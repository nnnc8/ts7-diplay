#!/usr/bin/env python3
"""Fail CI if credential containers or private-key blocks enter the source tree."""
from pathlib import Path
import hashlib
import re
import subprocess

root = Path(__file__).resolve().parents[1]
blocked_suffixes = {'.pk8', '.p7b', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.apk', '.aab'}
private_block = re.compile(rb'-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}')
# Exact upstream v0.2.48 public TLS trust store, not an accessory identity.
# Pin both path and content so placing another certificate/key here cannot bypass the check.
public_update_ca = 'common/src/main/res/raw/update_ca_bundle.pem'
public_update_ca_sha256 = '3e68a959493625de324485c7e0314e68688af96b6efdfd80fb7f99e804493459'


def forbidden(name: str, data: bytes) -> bool:
    path = Path(name)
    if '.private' in path.parts or private_block.search(data):
        return True
    if name == public_update_ca and hashlib.sha256(data).hexdigest() == public_update_ca_sha256:
        return False
    return path.suffix.lower() in blocked_suffixes


def main():
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    failures = [name for name in filter(None, names)
                if (root / name).is_file() and forbidden(name, (root / name).read_bytes())]
    if failures:
        raise SystemExit('Credential or distribution files are forbidden in the public tree:\n' + '\n'.join(failures))
    print('Public tree check passed: no credential containers or private-key blocks.')


if __name__ == '__main__':
    main()
