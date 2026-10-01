#!/usr/bin/env python3
"""Validate the exact APK shipped, including compressed arm64 native libraries."""
import argparse, os, re, struct, subprocess, zipfile
from pathlib import Path
p = argparse.ArgumentParser()
p.add_argument('apk', type=Path)
p.add_argument('--tools', type=Path, default=Path(os.environ.get('ANDROID_HOME', '/tmp/android-tools')) / 'build-tools/36.0.0')
p.add_argument('--release', action='store_true')
a = p.parse_args()
def run(tool, *args):
    return subprocess.check_output([str(a.tools / tool), *args, str(a.apk)], text=True)
signing = run('apksigner', 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', '--max-sdk-version', '36')
assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signing
if a.release:
    assert '56bc9ac6d51eaa649763076430d18b6db68facb42a43921f6cdc67679a4ab661' in signing, 'Unexpected release signer'
run('zipalign', '-P', '16', '-c', '4')
badging = run('aapt', 'dump', 'badging')
assert "name='de.binarynoise.captiveportalautologin.college'" in badging
assert "sdkVersion:'26'" in badging
with zipfile.ZipFile(a.apk) as z:
    assert z.testzip() is None, 'Corrupt ZIP entry'
    names = z.namelist()
    assert len(names) == len(set(names)), 'Duplicate ZIP entries'
    libraries = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert libraries and all(n.startswith('lib/arm64-v8a/') for n in libraries), 'Wrong ABI'
    for n in libraries:
        data = z.read(n)
        assert data[:6] == b'\x7fELF\x02\x01' and struct.unpack_from('<H', data, 18)[0] == 183, n
        offset = struct.unpack_from('<Q', data, 32)[0]
        size, count = struct.unpack_from('<HH', data, 54)
        for i in range(count):
            header = offset + i * size
            if struct.unpack_from('<I', data, header)[0] == 1:
                assert struct.unpack_from('<Q', data, header + 48)[0] >= 16384, f'{n}: not 16 KB aligned'
print('APK signatures (Android 8–16), ZIP integrity/alignment, fork identity and arm64 ELF alignment passed.')
print(signing)
