#!/usr/bin/env bash
set -euo pipefail
# Simulate an installed upstream/debug app with a different signing certificate.
fixture=$(mktemp -d)
trap 'rm -rf "$fixture"' EXIT
cat > "$fixture/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="de.binarynoise.captiveportalautologin" android:versionCode="9999">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="35" />
  <application android:label="Install conflict test fixture" android:hasCode="false" />
</manifest>
XML
sdk_tools="$ANDROID_HOME/build-tools/36.0.0"
"$sdk_tools/aapt" package -f -M "$fixture/AndroidManifest.xml" -I "$ANDROID_HOME/platforms/android-36/android.jar" -F "$fixture/unsigned.apk"
keytool -genkeypair -keystore "$fixture/fixture.jks" -storepass fixture-password -keypass fixture-password -alias fixture -dname 'CN=Unrelated install test key' -keyalg RSA -validity 30 -noprompt
mkdir -p smoke-fixture
"$sdk_tools/apksigner" sign --ks "$fixture/fixture.jks" --ks-pass pass:fixture-password --out smoke-fixture/upstream-fixture.apk "$fixture/unsigned.apk"
