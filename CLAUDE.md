# CLAUDE.md

Native Android apps for Al Ummah Now (viewer: phone, tablet, foldable, Android TV / Fire TV, Android Auto;
Studio mobile). Kotlin + Jetpack Compose + Media3. **Public repo.** Plan: `docs/PLAN.md`.
Spec: `docs/native-apps/EPICS.md` in the AlUmmahNow monorepo.

## Commands

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21   # macOS (brew install openjdk@21); CI uses Temurin 21
./gradlew assembleDebug                          # every app × flavour
./gradlew ktlintCheck detekt lint                # static checks (ktlintFormat to auto-fix)
./gradlew testDebugUnitTest testQaDebugUnitTest testQaGoogleDebugUnitTest   # JVM tests incl. Robolectric
./gradlew recordRoborazziDebug recordRoborazziQaDebug recordRoborazziQaGoogleDebug   # re-record goldens (on Linux)
./gradlew :viewer-mobile:installQaDebug          # run against the QA API
./gradlew :viewer-mobile:connectedQaDebugAndroidTest   # needs a running emulator
```

## Layout

`build-logic/` convention plugins · `gradle/libs.versions.toml` versions · `core-bridge` (interfaces in front
of aun-core) · `design` (Fanous theme) · `player` (Media3) · `feature-*` · apps `viewer-mobile`,
`viewer-tv` (store flavours google/amazon), `studio-mobile`. Every app has `env` flavours `qa` / `prod`.

## Rules

- **Never commit secrets:** keystores, `google-services.json`, service-account JSON, API keys, `.env`.
  Push protection is on; `.gitignore` covers the usual names.
- **Never publish to Play or Amazon by hand.** Releases come only from `release.yml` on a `vYYYY.MM.DD` tag on `main`.
- **Every string goes in resources, in both English and Arabic** (`values/` and `values-ar/`). No hardcoded
  UI text. Use `start`/`end`, never `left`/`right`. Every UI change needs screenshot tests in both `en` and
  `ar` (RTL), plus light and dark.
- **No TODO comments.** Implement it now, or open a GitHub issue labelled `queue` and reference it.
- **Never call the backend directly from features.** Go through `:core-bridge` interfaces; they will be
  backed by the Rust core (UniFFI AAR).
- **Logging:** use `AunLog` from `:core-bridge` (tagged, stripped of PII). No `println`. Use `Log.*` only inside `AunLog`.
- Small PRs into `main`, CI green, squash-merge.
- Fanous tokens in `:design` are placeholders from the spec (§3) until Epic 10 ships the generated theme;
  don't hand-tune colours elsewhere.
- Golden screenshots are recorded on Linux. Re-record them with the "Record screenshots" workflow, not on macOS.
