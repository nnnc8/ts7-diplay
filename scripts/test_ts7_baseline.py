#!/usr/bin/env python3
"""Host-only checker regressions. These tests do not establish emulator evidence."""
import base64
from pathlib import Path
import struct
import sys
import unittest

sys.dont_write_bytecode = True
import check_ts7_baseline as baseline


class BaselineChecksTest(unittest.TestCase):
    def test_reviewed_logging_patch_is_exact(self):
        repo = Path(__file__).resolve().parents[1]
        name = "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt"
        expected = baseline.reviewed_media_source(baseline.original(repo, name))
        self.assertEqual((repo / name).read_bytes(), expected)
        with self.assertRaises(baseline.CheckFailure):
            baseline.reviewed_media_source(expected)
        changed_decoder = expected.replace(b"MAX_INPUT_SIZE = 8 * 1024 * 1024", b"MAX_INPUT_SIZE = 1")
        self.assertNotEqual(changed_decoder, expected)

    def test_fixture_digest_and_annex_b_counts(self):
        repo = Path(__file__).resolve().parents[1]
        fixture = base64.b64decode((repo / baseline.FIXTURE).read_bytes())
        self.assertEqual(len(fixture), 1320)
        self.assertEqual(baseline.sha(fixture), baseline.FIXTURE_SHA)
        self.assertEqual(fixture.count(b"\x00\x00\x00\x01\x09"), 12)
        self.assertEqual(fixture.count(b"\x00\x00\x01\x65"), 12)

    def test_elf_rejects_a_renamed_x86_library(self):
        header = bytearray(24)
        header[:6] = b"\x7fELF\x01\x01"
        struct.pack_into("<HH", header, 16, 3, 40)
        baseline.elf(header, 1, 40)
        struct.pack_into("<H", header, 18, 3)
        with self.assertRaises(baseline.CheckFailure):
            baseline.elf(header, 1, 40)

    def test_debug_signer_field_order_and_strict_identity(self):
        prefix = b"Number of signers: 1\nSigner #1 certificate DN: "
        suffix = b"\nSigner #1 certificate SHA-256 digest: " + b"a" * 64 + b"\n"
        for fields in (b"CN=Android Debug, O=Android, C=US", b"C=US, O=Android, CN=Android Debug"):
            self.assertEqual(baseline.debug_signer_digest(prefix + fields + suffix), "a" * 64)
        for fields in (b"CN=Production, O=Android, C=US", b"CN=Android Debug, O=Android",
                       b"CN=Android Debug, O=Android, C=US, OU=Unverified"):
            with self.assertRaises(baseline.CheckFailure):
                baseline.debug_signer_digest(prefix + fields + suffix)
        with self.assertRaises(baseline.CheckFailure):
            baseline.debug_signer_digest((prefix + b"CN=Android Debug, O=Android, C=US" + suffix)
                                        .replace(b"Number of signers: 1", b"Number of signers: 2"))

    def manifest(self, permission="android.permission.INTERNET", enabled="false", extra=""):
        return f'''E: manifest (line=1)
  A: package="com.shihab.diplay.ts7"
  {extra}
  E: uses-permission (line=2)
    A: http://schemas.android.com/apk/res/android:name(0x01010003)="{permission}" (Raw: "{permission}")
  E: application (line=3)
    E: receiver (line=4)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="com.shilapi.xcertplay.BootReceiver"
      A: http://schemas.android.com/apk/res/android:enabled(0x0101000e)={enabled}
'''.encode()

    def test_permission_and_boot_gates(self):
        self.assertEqual(baseline.inspect_permissions(self.manifest()), ["android.permission.INTERNET"])
        for permission in ("android.permission.WRITE_SECURE_SETTINGS", "android.permission.PACKAGE_USAGE_STATS",
                           "android.permission.ACCESS_SUPERUSER", "com.vendor.permission.SYSTEM"):
            with self.assertRaises(baseline.CheckFailure):
                baseline.inspect_permissions(self.manifest(permission=permission))
        with self.assertRaises(baseline.CheckFailure):
            baseline.inspect_permissions(self.manifest(enabled="true"))
        with self.assertRaises(baseline.CheckFailure):
            baseline.inspect_permissions(self.manifest(extra='A: http://schemas.android.com/apk/res/android:sharedUserId="android.uid.system"'))

    def output(self, failed=None, omitted=None):
        rows = [f"INSTRUMENTATION_RESULT: ts7.source_sha={baseline.UPSTREAM}",
                "INSTRUMENTATION_RESULT: ts7.api=27",
                "INSTRUMENTATION_RESULT: ts7.suite=" + ("FAIL" if failed else "EMULATOR_PASS")]
        for check in baseline.CHECKS:
            if check != omitted:
                rows.append(f"INSTRUMENTATION_RESULT: ts7.{check}=" + ("FAIL" if check == failed else "PASS"))
        if failed:
            rows.append(f"INSTRUMENTATION_RESULT: ts7.failure_{failed}=SURFACE_CALLBACK_FAILED")
        rows.append("INSTRUMENTATION_CODE: -1")
        return "\n".join(rows).encode()

    def test_every_instrumentation_check_is_required(self):
        self.assertEqual(len(baseline.parse_instrumentation(self.output(), baseline.UPSTREAM)), 8)
        for name in baseline.CHECKS:
            with self.assertRaises(baseline.InstrumentationFailure) as result:
                baseline.parse_instrumentation(self.output(omitted=name), baseline.UPSTREAM)
            self.assertEqual(result.exception.details["checks"][name], "NOT_REPORTED")

    def test_failure_results_are_fixed_and_useful(self):
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(self.output(failed="media_surface_lifecycle"), baseline.UPSTREAM)
        self.assertEqual(result.exception.details["failure_codes"]["media_surface_lifecycle"], "SURFACE_CALLBACK_FAILED")
        raw = self.output(failed="media_surface_lifecycle").replace(b"SURFACE_CALLBACK_FAILED", b"UNTRUSTED_PRIVATE_VALUE")
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(raw, baseline.UPSTREAM)
        self.assertNotIn("UNTRUSTED_PRIVATE_VALUE", str(result.exception.details))
        self.assertEqual(result.exception.details["checks"]["classic_home"], "PASS")

    def test_a_crash_or_wrong_sha_cannot_pass(self):
        for raw in (self.output().replace(b"INSTRUMENTATION_CODE: -1", b"INSTRUMENTATION_CODE: 0"),
                    self.output().replace(baseline.UPSTREAM.encode(), b"0" * 40)):
            with self.assertRaises(baseline.InstrumentationFailure):
                baseline.parse_instrumentation(raw, baseline.UPSTREAM)

    def test_private_header_literal_is_not_a_credential(self):
        literal = b'rb"-----BEGIN PRIVATE KEY-----\\s+[A-Za-z0-9+/=]{40,}"'
        self.assertIsNone(baseline.PRIVATE_KEY.search(literal))
        self.assertIsNotNone(baseline.PRIVATE_KEY.search(b"-----BEGIN PRIVATE KEY-----\n" + b"A" * 48))


if __name__ == "__main__":
    unittest.main()
