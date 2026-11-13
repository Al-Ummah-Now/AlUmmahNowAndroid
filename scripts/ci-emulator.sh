#!/usr/bin/env bash
# Runs inside android-emulator-runner: instrumented tests, then en + ar screenshots of the viewer.
set -euo pipefail
./gradlew :viewer-mobile:connectedQaDebugAndroidTest :studio-mobile:connectedQaDebugAndroidTest :admin-mobile:connectedQaDebugAndroidTest --continue
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
