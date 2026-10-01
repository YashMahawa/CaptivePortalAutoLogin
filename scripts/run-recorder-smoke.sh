#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d > recorder-logcat.txt || true' EXIT
adb install smoke-apks/upstream-fixture.apk
adb install smoke-apks/app-x86_64-release.apk
# Verify a later install can update the new fork without removing its data.
adb install -r smoke-apks/app-x86_64-release.apk
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural
adb shell settings put global force_resizable_activities 1
adb shell pm grant de.binarynoise.captiveportalautologin.college android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant de.binarynoise.captiveportalautologin.college android.permission.ACCESS_FINE_LOCATION
adb shell pm grant de.binarynoise.captiveportalautologin.college android.permission.ACCESS_BACKGROUND_LOCATION
adb shell pm grant de.binarynoise.captiveportalautologin.college android.permission.POST_NOTIFICATIONS
adb shell cmd location set-location-enabled true
adb shell am instrument -w de.binarynoise.captiveportalautologin.college/de.binarynoise.captiveportalautologin.RecorderSmokeInstrumentation | tee smoke-result.txt
grep -q 'Recorder page load, traffic capture, encrypted profile, and manual HTTP login passed' smoke-result.txt
