from pathlib import Path
import sys
import zipfile

destination = Path(sys.argv[1])
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as output:
    output.write('linux/build/libs/linux-shadow.jar', 'CaptivePortalAutoLogin.jar')
    output.writestr('README.txt', """CaptivePortalAutoLogin Linux CLI
Requires Java 17 or newer. Automatic mode also requires NetworkManager (nmcli).

Configure IITJ credentials in a real terminal (password entry is hidden):
  java -jar CaptivePortalAutoLogin.jar --configure-iitj
Credentials are saved as plaintext in an owner-only (600) local file:
  ~/.config/captiveportalautologin/iitj.properties
The Linux file is not encrypted; keep your Linux account and backups private.

Manual login, including on a server without NetworkManager:
  java -jar CaptivePortalAutoLogin.jar --iitj --oneshot
Already-online networks are checked before credentials are submitted.

Automatic IITJ mode:
  java -jar CaptivePortalAutoLogin.jar --iitj --service
Setup associates the profile with the sole active NetworkManager connection when
unambiguous. Otherwise list connections with `nmcli connection show` and configure:
  java -jar CaptivePortalAutoLogin.jar --configure-iitj --connection-uuid <UUID>
Service mode only attempts login while that saved UUID is active. Failures retry
at 60, 120, 240, 480, then 900 seconds; no rapid polling. Stop with Ctrl+C.

Alternative profile file: add --profile /absolute/path/iitj.properties to commands.
Certificate exceptions are optional during setup and restricted to the IITJ
netaccess HTTPS endpoint and gateway.iitj.ac.in:1003. With this option the server's
identity cannot be verified. The live college portal remains unverified.

Original generic portal mode:
  java -jar CaptivePortalAutoLogin.jar --service
  java -jar CaptivePortalAutoLogin.jar --help
Source: https://github.com/YashMahawa/CaptivePortalAutoLogin
The Android credential editor and Gecko recorder are not part of this Linux CLI.
""")
    for name in ['LICENSE', 'LICENSE.md', 'LICENSE.txt']:
        if Path(name).is_file():
            output.write(name, name)
