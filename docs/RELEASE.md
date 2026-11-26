# Release & signing

**Rule:** production releases are built and uploaded **only by CI** (`.github/workflows/release.yml`) from a
`vYYYY.MM.DD` tag (optionally `.N` for a second release that day) on `main`. Never upload by hand, and never
run `publish*` tasks locally.

## What a release tag does today

A `vYYYY.MM.DD[.N]` tag on `main` runs `.github/workflows/release.yml`. versionName is the tag without `v`
(`2026.11.14`, QA builds add `-qa`); versionCode is derived from it (below). Nothing fails because a secret or variable
is missing: that step is skipped with a `::notice::` naming what is missing.

| Job | Does | Needs (else skipped with a notice) |
|---|---|---|
| `qa-e2e` (gate) | instrumented tests against the deployed QA (`qa-e2e.yml`, list in `scripts/qa-deployed-tests.sh`): Studio's sign-in → upload → bulk edit → delete/restore → sign-out flow and the viewer's QA playback. `github-release` and `play` wait for it, so a red gate publishes nothing. PRs and `main` never run these tests (owner rule 2026-11-13: QA E2E only on releases); run it by hand from Actions → **QA E2E (release gate)** | `QA_SEED_PASSWORD` (fails, not skipped, when missing) |
| `github-release` | GitHub Release with `AlUmmahNowStudio-<v>.apk` (prod), `-qa.apk`, `.aab`, `SHA256SUMS.txt` | the upload key secrets, **or** the pre-release key secrets (set) |
| `play` → build | signed prod bundles for viewer, TV (Google), Studio as the `play-bundles` artifact (for each app's first manual upload), and the signed Fire TV APK as `fire-tv-apk` | the four `AUN_UPLOAD_*` secrets |
| `play` → upload | per app: bundle + store listing (texts from `src/main/play/`, screenshots generated in the job) to the Play **internal** track | the above + `PLAY_SERVICE_ACCOUNT_JSON` + that app's repo variable `PLAY_VIEWER_READY` / `PLAY_TV_READY` / `PLAY_STUDIO_READY` = `true` |
| `play` → Admin | Admin bundle to internal testing (never an artifact) | the above + `ADMIN_PLAY_READY=true`; Firebase from `ADMIN_GOOGLE_SERVICES_JSON` |
| `amazon` | Fire TV APK into the app's open Amazon Appstore edit (not submitted for review) | the build step + `AMAZON_APPSTORE_CLIENT_ID`, `AMAZON_APPSTORE_CLIENT_SECRET`, `AMAZON_APPSTORE_APP_ID` |

Until the owner provides the upload key, the GitHub release is a **pre-release** signed with the CI
**pre-release key** (`AUN_PRERELEASE_*` secrets, alias `aun-prerelease`, certificate SHA-256
`5A:1B:3D:2B:57:6E:9A:2D:F0:BF:1B:E3:F1:8C:40:20:13:F9:5C:09:EA:EE:7C:F7:31:10:C4:2F:B5:69:DD:C0`).
Testers can install and update it from the release page. Play never accepts it, and a copy installed
from Play can't be updated with it (or the other way round).

Every CI run also uploads `studio-apks-debug-signed`: QA debug plus QA/prod **release** (R8) APKs
signed with that run's debug key, for quick testing.

## Store listing files

Gradle Play Publisher's layout, checked in, en-US and ar with the same files (`./gradlew checkStoreListings` enforces
Play's limits, en/ar parity and the copy-tone words; CI runs it):

```
<viewer-mobile|viewer-tv|studio-mobile>/src/main/play/
  listings/{en-US,ar}/title.txt               ≤ 30
  listings/{en-US,ar}/short-description.txt   ≤ 80
  listings/{en-US,ar}/full-description.txt    ≤ 4000
  listings/{en-US,ar}/video-url.txt           optional (https)
  listings/{en-US,ar}/graphics/*-screenshots/ generated, gitignored
  release-notes/{en-US,ar}/internal.txt       ≤ 500, per track
```

**Screenshots** come from the real Compose screens with fixture data (`*/src/test/.../StoreScreenshots.kt`,
Roborazzi): phone 1080×1920, 7" tablet 1200×1920, 10" tablet 2560×1600, TV 1920×1080, en + ar, dark (+ light on
phones). Actions → **Store screenshots** → Run gives them as an artifact; the release job regenerates them before it
uploads a listing. Locally: `./gradlew -PaunStoreScreenshots=true
:viewer-mobile:testProdDebugUnitTest --tests '*StoreScreenshots*' :studio-mobile:testProdDebugUnitTest --tests '*StoreScreenshots*'
:viewer-tv:testProdGoogleDebugUnitTest --tests '*StoreScreenshots*'` (`--tests` filters the task before it).
The TV app's UI is still a placeholder (feature-tv), so its screenshots show that until the TV screens land: keep
`PLAY_TV_READY` off until then.

**Data safety** answers, drawn from the code with a source file per answer: [`store/play/data-safety.md`](../store/play/data-safety.md)
(and `.csv`).

## Application IDs

| App | Play / Amazon id | QA build id |
|---|---|---|
| Viewer (phone, tablet, foldable, Android Auto) | `net.alummahnow.app` | `net.alummahnow.app.qa` |
| Viewer TV (Android TV / Google TV) | `net.alummahnow.tv` | `net.alummahnow.tv.qa` |
| Viewer TV (Fire TV, Amazon Appstore) | `net.alummahnow.tv` (amazon flavour) | `net.alummahnow.tv.qa` |
| Studio | `net.alummahnow.studio` | `net.alummahnow.studio.qa` |
| Admin (staff only, Play internal testing only) | `net.alummahnow.admin` | `net.alummahnow.admin.qa` |

## Owner checklist

Everything is built; these are the only things to paste or click. Each one unlocks its step on the next tag.

### 1. Upload key (unlocks signed bundles + the GitHub release on the upload key)

```bash
keytool -genkeypair -v -keystore upload.jks -alias aun-upload -keyalg RSA -keysize 4096 -validity 10000
base64 -i upload.jks | pbcopy                                  # → AUN_UPLOAD_KEYSTORE_BASE64
keytool -list -v -keystore upload.jks -alias aun-upload | grep SHA256   # → "upload key SHA-256" below
```

GitHub → repo Settings → Environments → **`play-release`** and **`github-release`** (or repository secrets):

| Secret | Value |
|---|---|
| `AUN_UPLOAD_KEYSTORE_BASE64` | base64 of `upload.jks` |
| `AUN_UPLOAD_KEYSTORE_PASSWORD` | keystore password |
| `AUN_UPLOAD_KEY_ALIAS` | `aun-upload` |
| `AUN_UPLOAD_KEY_PASSWORD` | key password |

Protect both environments: deployment tags `v*` only, a required reviewer, and a tag ruleset so only admins create `v*`.

### 2. Google Play (unlocks uploads to the internal track)

1. Play Console (organisation account) → **Create app** for `net.alummahnow.app` (Al Ummah Now),
   `net.alummahnow.tv` (Al Ummah Now TV) and `net.alummahnow.studio` (Al Ummah Now Studio). Accept **Play App Signing**.
2. Google Cloud → enable the **Google Play Android Developer API** → create a service account → JSON key.
   Play Console → Users and permissions → invite the service account email → **Release manager** on the three apps
   (and Admin). Secret **`PLAY_SERVICE_ACCOUNT_JSON`** = the whole JSON (environment `play-release`).
3. Push a tag. Download the `play-bundles` artifact from the run. For each app: Testing → Internal testing → Create
   release → upload its `.aab` (Play requires the first upload by hand) → add testers.
4. Repo Settings → Variables → set `PLAY_VIEWER_READY=true`, `PLAY_STUDIO_READY=true` (and `PLAY_TV_READY=true` once
   the TV UI ships). From the next tag CI uploads bundles, listings and screenshots.
5. Per app, Policy → App content: **Privacy policy** URL, **App access** (Studio and Admin need sign-in: give Google's reviewers a
   dedicated reviewer account, never the owner's), **Ads: no**, **Content rating** questionnaire and **Target audience** from [`store/play/content-rating.md`](../store/play/content-rating.md) (re-check them at every release), **Data safety** from `store/play/data-safety.md`, **Data deletion** URL
   (the account deletion page). Store settings: app category, contact email and website (CI does not set them).
   The other App content declarations, which Play asks of every app:
   - **Foreground service permissions** (targetSdk 34+): one entry per type, each with a short screen recording of the
     user action that starts it. Viewer `mediaPlayback` (`AunPlaybackService`: playback the person started, continued in
     the background and in picture-in-picture). Studio declares `dataSync` for its WorkManager upload on Android
     13 and earlier. Record that flow on an API 33 device for the declaration. On Android 14 and later, Studio
     uses a user-initiated data transfer job and shows progress in the job notification. Admin and TV declare none.
   - **Financial features**: "My app doesn't provide any financial features" (creator support is a web checkout and is
     not in the apps). **Health apps**: none. **Government apps**, **News apps**: no.
   - **Privacy policy** URL: every app (viewer, TV, Studio) uses `https://www.alummahnow.net/en/privacy/` (Arabic: `https://www.alummahnow.net/ar/privacy/`; AlUmmahNow#1929). **Data deletion** URL: Studio, the app with accounts, uses `https://profile.auth.alummahnow.net/delete-account` (AlUmmahNowAuth#532).
6. Graphics not generated by CI, uploaded once in Console: feature graphic 1024×500 (each app), TV banner
   1280×720 (TV app). The app icon comes from the bundle.

### 3. Fingerprints to send (unlock App Links + passkeys)

After the first upload: Play Console → each app → Test and release → **App integrity → App signing** → copy the
**App signing key SHA-256** (and the upload key SHA-256 from step 1). Enter them in **videos admin → Mobile apps**
(it serves `https://videos.alummahnow.net/.well-known/assetlinks.json`):

| Package | Relations the server publishes | Fingerprints |
|---|---|---|
| `net.alummahnow.app` | `handle_all_urls` (App Links) | Play App Signing SHA-256 of the viewer, upload key SHA-256 |
| `net.alummahnow.studio` | `handle_all_urls` + `get_login_creds` | Play App Signing SHA-256 of Studio, upload key SHA-256 |
| `net.alummahnow.admin` | `get_login_creds` | Play App Signing SHA-256 of Admin, upload key SHA-256 |

QA (`videos.qa.alummahnow.net`): `net.alummahnow.studio.qa` with the pre-release key SHA-256 above (and any
developer debug key); `net.alummahnow.app.qa` with a developer debug key if wanted. Never a debug key on prod.
The same Studio/Admin fingerprints also go to `alummahnow.net` for passkeys ([PASSKEYS.md](PASSKEYS.md)).
The apps already claim the links: viewer `/app/auth/callback`, `/{en,ar}/{video,shorts,playlist,post}/…`, `/@…`
(also without the locale); Studio `/app/studio/…`, `/{en,ar}/studio/…`. Verify on a device with
`adb shell pm get-app-links net.alummahnow.app` (state `verified`).

### 4. Play Integrity (optional; attestation stays off until this is done)

Play Console → each of Studio and Admin → Test and release → **App integrity → Play Integrity API** → link the Google
Cloud project (same project as step 2 is fine) and enable the API there. Then **videos admin → Mobile apps →
Attestation**: Android **on**, **Cloud project number** = that project's number (Cloud console → project info).
Studio and Admin then send `X-AUN-Native-Proof` on writes; with it off they send nothing. Amazon / TV builds never
attest.

### 5. Amazon Appstore (unlocks the Fire TV upload)

1. Amazon Developer Console → Apps → **Add new app** (Android, Fire TV) → package `net.alummahnow.tv`. Note the
   **App ID** (`amzn1.devportal.mobileapp.…`).
2. Developer Console → Apps & Services → **API Access** → create a security profile for the **App Submission API** →
   copy its **Client ID** and **Client secret**.
3. Secrets in `play-release`: `AMAZON_APPSTORE_CLIENT_ID`, `AMAZON_APPSTORE_CLIENT_SECRET`, `AMAZON_APPSTORE_APP_ID`.
4. The first APK can be uploaded by hand from the `fire-tv-apk` artifact, or let CI put it in the edit. CI never
   submits: open the edit in the Developer Console and **Submit** when ready. Amazon re-signs with its own key.

### 6. Admin app and Firebase

See "Admin app (staff only)" below; `ADMIN_GOOGLE_SERVICES_JSON` = the whole `google-services.json` for
`net.alummahnow.admin`. Chromecast uses the default media receiver (`CC1AD845`); a branded receiver needs a Cast
Developer Console registration and an app id.

## Admin app (staff only)

The native Admin app (wiki AUN-ADMIN-NATIVE, AD10) ships **only** to Play internal testing. It is never
attached to a GitHub Release, and CI never uploads its APK as an artifact: this repo is public.

1. Create `net.alummahnow.admin` in Play Console (Play App Signing on, internal testing track, testers = staff
   Google accounts). Play needs the very first bundle uploaded in Console, and CI never exposes an Admin bundle, so
   build that one on your own machine with the upload key:
   `AUN_UPLOAD_KEYSTORE_PATH="$PWD/upload.jks" AUN_UPLOAD_KEYSTORE_PASSWORD=… AUN_UPLOAD_KEY_ALIAS=aun-upload AUN_UPLOAD_KEY_PASSWORD=… ./gradlew :admin-mobile:bundleProdRelease -PversionCode=1`
   → `admin-mobile/build/outputs/bundle/prodRelease/admin-mobile-prod-release.aab`. After that CI publishes.
2. Give the Play service account "Release manager" on it, too.
3. Set the repository variable `ADMIN_PLAY_READY` to `true`. From then on every release tag publishes it in the
   `play` job (same upload key and service account as the other apps).

## versionCode

Derived from the tag: `(days since 2026-01-01) × 100 + N`. It always increases, and allows up to 100 releases a day.

## Tracks

CI uploads to **internal** testing. Promotion to closed / open / production and staged rollout percentages are
done in Play Console by the owner (Epic 23.3–23.4) until we automate promotion with `promoteArtifact`.
