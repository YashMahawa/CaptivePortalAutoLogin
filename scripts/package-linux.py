from pathlib import Path
import sys
import zipfile

destination = Path(sys.argv[1])
destination.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as output:
    output.write('linux/build/libs/linux-shadow.jar', 'CaptivePortalAutoLogin.jar')
    output.writestr('README.txt', 'CaptivePortalAutoLogin Linux CLI\nRequires Java 17 or newer.\nRun: java -jar CaptivePortalAutoLogin.jar --help\nContinuous mode: java -jar CaptivePortalAutoLogin.jar --service\nSource: https://github.com/YashMahawa/CaptivePortalAutoLogin\nThe Android credential editor and Gecko recorder are not part of this Linux CLI.\n')
    for name in ['LICENSE', 'LICENSE.md', 'LICENSE.txt']:
        if Path(name).is_file():
            output.write(name, name)
