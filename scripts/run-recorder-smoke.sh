#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d > recorder-logcat.txt || true' EXIT
adb install smoke-apks/upstream-fixture.apk
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural
adb shell cmd location set-location-enabled true
for format in release direct; do
  adb install -r "smoke-apks/app-x86_64-$format.apk"
  adb install -r "smoke-apks/app-x86_64-$format.apk"
  for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION ACCESS_BACKGROUND_LOCATION POST_NOTIFICATIONS; do
    adb shell pm grant de.binarynoise.captiveportalautologin.college "android.permission.$permission"
  done
  adb shell am instrument -w de.binarynoise.captiveportalautologin.college/de.binarynoise.captiveportalautologin.RecorderSmokeInstrumentation | tee "smoke-$format.txt"
  grep -q 'Recorder page load, traffic capture, encrypted profile, and manual HTTP login passed' "smoke-$format.txt"
done
cat smoke-release.txt smoke-direct.txt > smoke-result.txt
