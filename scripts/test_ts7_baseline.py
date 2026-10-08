#!/usr/bin/env python3
"""Host-only checker regressions. These tests do not establish emulator evidence."""
import base64
from pathlib import Path
import struct
import sys
import unittest
from unittest.mock import Mock, patch

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
        self.assertEqual(fixture.count(b"\x00\x00\x00\x01\x67"), 12)
        self.assertEqual(fixture.count(b"\x00\x00\x00\x01\x68"), 12)
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

    def test_media_failure_detail_is_numeric_or_allowlisted(self):
        raw = b"INSTRUMENTATION_RESULT: ts7.media_phase=FIRST_SURFACE\n" + \
            b"INSTRUMENTATION_RESULT: ts7.media_frames=12\n" + \
            b"INSTRUMENTATION_RESULT: ts7.media_red_frames=0\n" + \
            b"INSTRUMENTATION_RESULT: ts7.media_decoder_output=YES\n" + \
            b"INSTRUMENTATION_RESULT: ts7.media_decoder_errors=1234\n" + self.output(failed="media_surface_lifecycle")
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(raw, baseline.UPSTREAM)
        self.assertEqual(result.exception.details["media_probe"], {
            "phase": "FIRST_SURFACE", "frames": 12, "red_frames": 0,
            "decoder_output": "YES", "decoder_errors": None, "output_formats": None,
            "backlog_recoveries": None, "invalid_units": None, "stalled_recoveries": None,
            "worker_alive": "NOT_REPORTED", "input_attempted": "NOT_REPORTED",
            "input_queued": "NOT_REPORTED", "queued_jobs": None,
            "worker_state": "NOT_REPORTED", "worker_phase": "NOT_REPORTED",
            "codec_kind": "NOT_REPORTED",
            "control_status": "NOT_REPORTED", "control_input_queued": None,
            "control_output_released": None, "control_frames": None, "control_red_frames": None,
            "buffer_control_status": "NOT_REPORTED", "buffer_input_queued": None, "buffer_output_released": None,
        })

    def test_worker_probe_filters_unknown_stack_details(self):
        rows = (b"INSTRUMENTATION_RESULT: ts7.media_input_queued=YES\n"
                b"INSTRUMENTATION_RESULT: ts7.media_queued_jobs=11\n"
                b"INSTRUMENTATION_RESULT: ts7.media_worker_state=TIMED_WAITING\n"
                b"INSTRUMENTATION_RESULT: ts7.media_worker_phase=WAITING_FOR_VIDEO_JOB\n")
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(rows + self.output(failed="media_surface_lifecycle"), baseline.UPSTREAM)
        probe = result.exception.details["media_probe"]
        self.assertEqual(probe["input_queued"], "YES")
        self.assertEqual(probe["queued_jobs"], 11)
        self.assertEqual(probe["worker_state"], "TIMED_WAITING")
        self.assertEqual(probe["worker_phase"], "WAITING_FOR_VIDEO_JOB")
        rows = rows.replace(b"WAITING_FOR_VIDEO_JOB", b"PRIVATE_STACK_DETAIL").replace(b"TIMED_WAITING", b"PRIVATE_THREAD_DETAIL")
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(rows + self.output(failed="media_surface_lifecycle"), baseline.UPSTREAM)
        self.assertEqual(result.exception.details["media_probe"]["worker_phase"], "NOT_REPORTED")
        self.assertEqual(result.exception.details["media_probe"]["worker_state"], "NOT_REPORTED")
        self.assertNotIn("PRIVATE_", str(result.exception.details))

    def test_platform_control_cannot_forgive_original_gate(self):
        rows = (b"INSTRUMENTATION_RESULT: ts7.media_control_status=PASS\n"
                b"INSTRUMENTATION_RESULT: ts7.media_control_input_queued=12\n"
                b"INSTRUMENTATION_RESULT: ts7.media_control_output_released=12\n"
                b"INSTRUMENTATION_RESULT: ts7.media_control_frames=12\n"
                b"INSTRUMENTATION_RESULT: ts7.media_control_red_frames=12\n")
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(rows + self.output(failed="media_surface_lifecycle"), baseline.UPSTREAM)
        self.assertEqual(result.exception.details["checks"]["media_surface_lifecycle"], "FAIL")
        self.assertEqual(result.exception.details["media_probe"]["control_status"], "PASS")
        self.assertEqual(result.exception.details["media_probe"]["control_input_queued"], 12)
        rows = rows.replace(b"control_status=PASS", b"control_status=PRIVATE_ERROR").replace(b"control_frames=12", b"control_frames=1234")
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(rows + self.output(failed="media_surface_lifecycle"), baseline.UPSTREAM)
        self.assertEqual(result.exception.details["media_probe"]["control_status"], "NOT_REPORTED")
        self.assertIsNone(result.exception.details["media_probe"]["control_frames"])
        self.assertNotIn("PRIVATE_ERROR", str(result.exception.details))

    def test_bytebuffer_control_is_not_surface_or_phone_proof(self):
        rows = (b"INSTRUMENTATION_RESULT: ts7.media_buffer_control_status=BYTEBUFFER_OUTPUT_PASS\n"
                b"INSTRUMENTATION_RESULT: ts7.media_buffer_input_queued=12\n"
                b"INSTRUMENTATION_RESULT: ts7.media_buffer_output_released=12\n")
        with self.assertRaises(baseline.InstrumentationFailure) as result:
            baseline.parse_instrumentation(rows + self.output(failed="media_surface_lifecycle"), baseline.UPSTREAM)
        self.assertEqual(result.exception.details["checks"]["media_surface_lifecycle"], "FAIL")
        self.assertEqual(result.exception.details["media_probe"]["buffer_control_status"], "BYTEBUFFER_OUTPUT_PASS")
        self.assertEqual(result.exception.details["media_probe"]["buffer_output_released"], 12)
        self.assertFalse(result.exception.details["phone_session_proven"])

    def test_native_codec_fault_counts_never_return_log_contents(self):
        raw = (b"E/ACodec  ( 123): signalError(omxError 0xdead, internalError -1) PRIVATE_DETAIL\n"
               b"W/SoftwareRenderer( 124): Surface::dequeueBuffer returned error -12 PRIVATE_DETAIL\n"
               b"E/SoftAVC ( 125): Allocation failure in decoder PRIVATE_DETAIL\n"
               b"E/UNTRUSTED( 126): signalError(omxError PRIVATE_DETAIL\n"
               b"I/ACodec( 127): signalError(omxError PRIVATE_DETAIL\n")
        counts = baseline.native_codec_fault_counts(raw)
        self.assertEqual(counts["codec_signal_error"], 1)
        self.assertEqual(counts["native_window_failure"], 1)
        self.assertEqual(counts["decoder_allocation_failure"], 1)
        self.assertEqual(sum(counts.values()), 3)
        self.assertTrue(all(isinstance(value, int) for value in counts.values()))
        self.assertNotIn("PRIVATE_DETAIL", str(counts))

    def test_cleanup_fault_does_not_hide_original_instrumentation_failure(self):
        path = Mock()
        path.is_file.return_value = True
        def device_response(args, **kwargs):
            if "force-stop" in args:
                raise baseline.CheckFailure("COMMAND_FAILED")
            if "instrument" in args:
                return self.output(failed="media_surface_lifecycle")
            if "logcat" in args:
                return b"E/ACodec( 123): signalError(omxError 0xdead, internalError -1) PRIVATE_DETAIL\n"
            if args[-1] == "ro.build.version.sdk": return b"27"
            if args[-1] == "ro.product.cpu.abi": return b"x86_64"
            if args[-1] == "ro.kernel.qemu": return b"1"
            return b"Success"
        with patch.object(baseline.shutil, "which", return_value="adb"), patch.object(baseline, "command", side_effect=device_response):
            with self.assertRaises(baseline.InstrumentationFailure) as result:
                baseline.emulator(None, path, path, "emulator-5554", baseline.UPSTREAM)
        self.assertEqual(result.exception.details["failure_codes"]["media_surface_lifecycle"], "SURFACE_CALLBACK_FAILED")
        self.assertEqual(result.exception.details["native_codec_fault_counts"]["codec_signal_error"], 1)
        self.assertEqual(result.exception.details["emulator_cleanup"], "FORCE_STOP_FAILED")
        self.assertNotIn("PRIVATE_DETAIL", str(result.exception.details))

    def test_private_header_literal_is_not_a_credential(self):
        literal = b'rb"-----BEGIN PRIVATE KEY-----\\s+[A-Za-z0-9+/=]{40,}"'
        self.assertIsNone(baseline.PRIVATE_KEY.search(literal))
        self.assertIsNotNone(baseline.PRIVATE_KEY.search(b"-----BEGIN PRIVATE KEY-----\n" + b"A" * 48))


if __name__ == "__main__":
    unittest.main()
