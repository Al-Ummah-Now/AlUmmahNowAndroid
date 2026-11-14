# Release & signing

**Rule:** production releases are built and uploaded **only by CI** (`.github/workflows/release.yml`) from a
`vYYYY.MM.DD` tag (optionally `.N` for a second release that day) on `main`. Never upload by hand, and never
run `publish*` tasks locally.

## What a release tag does today

A `vYYYY.MM.DD[.N]` tag on `main` runs `.github/workflows/release.yml`:

| Job | Output | Needs |
|---|---|---|
| `github-release` | GitHub Release with `AlUmmahNowStudio-<v>.apk` (prod), `-qa.apk`, `.aab`, `SHA256SUMS.txt` | the upload key secrets, **or** the pre-release key secrets (set) |
| `play` | prod bundles to the Play **internal** track (viewer, TV, Studio; Admin once `ADMIN_PLAY_READY`) + Fire TV APK artifact | the upload key secrets **and** `PLAY_SERVICE_ACCOUNT_JSON` |

Until the owner provides the upload key, the GitHub release is a **pre-release** signed with the CI
**pre-release key** (`AUN_PRERELEASE_*` secrets, alias `aun-prerelease`, certificate SHA-256
`5A:1B:3D:2B:57:6E:9A:2D:F0:BF:1B:E3:F1:8C:40:20:13:F9:5C:09:EA:EE:7C:F7:31:10:C4:2F:B5:69:DD:C0`).
Testers can install and update it from the release page. Play never accepts it, and a copy installed
from Play can't be updated with it (or the other way round). The `play` job is skipped with a warning
until its secrets exist; it does not fail the release.

Every CI run also uploads `studio-apks-debug-signed`: QA debug plus QA/prod **release** (R8) APKs
signed with that run's debug key, for quick testing.

## Application IDs

| App | Play / Amazon id | QA build id |
|---|---|---|
| Viewer (phone, tablet, foldable, Android Auto) | `net.alummahnow.app` | `net.alummahnow.app.qa` |
| Viewer TV (Android TV / Google TV) | `net.alummahnow.tv` | `net.alummahnow.tv.qa` |
| Viewer TV (Fire TV, Amazon Appstore) | `net.alummahnow.tv` (amazon flavour) | `net.alummahnow.tv.qa` |
| Studio | `net.alummahnow.studio` | `net.alummahnow.studio.qa` |
| Admin (staff only, Play internal testing only) | `net.alummahnow.admin` | `net.alummahnow.admin.qa` |

## What the owner must provide

1. **Google Play Console developer account** (organisation), with the three apps created under the ids above.
   Enrol them in **Play App Signing**: Google keeps the app signing key, and we hold only an upload key.
2. **Upload keystore** (one key for all three apps is fine). Create it locally and never commit it:
   ```bash
   keytool -genkeypair -v -keystore upload.jks -alias aun-upload -keyalg RSA -keysize 4096 -validity 10000
   base64 -i upload.jks | pbcopy   # paste into the secret below
   ```
3. **Play service account JSON** with the "Release manager" permission on the three apps
   (Play Console → Users and permissions; the Google Cloud project must enable the Android Publisher API).
4. **GitHub secrets**, stored in the `play-release` environment **and** in the `github-release`
   environment (or as repository secrets, which both environments can read). Protect it: deployment tags limited to `v*`, a required reviewer, and a tag ruleset so only admins can create `v*` tags.

   | Secret | Value |
   |---|---|
   | `AUN_UPLOAD_KEYSTORE_BASE64` | base64 of `upload.jks` |
   | `AUN_UPLOAD_KEYSTORE_PASSWORD` | keystore password |
   | `AUN_UPLOAD_KEY_ALIAS` | e.g. `aun-upload` |
   | `AUN_UPLOAD_KEY_PASSWORD` | key password |
   | `PLAY_SERVICE_ACCOUNT_JSON` | the full service-account JSON |

5. **Amazon Appstore developer account** for Fire TV. Until an Appstore submission API key is provided, CI
   builds the signed Fire TV APK as an artifact on each release tag.
6. **Firebase / Cast:** the Chromecast receiver uses the default media receiver
   (`CC1AD845`). A custom branded receiver needs a Cast Developer Console registration ($5) and an app id.
   The viewer and Studio need no `google-services.json`. The **Admin app** uses FCM for review alerts: add an
   Android app `net.alummahnow.admin` (and `net.alummahnow.admin.qa` for QA builds) to the Firebase project,
   download its `google-services.json` and store the whole file as the secret `ADMIN_GOOGLE_SERVICES_JSON`
   (the server side needs the matching FCM service account, AlUmmahNow#1264). Without it the app builds and
   runs with alerts off and says so in Settings.
7. **Passkey fingerprints:** once Play App Signing is on, send the server config the **app signing key SHA-256** of
   `net.alummahnow.studio` and `net.alummahnow.admin` (Play Console → App integrity → App signing) and the **upload
   key SHA-256**. They go in `assetlinks.json` on `alummahnow.net` and in the auth server's accepted
   `android:apk-key-hash:` origins. Details: [PASSKEYS.md](PASSKEYS.md).
8. **Store listing assets** in Arabic and English (screenshots per locale, feature graphic, TV banner),
   privacy policy URL, the data safety form, and content rating (Epic 23.5).

## Admin app (staff only)

The native Admin app (wiki AUN-ADMIN-NATIVE, AD10) ships **only** to Play internal testing. It is never
attached to a GitHub Release, and CI never uploads its APK as an artifact: this repo is public.

1. Create `net.alummahnow.admin` in Play Console (Play App Signing on, internal testing track, testers = staff
   Google accounts). Play needs the very first bundle uploaded in Console; after that CI publishes.
2. Give the Play service account "Release manager" on it, too.
3. Set the repository variable `ADMIN_PLAY_READY` to `true`. From then on every release tag publishes it in the
   `play` job (same upload key and service account as the other apps).

## versionCode

Derived from the tag: `(days since 2026-01-01) × 100 + N`. It always increases, and allows up to 100 releases a day.

## Tracks

CI uploads to **internal** testing. Promotion to closed / open / production and staged rollout percentages are
done in Play Console by the owner (Epic 23.3–23.4) until we automate promotion with `promoteArtifact`.
