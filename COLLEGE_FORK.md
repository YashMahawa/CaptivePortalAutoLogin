# College Wi-Fi fork

Source and build history: https://github.com/YashMahawa/CaptivePortalAutoLogin

The original settings interface and Gecko recorder are retained. The previous delivered v2 source is preserved as a separate commit; `hostel-fork/` archives the earlier experimental rewrite and is not built.

## Builds and signing

Every push to `main` runs Android unit tests and builds a release arm64-v8a APK plus a separate Linux CLI ZIP on a GitHub-hosted runner. Successful builds publish test-build releases with SHA256 checksums. Pull requests build and test without access to the release signing secrets.

The signing keystore and its passwords are GitHub Actions repository secrets. The GitHub personal access token is never committed and is not supplied to Actions. Actions uses its short-lived `GITHUB_TOKEN` for release publication.

The temporary debug signing key used for the earlier APK was lost when the build environment reset. The first APK signed with the durable fork key requires uninstalling the earlier clone and entering its credentials again. Later fork builds use the same key and increasing version codes.

## Login behavior and verification limits

- Save the exact portal login URL, username and password under Manual login. Details are encrypted with Android Keystore. Enable saved automatic login while connected to the intended Wi-Fi.
- Automatic attempts follow Android captive-portal callbacks. Failed attempts retry after 1, 2, 4, 8, then at most 15 minutes. These handler retries run while the phone is awake; they do not use a wake lock or periodic background polling.
- The last attempt and retry information remain visible in the main settings and manual-login screen.
- Manual login fetches fresh fields and cookies. It supplies the configured browser user agent, Referer and Origin. It supports ordinary HTML POST forms and same-host HTTP-to-HTTPS upgrades, not arbitrary JavaScript/SSO flows.
- Before manual submission, a short HTTPS connectivity check runs through the selected Wi-Fi, so a portal page without a login form is not treated as an error when internet already works. Failure of this check is not proof that the internet is down; submission and the original portal verification continue.
- The recorder no longer treats an already-validated network as a completed recording before loading a page. It allows the capture extension in its private session and defers runtime initialization until after selecting the network. Automatic login pauses while recording.
- Upstream Capture records HAR traffic for diagnosing a portal. It does not generate or replay a login macro. A particular college portal cannot be claimed supported without its URL, form behavior and an actual login test. Never send a password in an issue or commit a HAR containing credentials.

The Linux ZIP contains the original Java CLI. It does not include the Android credential editor or Gecko recorder.
