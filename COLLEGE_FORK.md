# College Wi-Fi fork

Source and build history: https://github.com/YashMahawa/CaptivePortalAutoLogin

The original settings interface and Gecko recorder are retained. The previous delivered v2 source is preserved as a separate commit; `hostel-fork/` archives the earlier experimental rewrite and is not built.

## Builds and signing

Every push to `main` runs Android unit tests and builds a release arm64-v8a APK plus a separate Linux CLI ZIP on a GitHub-hosted runner. Publication also requires an Android 15 and 16 emulator checks that loads a synthetic portal in the actual Gecko recorder, verifies traffic capture, round-trips encrypted credentials and submits a fresh login form with cookies, Origin and Referer. The smoke runner exists only in the emulator APK, not the delivered arm64 APK. Successful builds publish test-build releases with SHA256 checksums. Pull requests build and test without access to the release signing secrets.

The signing keystore and its passwords are GitHub Actions repository secrets. The GitHub personal access token is never committed and is not supplied to Actions. Actions uses its short-lived `GITHUB_TOKEN` for release publication.

The temporary debug signing key used for the earlier APK was lost when the build environment reset. Builds now use `de.binarynoise.captiveportalautologin.college`, separate from the original package. This avoids different-signer installation conflicts without uninstalling the original or old debug build. Enter credentials once in the new fork and disable the old app’s automatic service. Later fork builds use the same durable key and increasing version codes. CI installs an unrelated-signer original-package fixture before installing/updating the fork, and validates the shipped arm64 signature, ZIP and 16 KB ELF alignment.

The UI keeps the original settings appearance. Fragment text no longer cross-fades during back navigation; root containers handle system bars, display cutouts, mandatory gesture and keyboard insets without accumulating padding.

## Login behavior and verification limits

- Save the exact portal login URL, username and password under Manual login. Details are encrypted with Android Keystore. Enable saved automatic login while connected to the intended Wi-Fi.
- Automatic attempts follow Android captive-portal callbacks. Failed attempts retry after 1, 2, 4, 8, then at most 15 minutes. These handler retries run while the phone is awake; they do not use a wake lock or periodic background polling.
- When Android temporarily hides the SSID on a background callback, automatic opt-in uses the known SSID retained for that same network.
- The last attempt and retry information remain visible in the main settings and manual-login screen.
- Manual login fetches fresh fields and cookies. It supplies the configured browser user agent, Referer and Origin. It supports ordinary HTML POST forms and same-host HTTP-to-HTTPS upgrades, not arbitrary JavaScript/SSO flows.
- Before manual submission, a short HTTPS connectivity check runs through the selected Wi-Fi, so a portal page without a login form is not treated as an error when internet already works. Failure of this check is not proof that the internet is down; submission and the original portal verification continue.
- The recorder no longer treats an already-validated network as a completed recording before loading a page. It allows the capture extension in its private session and defers runtime initialization until after selecting the network. Automatic login pauses while recording. The login page is loaded only after the extension confirms its capture configuration; a missing handshake produces a visible startup error after 30 seconds.
- Upstream Capture records HAR traffic for diagnosing a portal. It does not generate or replay a login macro. A particular college portal cannot be claimed supported without its URL, form behavior and an actual login test. Never send a password in an issue or commit a HAR containing credentials.

The Linux ZIP contains the original Java CLI. It does not include the Android credential editor or Gecko recorder.

## Installer compatibility investigation

The Vivo T2 5G on Android 15 rejected build 12 even with a separate package name. The full installer error is still needed; signature and ELF validation did not reproduce this phone-specific rejection. Releases now explicitly include v1/v2/v3 signatures using the same durable key. A larger optional `-direct.apk` stores aligned native libraries uncompressed with `extractNativeLibs=false`, so installation does not need to extract Gecko’s compressed libraries. The usual APK remains compressed. CI exercises installation/update, recorder and manual login for both packaging formats on Android 15 and 16. These changes are compatibility probes, not proof that the Vivo issue is fixed.

## IITJ flow investigation (uploaded Internet.py)

The supplied server helper discovers a literal JavaScript redirect from HTTP gstatic,
fetches `https://gateway.iitj.ac.in:1003/fgtauth?<fresh token>`, then submits
`magic`, `4Tredir`, username and password. It disables TLS certificate verification.
The reported current URL is `https://netaccess.iitj.ac.in/24online/servlet/E24onlineHTTPClient`;
its live form has not been inspected and may differ from that older FortiGate flow.

Saved IITJ profiles now perform a fresh probe each attempt, follow bounded HTTP,
literal script and meta refresh redirects, preserve cookies and form fields, and
report the two authentication failures recognized by the supplied script. The
4Tredir POST target is used only if it is one of the two explicitly allowed IITJ
HTTPS endpoints; external return URLs remain hidden form fields. Credentials are
never forwarded by POST redirects. A profile opt-in allows campus certificate
exceptions only for these two endpoints and ports. The recorder exposes network
and certificate load failures in an error page with retry and an explicit temporary
certificate exception button, instead of silently halting to a blank page.

Local tests replicate fresh FortiGate tokens and cookies, redirect chains, account
failure text and origin boundaries. Android emulator tests cover actual browser
JavaScript redirects, HTTP meta refresh login, redirect loops and host rejection.
They do not verify the live IITJ portal or college credentials.

Saved-profile submission now verifies Wi-Fi internet access directly and ends that
attempt. It no longer runs a generic portal solver afterwards, which could obscure
the manual result with an unsupported-portal error. An HTTP success without verified
internet is reported as unverified and retains the existing bounded automatic retry.
