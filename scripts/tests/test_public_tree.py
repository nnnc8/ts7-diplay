import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('public_tree', ROOT / 'scripts/check_public_tree.py')
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


class PublicTreeTest(unittest.TestCase):
    def test_only_exact_upstream_public_ca_is_allowed(self):
        data = (ROOT / check.public_update_ca).read_bytes()
        self.assertFalse(check.forbidden(check.public_update_ca, data))
        self.assertTrue(check.forbidden('assets/accessory.pem', data))
        self.assertTrue(check.forbidden(check.public_update_ca, data + b'changed'))

    def test_private_key_blocks_and_containers_stay_forbidden(self):
        fake_key = b'-----BEGIN ' + b'PRIVATE KEY-----\n' + b'A' * 64
        self.assertTrue(check.forbidden(check.public_update_ca, fake_key))
        self.assertTrue(check.forbidden('source.txt', fake_key))
        for name in ('.private/runtime-assets/identity.txt', 'identity.pk8', 'identity.p7b',
                     'identity.key', 'identity.p12', 'identity.pfx', 'identity.jks',
                     'identity.keystore', 'release.apk', 'release.aab'):
            self.assertTrue(check.forbidden(name, b'fixture'))
        self.assertFalse(check.forbidden('docs/example.txt', b'public text'))
