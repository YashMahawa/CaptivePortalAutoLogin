#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d > recorder-logcat.txt || true' EXIT
adb install -r smoke-apks/app-x86_64-release.apk
adb shell pm grant de.binarynoise.captiveportalautologin android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant de.binarynoise.captiveportalautologin android.permission.ACCESS_FINE_LOCATION
adb shell pm grant de.binarynoise.captiveportalautologin android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant de.binarynoise.captiveportalautologin android.permission.POST_NOTIFICATIONS
adb shell cmd location set-location-enabled true
adb shell am instrument -w de.binarynoise.captiveportalautologin/de.binarynoise.captiveportalautologin.RecorderSmokeInstrumentation | tee smoke-result.txt
grep -q 'Recorder page load, traffic capture, encrypted profile, and manual HTTP login passed' smoke-result.txt
