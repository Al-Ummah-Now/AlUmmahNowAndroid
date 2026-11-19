#!/usr/bin/env bash
# Runs inside android-emulator-runner on PRs and main: instrumented tests, then en + ar screenshots of the viewer.
# Tests against the deployed QA (QA_DEPLOYED_TESTS) are left out here: they run only on releases
# (.github/workflows/qa-e2e.yml, called by release.yml). Owner rule 2026-11-13: no E2E against deployed QA
# on PRs, main or a schedule.
set -euo pipefail
source "$(dirname "$0")/qa-deployed-tests.sh"
./gradlew :viewer-mobile:connectedQaDebugAndroidTest :studio-mobile:connectedQaDebugAndroidTest :admin-mobile:connectedQaDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.notClass="$QA_DEPLOYED_TESTS" --continue
mkdir -p emulator-screenshots
APK=viewer-mobile/build/outputs/apk/qa/debug/viewer-mobile-qa-debug.apk
PKG=net.alummahnow.app.qa
adb install -r "$APK"
for locale in en ar; do
  adb shell cmd locale set-app-locales "$PKG" --locales "$locale" || true
  adb shell am force-stop "$PKG"
  adb shell am start -W -n "$PKG/net.alummahnow.viewer.MainActivity"
  sleep 12
  adb exec-out screencap -p > "emulator-screenshots/viewer-home-$locale.png"
done
