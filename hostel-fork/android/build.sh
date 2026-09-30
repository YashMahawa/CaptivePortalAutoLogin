#!/usr/bin/env bash
set -euo pipefail
SDK="${ANDROID_HOME:-/tmp/android-sdk}"
TOOLS="$SDK/build-tools/36.0.0"
cd "$(dirname "$0")"
mkdir -p build/classes build/dex build/out
if command -v javac >/dev/null; then
  javac -source 8 -target 8 -cp "$SDK/platforms/android-36/android.jar" -d build/classes src/org/yash/hostelwifi/*.java
else
  java -jar "${ECJ_JAR:?Set ECJ_JAR to an Eclipse compiler jar if javac is unavailable}" -nowarn -source 8 -target 8 -cp "$SDK/platforms/android-36/android.jar" -d build/classes src/org/yash/hostelwifi/*.java
fi
"$TOOLS/d8" --min-api 26 --lib "$SDK/platforms/android-36/android.jar" --output build/dex $(find build/classes -name '*.class')
"$TOOLS/aapt2" compile --dir res -o build/out/res.zip
"$TOOLS/aapt2" link --auto-add-overlay -R build/out/res.zip -o build/out/base.apk --manifest AndroidManifest.xml -I "$SDK/platforms/android-36/android.jar" --min-sdk-version 26 --target-sdk-version 35 --version-code 1 --version-name 1.0 --rename-manifest-package org.yash.hostelwifi
cd build/dex
zip -q -u ../out/base.apk classes.dex
cd ../..
if [ ! -f build/signing.jks ]; then keytool -genkeypair -keystore build/signing.jks -storepass changeit -keypass changeit -alias hostel -keyalg RSA -keysize 3072 -validity 3650 -dname 'CN=Hostel Wi-Fi' >/dev/null 2>&1; fi
"$TOOLS/zipalign" -f 4 build/out/base.apk build/out/aligned.apk
"$TOOLS/apksigner" sign --ks build/signing.jks --ks-key-alias hostel --ks-pass pass:changeit --key-pass pass:changeit --out build/out/Hostel-WiFi-arm64-v8a.apk build/out/aligned.apk
"$TOOLS/apksigner" verify --verbose build/out/Hostel-WiFi-arm64-v8a.apk
