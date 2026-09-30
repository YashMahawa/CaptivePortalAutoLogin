# Hostel Wi-Fi

A compact, event-driven captive portal helper inspired by [CaptivePortalAutoLogin](https://github.com/binarynoise/CaptivePortalAutoLogin). This is an independent implementation placed alongside the cloned upstream repository. No upstream source is bundled in the release packages.

## Android

1. Install the signed APK. It uses Java bytecode and contains no architecture-specific native libraries, so it works on arm64-v8a devices.
2. Enter the actual college portal URL and your credentials, then save.
3. Open the portal in the app, wait for the login page, and tap **Save form on this page** before signing in. This records a standard POST form's action and field names.
4. Return and tap **Log in now**. **Ask Android to recheck** triggers Android's network validation. Enable auto login for an event-driven foreground service and allow its notification.

Credentials are encrypted with an Android Keystore AES-GCM key. The recorded form action, names and hidden values stay in app-private storage. Portal HTML with expiring hidden tokens will require a portal-specific handler. HTTP status alone is not proof of working internet; Android's validation state is shown when updated. The phone must remain available for automatic login. Android may not detect a captive portal behind a personal router. If the router or campus controls access by its WAN MAC, configure login on a device connected to the router or on the router itself. This app cannot guarantee uninterrupted internet for smart lights while the phone is off or the campus session expires.

Build: Android SDK platform 36 and build-tools 36, Java compiler (ECJ in the provided `build.sh` path). `ANDROID_HOME=/path/to/sdk ./android/build.sh`. The resulting APK is signed with a generated local key. Keep that key to install future updates over this build.

## Linux

See `linux/README.md`. The companion uses Python standard library and NetworkManager; it is distributed separately.
# Archived prototype

This directory preserves the first experimental implementation for history. It is not used by the APK or Linux workflow. Use the original app in `app/` and the maintained Linux CLI in `linux/`.
