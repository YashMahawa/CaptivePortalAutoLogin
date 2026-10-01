#!/usr/bin/env bash
set -euo pipefail
trap 'adb logcat -d > recorder-logcat.txt || true' EXIT
adb install -r smoke-apks/app-x86_64-release.apk
adb install -r smoke-apks/app-x86_64-release-androidTest.apk
adb shell am instrument -w de.binarynoise.captiveportalautologin.test/de.binarynoise.captiveportalautologin.RecorderSmokeInstrumentation | tee smoke-result.txt
grep -q 'Recorder page load, traffic capture, encrypted profile, and manual HTTP login passed' smoke-result.txt
