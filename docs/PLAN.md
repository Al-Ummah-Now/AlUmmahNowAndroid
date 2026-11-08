# Al Ummah Now Android: plan

Source spec: `docs/native-apps/EPICS.md` in the monorepo (§1–§4; Epics 12–15, 17, 18, 20–23).
This repo holds every native Android app: the viewer on phone, tablet and foldable; Android TV / Google TV
and Fire TV; Android Auto; and Studio mobile.

## 1. Modules

```
:core-bridge     Kotlin interfaces in front of every aun-core (Rust/UniFFI) call + temporary Kotlin impls
:design          Fanous theme (placeholder tokens from spec §3), components, brand assets, launcher icon
:player          Media3 wrapper: ExoPlayer HLS, quality/speed/subtitles, MediaLibraryService
                 (background audio, lock screen, Android Auto browse tree), Chromecast
:feature-home    Home rails (phone/tablet/foldable)
:feature-watch   Watch page (player + details + chapters + related), adaptive two-pane
:feature-tv      10-foot home + watch for Android TV / Fire TV (Compose for TV)
:feature-upload  Studio upload: picker, TUS via core-bridge, WorkManager background worker
:feature-editor  Studio edit spike: Media3 Transformer trim / concat / text overlay (Arabic shaping)
:feature-live    Studio go-live research spike behind a LiveBroadcaster interface (D3 pending)

:viewer-mobile   app  net.alummahnow.app      (+ Android Auto media browsing)
:viewer-tv       app  net.alummahnow.tv       flavours: store = google | amazon (Fire TV)
:studio-mobile   app  net.alummahnow.studio
```

Every app also has an `env` flavour dimension: `qa` (`.qa` id suffix, `*.qa.alummahnow.net`) and `prod`.
Shared Gradle settings live in `build-logic/` convention plugins (`aun.android.library`,
`aun.android.application`, `aun.compose`). Versions live in `gradle/libs.versions.toml`.

Dependency direction: `apps → feature-* → (player, design, core-bridge)`; features never depend on each other.

## 2. Architecture

- **UI:** Jetpack Compose + Material 3 (phone/tablet), Compose for TV (`androidx.tv:tv-material`) on TV.
  Adaptive layouts use window size classes: compact = one pane; medium/expanded (tablets, unfolded
  foldables) = two-pane watch and more rail columns.
- **State:** one `ViewModel` per screen that exposes a `StateFlow<UiState>` (a sealed Loading / Content / Error);
  coroutines + Flow; UI is a pure function of state.
- **DI:** manual. Each app builds an `AppContainer` in its `Application` and hands it to ViewModel factories.
  This keeps the build free of KSP/kapt and makes the core-bridge swap (temp impl → UniFFI AAR) a one-line
  change in the container. Hilt can come later if the graph grows.
- **Core bridge:** every call that `aun-core` will own (`aun-api`, `aun-auth`, `aun-upload`, `aun-media`,
  `aun-i18n`, `aun-telemetry`) goes through a Kotlin interface in `:core-bridge`
  (`CatalogRepository`, `AuthBridge`, `UploadEngine`, `EditPlanner`). Until the AAR is published,
  `Temporary*` implementations use OkHttp + kotlinx.serialization against the public QA API, and the
  TUS Java client for uploads. When the AAR lands, add `UniFfi*` implementations and switch the container.
- **Player:** one `MediaLibraryService` (`AunPlaybackService`) owns the ExoPlayer, so playback continues in
  the background, shows lock-screen / notification controls, and exposes the browse tree used by Android Auto.
  UI connects with a `MediaController`. Chromecast uses `media3-cast` `CastPlayer`, switching player on
  session start/end.
- **i18n / RTL:** Arabic and English resources for every string (`values/`, `values-ar/`), per-app language
  (`generateLocaleConfig`), `supportsRtl`, no hardcoded strings, mirrored seek (±10 s) in RTL.
  Localised API fields (`titleLocalized`, `descriptionLocalized`, chapter titles) are picked by locale.
- **Theming:** dark (night) by default, light (parchment) supported; follows system unless the user picks one.
  Placeholder tokens from spec §3 live in `:design` `FanousTokens` until Epic 10 publishes the generated theme.
- **Auth:** not wired yet. Sign-in needs a registered native PKCE client (Epic 9.1) for viewer-mobile and
  studio-mobile and the device flow (9.2) for TV; the parent agent registers them. No workarounds.

## 3. Testing

| Layer | Tool | Where it runs |
|---|---|---|
| Pure logic (repositories, mappers, ViewModels, quality/speed policies) | JUnit4, kotlinx-coroutines-test, Turbine, MockWebServer | JVM (`test*UnitTest`) |
| Compose UI behaviour | Compose UI test on Robolectric | JVM |
| Screenshot tests: en + ar (RTL), light + dark, phone / tablet / TV | Roborazzi on Robolectric (native graphics) | JVM; goldens in `*/src/test/screenshots` |
| Instrumented (real app, real player, real network to QA) | AndroidX Test + Compose UI test | API 35 emulator in GitHub Actions |
| Static | ktlint, detekt, Android lint | CI |

Goldens are recorded on Linux (CI image) so renders match the CI machine: run the **Record screenshots**
workflow, or `./gradlew recordRoborazziDebug` on Linux. CI fails on any diff.

## 4. Environments

| Flavour | API | Web (share links) | applicationId suffix |
|---|---|---|---|
| `qa` | `https://videos.api.qa.alummahnow.net` | `https://videos.qa.alummahnow.net` | `.qa` |
| `prod` | `https://videos.api.alummahnow.net` | `https://videos.alummahnow.net` | none |

## 5. Release

See `docs/RELEASE.md`. Summary: Gradle Play Publisher uploads `prod` release bundles to the Play
**internal** track, triggered only by a `vYYYY.MM.DD[.N]` tag on `main`. Nothing is published by hand.
Signing is off until the owner provides a Play Console account, an upload key, and a service account.

## 6. Delivery order

1. Scaffold + CI + docs (this PR).
2. `:core-bridge` + `:design` (Fanous placeholder theme, RTL, components) with screenshot tests.
3. Viewer phone/tablet: home rails + watch page with Media3 HLS (quality, speed, subtitles, PiP, background
   audio, Chromecast), adaptive layouts.
4. Android TV / Fire TV (Compose for TV, focus, 10-foot).
5. Android Auto media browsing (MediaLibraryService browse tree).
6. Studio mobile: upload skeleton (TUS + WorkManager), Transformer edit spike, live-broadcast research.

Follow-ups beyond the first slice (search, library, channel page, comments, downloads, sign-in, Kids,
Islamic layer, notifications) are tracked as GitHub issues.
