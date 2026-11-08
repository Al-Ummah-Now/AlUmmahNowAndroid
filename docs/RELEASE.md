# Release & signing

**Rule:** production releases are built and uploaded **only by CI** (`.github/workflows/release.yml`) from a
`vYYYY.MM.DD` tag (optionally `.N` for a second release that day) on `main`. Never upload by hand, and never
run `publish*` tasks locally.

## Application IDs

| App | Play / Amazon id | QA build id |
|---|---|---|
| Viewer (phone, tablet, foldable, Android Auto) | `net.alummahnow.app` | `net.alummahnow.app.qa` |
| Viewer TV (Android TV / Google TV) | `net.alummahnow.tv` | `net.alummahnow.tv.qa` |
| Viewer TV (Fire TV, Amazon Appstore) | `net.alummahnow.tv` (amazon flavour) | `net.alummahnow.tv.qa` |
| Studio | `net.alummahnow.studio` | `net.alummahnow.studio.qa` |

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
4. **GitHub secrets**, stored in the `play-release` environment (protected: only `main` tags):

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
   No `google-services.json` is needed for anything in this repo today.
7. **Store listing assets** in Arabic and English (screenshots per locale, feature graphic, TV banner),
   privacy policy URL, the data safety form, and content rating (Epic 23.5).

## versionCode

Derived from the tag: `(days since 2026-01-01) × 100 + N`. It always increases, and allows up to 100 releases a day.

## Tracks

CI uploads to **internal** testing. Promotion to closed / open / production and staged rollout percentages are
done in Play Console by the owner (Epic 23.3–23.4) until we automate promotion with `promoteArtifact`.
