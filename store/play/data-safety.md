# Play data safety answers

Drawn from what the code really sends off the device (audited 2026-11-14). The machine-readable copy is
[`data-safety.csv`](data-safety.csv): one row per data type, with the source file for each answer.
`./gradlew checkStoreListings` fails if a cited source file disappears, so a refactor flags this page for review.

Play Console asks these per app under **Policy → App content → Data safety**. The Console's CSV import uses its own
question ids, which change; fill the form from the tables below (about five minutes per app).

## Answers shared by every app

| Question | Answer | Why (source) |
|---|---|---|
| Does your app collect or share any of the required user data types? | Viewer and TV: **No**. Studio and Admin: **Yes** | tables below |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | every base URL is `https://` (`build-logic/convention/src/main/kotlin/AndroidApplicationConventionPlugin.kt`); Admin traffic refuses plain HTTP on every hop (`core-bridge/.../admin/PrivateHttp.kt`) |
| Do you provide a way for users to request that their data is deleted? | **Yes**: the account deletion page (owner pastes its URL, see `docs/RELEASE.md`) | accounts live on the Auth server, not in the app |
| Data shared with third parties? | **No** for every type. Bunny / Cloudflare store and deliver media for us (service providers, which Play does not count as sharing) | — |
| Crash logs, diagnostics, analytics, advertising ID | **Not collected** by any app: logging goes to the device log only (`core-bridge/.../AunLog.kt`); no analytics, crash-reporting or ads SDK in `gradle/libs.versions.toml` | — |
| Location, contacts, messages, audio, calendar, health, financial info | **Not collected** | no such permissions in any `AndroidManifest.xml` |

## Viewer (`net.alummahnow.app`) and TV (`net.alummahnow.tv`)

**No data collected, no data shared.** The viewer has no account (`GuestOnlyAuthBridge` in
`viewer-mobile/.../AppContainer.kt`) and only reads the public catalogue (`TemporaryCatalogRepository.kt`). Theme and
language choices stay on the device (`DisplayPrefsStore.kt`). The TV app has no network features yet.

## Studio (`net.alummahnow.studio`)

| Category → type | Collected | Shared | Ephemeral | Required? | Purposes | When / what (source) |
|---|---|---|---|---|---|---|
| Personal info → User IDs | Yes | No | No | Required | App functionality, Account management | signed-in calls carry the account's access token (`CoreAccountSession.kt`). The email and password are typed into the Auth web page in a browser tab, not into the app |
| Personal info → Other info | Yes | No | No | Optional | Account management | only when adding or using a passkey: the WebAuthn response goes to the Auth server (`PasskeyApi.kt`) |
| Photos and videos → Videos | Yes | No | No | Optional | App functionality | only the files the person picks to upload (`UploadScreen.kt`, `CoreUploadQueue.kt`) |
| App activity → Other user-generated content | Yes | No | No | Optional | App functionality | titles, descriptions, tags, category, language, playlists, schedules (`CoreStudioRepository.kt`) |
| App info and performance → Other app performance data | Yes | No | Yes | Required | Fraud prevention, security, and compliance | only when the videos admin turns Android attestation on: a Play Integrity verdict (no device id) is exchanged for a short-lived proof (`PlayIntegrityProofProvider.kt`) |

## Admin (`net.alummahnow.admin`, staff only)

Play does not ask for the data safety form while an app is only on internal testing. If that changes:

| Category → type | Collected | Shared | Ephemeral | Required? | Purposes | When / what (source) |
|---|---|---|---|---|---|---|
| Personal info → User IDs | Yes | No | No | Required | App functionality, Account management | the reviewer's access token (`HttpReviewService.kt`) |
| Device or other IDs | Yes | No | No | Optional | App functionality | review alerts: FCM token, a random install id, locale, app version; removed on sign-out (`AdminPush.kt`, `AdminPrefs.kt`) |
| App activity → Other actions | Yes | No | No | Required | App functionality | review decisions, notes, reveals, screenshot audit events (`HttpReviewService.kt`) |
| App info and performance → Other app performance data | Yes | No | Yes | Required | Fraud prevention, security, and compliance | only when Android attestation is on |

## Keep it true

Adding analytics, crash reporting, push to Studio or the viewer, sign-in to the viewer, or any new upload type changes
these answers: update the CSV and this page in the same PR, and tell the owner to update the Console form.
