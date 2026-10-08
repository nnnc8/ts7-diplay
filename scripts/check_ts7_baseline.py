#!/usr/bin/env python3
"""Bounded TS7 R1 checks. Only fixed results, counts and digests leave this process.

No captured Gradle/adb output, exception messages, logcat or original debug APK is
published. API27 evidence is accepted only from the native instrumentation runner
on an actual x86_64 emulator. A host build can never supply EMULATOR_PASS.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile

UPSTREAM = "c8884adcc75bfda3c134db63877bd6c6f83beb74"
APPLICATION = "com.shihab.diplay.ts7"
RUNNER = "com.shilapi.xcertplay.baseline.BaselineInstrumentation"
FIXTURE = "mobile/src/androidTest/assets/ts7-baseline/red.h264.base64"
FIXTURE_SHA = "62a054a4e4f667f7c95a0dd774f7a81caa0e8f1145c4b964e017cf80f007cc54"
CHECKS = (
    "api27_environment", "classic_home", "classic_settings_connection",
    "choose_phone_dialog", "auth_blocked_no_session", "activity_stop_restart",
    "native_library_load", "media_surface_lifecycle",
)
CONTROL_STATUSES = {
    "PASS", "BYTEBUFFER_OUTPUT_PASS", "API27_REQUIRED", "FIXTURE_INVALID",
    "SURFACE_INIT_FAILED", "CODEC_CREATE_FAILED", "CODEC_CONFIGURE_FAILED", "CODEC_START_FAILED",
    "OUTPUT_FAILED", "OUTPUT_BUFFER_INVALID", "INPUT_FAILED", "INPUT_TIMEOUT", "INPUT_BUFFER_INVALID",
    "OUTPUT_INSUFFICIENT", "FRAMES_INSUFFICIENT", "RED_FRAMES_INSUFFICIENT", "TIMESTAMP_MISSING",
    "SURFACE_UNHEALTHY", "CLEANUP_FAILED", "INTERRUPTED",
}
LIBRARIES = ("libxcertplay_i2c.so", "liblocal_hotspot_radio.so")
CREDENTIAL_SUFFIXES = {
    ".pk8", ".p7b", ".p7c", ".p8", ".pem", ".key", ".p12", ".pfx",
    ".jks", ".keystore", ".cer", ".crt", ".der",
}
PRIVATE_KEY = re.compile(rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}")
SAFE_PERMISSIONS = {"android.permission." + name for name in (
    "INTERNET", "ACCESS_NETWORK_STATE", "RECORD_AUDIO", "CHANGE_WIFI_STATE", "ACCESS_WIFI_STATE",
    "NEARBY_WIFI_DEVICES", "CHANGE_WIFI_MULTICAST_STATE", "ACCESS_COARSE_LOCATION", "ACCESS_FINE_LOCATION",
    "BLUETOOTH_CONNECT", "BLUETOOTH", "BLUETOOTH_ADMIN", "RECEIVE_BOOT_COMPLETED", "FOREGROUND_SERVICE",
    "FOREGROUND_SERVICE_CONNECTED_DEVICE", "FOREGROUND_SERVICE_MICROPHONE", "POST_NOTIFICATIONS",
)} | {APPLICATION + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"}

# Parent-reviewed privacy patch only. Count each old span in the pinned source,
# apply it once, then compare the ENTIRE resulting file byte for byte. This is
# intentionally not a pattern allowing arbitrary logging or decoder changes.
MEDIA_SANITIZATIONS = (
    (b"import android.util.Log\n", b"import com.shilapi.xcertplay.PublicLog as Log\n"),
    (b"import com.shilapi.xcertplay.airplay.toHexString\n", b""),
    (b'"audio AAC config rate=${format.sampleRate} channels=${format.channels} " +\n'
     b'                    "csd0=${aacAudioSpecificConfig().toHexString()}"',
     b'"audio AAC config rate=${format.sampleRate} channels=${format.channels}"'),
    (b'"audio AAC access unit bytes=${accessUnit.size} " +\n'
     b'                                "head=${accessUnit.copyOf(minOf(accessUnit.size, 16)).toHexString()}"',
     b'"audio AAC access unit bytes=${accessUnit.size}"'),
    (b'"audio Opus skipping short packet bytes=${accessUnit.size} " +\n'
     b'                                "head=${accessUnit.toHexString()}"',
     b'"audio Opus skipping short packet bytes=${accessUnit.size}"'),
    (b'"audio decoder first input codec=${format.codec} bytes=${payload.size} " +\n'
     b'                        "head=${payload.copyOf(minOf(payload.size, 16)).toHexString()}"',
     b'"audio decoder first input codec=${format.codec} bytes=${payload.size}"'),
    (b'            val end = minOf(data.size, offset + minOf(length, 16))\n', b""),
    (b'"audio first PCM type=${format.payloadType} bytes=$length " +\n'
     b'                    "head=${data.copyOfRange(offset, end).toHexString()}"',
     b'"audio first PCM type=${format.payloadType} bytes=$length"'),
)


class CheckFailure(Exception):
    """The message is an allowlisted, non-identifying failure code."""


class BuildFailure(CheckFailure):
    def __init__(self, code, details):
        super().__init__(code)
        self.details = details


class InstrumentationFailure(CheckFailure):
    def __init__(self, code, details):
        super().__init__(code)
        self.details = details


def require(condition, code):
    if not condition:
        raise CheckFailure(code)


def command(args, cwd=None, timeout=60):
    try:
        result = subprocess.run(args, cwd=cwd, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout, check=False)
    except subprocess.TimeoutExpired:
        raise CheckFailure("COMMAND_TIMEOUT") from None
    except OSError:
        raise CheckFailure("COMMAND_UNAVAILABLE") from None
    require(result.returncode == 0, "COMMAND_FAILED")
    return result.stdout


def git(repo, *args):
    return command(["git", "-C", str(repo), *args])


def sha(data):
    return hashlib.sha256(data).hexdigest()


def clean(repo):
    return not git(repo, "status", "--porcelain", "--untracked-files=all").strip()


def pinned_files(repo):
    return [name for name in git(repo, "ls-tree", "-r", "--name-only", "-z", UPSTREAM)
            .decode().split("\0") if name]


def original(repo, name):
    return git(repo, "show", f"{UPSTREAM}:{name}")


def pristine(repo):
    require(git(repo, "rev-parse", "HEAD").decode().strip() == UPSTREAM, "UPSTREAM_SHA_MISMATCH")
    require(clean(repo), "UPSTREAM_NOT_PRISTINE")
    require(len(pinned_files(repo)) == 428, "UPSTREAM_FILE_COUNT_MISMATCH")
    return {"status": "PRISTINE_UPSTREAM_PASS", "retained_files": 428}


def reviewed_media_source(upstream):
    for before, after in MEDIA_SANITIZATIONS:
        require(upstream.count(before) == 1, "REVIEWED_MEDIA_PATCH_COUNT_MISMATCH")
        upstream = upstream.replace(before, after, 1)
    return upstream


def source_digest(repo):
    names = git(repo, "ls-files", "--cached", "--others", "--exclude-standard", "-z").decode().split("\0")
    digest = hashlib.sha256()
    for name in sorted(filter(None, names)):
        digest.update(name.encode() + b"\0")
        digest.update((repo / name).read_bytes())
    return digest.hexdigest()


def source(repo, require_clean=False):
    git(repo, "merge-base", "--is-ancestor", UPSTREAM, "HEAD")
    names = pinned_files(repo)
    require(len(names) == 428, "UPSTREAM_FILE_COUNT_MISMATCH")
    require(all((repo / name).is_file() for name in names), "UPSTREAM_SOURCE_REMOVED")
    ancestors = git(repo, "rev-list", UPSTREAM).decode().splitlines()
    require(len(ancestors) == 2, "UPSTREAM_HISTORY_MISMATCH")
    for module in ("mobile", "common", "shared", "automotive", "site"):
        require((repo / module).is_dir(), "UPSTREAM_MODULE_REMOVED")
    notices = [name for name in names if re.search(r"license|notice|copying|credits", name, re.I)]
    immutable = notices + [".github/workflows/android.yml", ".github/workflows/pages.yml",
                           "gradle/wrapper/gradle-wrapper.properties", "gradlew", "gradlew.bat",
                           "gradle/wrapper/gradle-wrapper.jar"]
    for name in immutable:
        require((repo / name).read_bytes() == original(repo, name), "UPSTREAM_NOTICE_OR_WRAPPER_CHANGED")
    # Require exactly the reviewed logging-only patch, including the audio tail.
    media_name = "shared/src/main/java/com/shilapi/xcertplay/media/AndroidMediaSink.kt"
    require((repo / media_name).read_bytes() == reviewed_media_source(original(repo, media_name)),
            "UPSTREAM_MEDIA_SINK_CHANGED")
    public_names = git(repo, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
    for name in filter(None, public_names.decode().split("\0")):
        path = repo / name
        require(path.is_file(), "PUBLIC_SOURCE_MISSING")
        require(path.suffix.lower() not in CREDENTIAL_SUFFIXES | {".apk", ".aab"}, "SOURCE_CREDENTIAL_CONTAINER")
        require("offline-mfi" not in path.parts and ".private" not in path.parts, "SOURCE_CREDENTIAL_DIRECTORY")
        require(not PRIVATE_KEY.search(path.read_bytes()), "SOURCE_PRIVATE_KEY_BLOCK")
    fixture = base64.b64decode((repo / FIXTURE).read_bytes(), validate=False)
    require(sha(fixture) == FIXTURE_SHA and len(fixture) == 1320, "SYNTHETIC_FIXTURE_CHANGED")
    if require_clean:
        require(clean(repo), "SOURCE_WORKTREE_DIRTY")
    return {"status": "SOURCE_INSPECTION_PASS", "retained_files": len(names),
            "retained_upstream_commits": len(ancestors), "unchanged_notices": len(notices),
            "upstream_ancestor": True, "unchanged_media_sink_except_reviewed_logging": True,
            "source_tree_sha256": source_digest(repo), "fixture_sha256": FIXTURE_SHA,
            "working_tree_clean": clean(repo)}


def sdk_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    require(bool(sdk), "ANDROID_SDK_UNAVAILABLE")
    tool = Path(sdk) / "build-tools" / "36.0.0" / name
    require(tool.is_file(), "BUILD_TOOLS_36_UNAVAILABLE")
    return str(tool)


def toolchain(repo):
    require("DIPLAY_AUTH_ASSETS_DIR" not in os.environ, "EXTERNAL_AUTH_INPUT_FORBIDDEN")
    version = command(["java", "-version"]).decode(errors="replace")
    require(re.search(r'version "25\.', version), "JDK25_REQUIRED")
    wrapper = (repo / "gradle/wrapper/gradle-wrapper.properties").read_text()
    require("gradle-9.5.0-bin.zip" in wrapper and
            "553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746" in wrapper,
            "GRADLE95_WRAPPER_REQUIRED")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or "")
    require((sdk / "platforms/android-37.0/android.jar").is_file(), "SDK37_REQUIRED")
    ndk = sdk / "ndk/25.2.9519653/source.properties"
    require(ndk.is_file() and "25.2.9519653" in ndk.read_text(), "NDK25_REQUIRED")
    sdk_tool("aapt2")
    return {"jdk": 25, "gradle": "9.5.0", "sdk": "37.0", "build_tools": "36.0.0", "ndk": "25.2.9519653"}


def unit_report_counts(repo, require_reports=True):
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for module in ("shared", "common"):
        files = sorted((repo / module / "build/test-results/testDebugUnitTest").glob("TEST-*.xml"))
        if require_reports:
            require(bool(files), "UNIT_REPORT_MISSING")
        for path in files:
            suite = ET.parse(path).getroot()
            for key in totals:
                totals[key] += int(suite.attrib.get(key, "0"))
    return totals


def lint_counts(repo, suffix):
    report = repo / f"mobile/build/reports/lint-results-{suffix.lower()}.xml"
    if report.is_file():
        issues = ET.parse(report).getroot().findall("issue")
        return {"errors": sum(issue.attrib.get("severity", "").lower() in ("error", "fatal") for issue in issues),
                "warnings": sum(issue.attrib.get("severity", "").lower() not in ("error", "fatal") for issue in issues),
                "format": "xml"}
    sarif = report.with_suffix(".sarif")
    if sarif.is_file():
        runs = json.loads(sarif.read_text()).get("runs", [])
        results = [entry for run in runs for entry in run.get("results", [])]
        return {"errors": sum(entry.get("level", "warning") == "error" for entry in results),
                "warnings": sum(entry.get("level", "warning") != "error" for entry in results), "format": "sarif"}
    raise CheckFailure("LINT_REPORT_MISSING")


def run_build(repo, tasks, suffix):
    try:
        result = subprocess.run(["bash", "gradlew", *tasks, "--no-daemon", "--max-workers=1",
                                 "--no-configuration-cache", "--console=plain"], cwd=repo,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=1500, check=False)
    except subprocess.TimeoutExpired:
        raise CheckFailure("GRADLE_TIMEOUT") from None
    except OSError:
        raise CheckFailure("GRADLE_UNAVAILABLE") from None
    if result.returncode != 0:
        text = result.stdout.decode(errors="replace")
        failed = re.findall(r"^> Task (:(?:mobile|common|shared):[A-Za-z0-9]+) FAILED\s*$", text, re.M)
        if "AsyncExecutionService.getService" in text:
            code = "LINT_INTERNAL_UAST_ERROR"
        elif any(":lint" in task for task in failed):
            code = "GRADLE_LINT_FAILED"
        elif any("UnitTest" in task for task in failed):
            code = "GRADLE_TEST_FAILED"
        else:
            code = "GRADLE_BUILD_FAILED"
        details = {"failed_tasks": sorted(set(failed)), "observed_reports_may_be_stale": True}
        try:
            details["observed_unit_tests"] = unit_report_counts(repo, require_reports=False)
            details["observed_lint"] = lint_counts(repo, suffix)
        except Exception:
            pass
        raise BuildFailure(code, details)


def build(repo, variant):
    versions = toolchain(repo)
    if variant == "upstream":
        pristine(repo)
    tasks = [":shared:testDebugUnitTest", ":common:testDebugUnitTest"]
    suffix = "Debug" if variant == "upstream" else "Baseline"
    tasks += [f":mobile:lint{suffix}", f":mobile:assemble{suffix}"]
    if variant != "upstream":
        tasks.append(":mobile:assembleBaselineAndroidTest")
    before = source_digest(repo)
    run_build(repo, tasks, suffix)
    require(source_digest(repo) == before, "SOURCE_CHANGED_DURING_BUILD")
    counts = unit_report_counts(repo)
    require(counts["tests"] >= 326 and all(counts[key] == 0 for key in ("failures", "errors", "skipped")),
            "UNIT_TESTS_NOT_ALL_PASS")
    if variant == "upstream":
        require(counts["tests"] == 326, "PRISTINE_TEST_COUNT_CHANGED")
        pristine(repo)
    lint = lint_counts(repo, suffix)
    require(lint["errors"] == 0, "LINT_FAILED")
    return {"status": "UPSTREAM_BUILD_PASS" if variant == "upstream" else "HOST_TEST_PASS",
            "toolchain": versions, "tasks": tasks, "unit_tests": counts, "lint": lint,
            "source_tree_sha256": before, "original_debug_apk_published": False}


def elf(data, elf_class, machine):
    require(len(data) >= 20 and data[:4] == b"\x7fELF", "NATIVE_NOT_ELF")
    require(data[4] == elf_class and data[5] == 1, "NATIVE_ELF_CLASS_MISMATCH")
    require(struct.unpack_from("<H", data, 16)[0] == 3, "NATIVE_NOT_SHARED_OBJECT")
    require(struct.unpack_from("<H", data, 18)[0] == machine, "NATIVE_ELF_MACHINE_MISMATCH")


def manifest_elements(dump):
    elements = []
    current = None
    for line in dump.decode(errors="replace").splitlines():
        element = re.search(r"\bE: ([A-Za-z0-9_-]+)\b", line)
        if element:
            current = {"tag": element[1], "attributes": {}}
            elements.append(current)
        attr = re.search(r"\bA: (?:http://schemas.android.com/apk/res/android:)?([A-Za-z0-9_.]+)(?:\([^)]*\))?=(.*)", line)
        if attr and current is not None:
            raw = attr[2].strip()
            quoted = re.match(r'"([^"]*)"', raw)
            current["attributes"][attr[1]] = quoted[1] if quoted else raw
    return elements


def inspect_permissions(manifest):
    elements = manifest_elements(manifest)
    requested = {entry["attributes"].get("name", "") for entry in elements
                 if entry["tag"] in ("uses-permission", "uses-permission-sdk-23")}
    require(bool(requested) and requested <= SAFE_PERMISSIONS, "APK_UNEXPECTED_PERMISSION")
    root = next((entry for entry in elements if entry["tag"] == "manifest"), None)
    require(root is not None and "sharedUserId" not in root["attributes"], "APK_SHARED_SYSTEM_UID")
    boot = [entry for entry in elements if entry["tag"] == "receiver" and
            entry["attributes"].get("name") == "com.shilapi.xcertplay.BootReceiver"]
    require(len(boot) == 1 and boot[0]["attributes"].get("enabled") == "false", "BOOT_RECEIVER_NOT_DISABLED")
    return sorted(requested)


def debug_signer_digest(signature):
    # JDK/apksigner versions can print the same X.500 fields in reverse order.
    names = re.findall(rb"^Signer #1 certificate DN: (.+)$", signature, re.M)
    require(len(names) == 1 and sorted(names[0].strip().split(b", ")) ==
            [b"C=US", b"CN=Android Debug", b"O=Android"], "TEST_DEBUG_SIGNER_REQUIRED")
    require(re.search(rb"^Number of signers: 1\s*$", signature, re.M), "SINGLE_DEBUG_SIGNER_REQUIRED")
    digest = re.findall(rb"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", signature, re.M)
    require(len(digest) == 1, "SIGNER_DIGEST_MISSING")
    return digest[0].decode().lower()


def inspect_apk(repo, path, test_apk=None):
    require(path.is_file(), "BASELINE_APK_MISSING")
    badging = command([sdk_tool("aapt2"), "dump", "badging", str(path)]).decode(errors="replace")
    require(f"package: name='{APPLICATION}'" in badging, "BASELINE_APPLICATION_ID_MISMATCH")
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", badging)
    require(sdk and int(sdk[1]) <= 27, "MIN_SDK_ABOVE_27")
    require("targetSdkVersion:'37'" in badging, "TARGET_SDK37_REQUIRED")
    require("launchable-activity: name='com.shilapi.xcertplay.DiPlayActivity'" in badging,
            "CLASSIC_LAUNCHER_MISSING")
    manifest = command([sdk_tool("aapt2"), "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"])
    permissions = inspect_permissions(manifest)
    for marker in (b"StandaloneHudDemoActivity", b"StarterBridgeReceiver", b"StandaloneHudPackets"):
        require(marker not in manifest, "DEBUG_HUD_COMPONENT_PRESENT")
    restricted = [name for name in pinned_files(repo) if name == "common/src/main/res/drawable/ic_carplay.png"
                  or (name.startswith("shared/src/main/assets/byd-hud-icons/") and name.endswith(".png"))]
    restricted_digests = {sha(original(repo, name)) for name in restricted}
    native = {}
    with zipfile.ZipFile(path) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())), "APK_DUPLICATE_ENTRY")
        require("res/drawable/ic_carplay.png" not in archive.namelist(), "APK_ORIGINAL_ICON_RESOURCE")
        require(any(name.startswith("res/drawable") and name.endswith("/ic_carplay.xml")
                    for name in archive.namelist()), "NEUTRAL_ICON_XML_MISSING")
        for name in archive.namelist():
            lower = name.lower()
            require("offline-mfi" not in lower and Path(lower).suffix not in CREDENTIAL_SUFFIXES,
                    "APK_CREDENTIAL_CONTAINER")
            # AAPT can rasterize the neutral vector for pre21 under ic_carplay.png.
            # Reject upstream Apple/BYD bytes below, independently of entry names.
            require("byd-hud-icons" not in lower, "APK_BYD_ASSET_DIRECTORY")
            require("ts7-baseline" not in lower and not lower.endswith((".h264", ".base64")),
                    "TEST_FIXTURE_IN_INSTALL_APK")
            data = archive.read(name)
            require(sha(data) not in restricted_digests, "APK_RESTRICTED_IMAGE_BYTES")
            require(not PRIVATE_KEY.search(data), "APK_PRIVATE_KEY_BLOCK")
            if lower.endswith(".dex"):
                require(b"Lcom/shilapi/xcertplay/baseline/" not in data, "TEST_CLASSES_IN_INSTALL_APK")
                require(not any(marker in data for marker in (b"StandaloneHudDemoActivity", b"StarterBridgeReceiver")),
                        "DEBUG_HUD_CLASS_PRESENT")
        for abi, elf_class, machine in (("armeabi-v7a", 1, 40), ("x86_64", 2, 62)):
            for library in LIBRARIES:
                entry = f"lib/{abi}/{library}"
                require(entry in archive.namelist(), "NATIVE_LIBRARY_MISSING")
                data = archive.read(entry)
                elf(data, elf_class, machine)
                native[entry] = {"elf_class": elf_class, "e_machine": machine, "sha256": sha(data)}
    signature = command([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", str(path)])
    signer_digest = debug_signer_digest(signature)
    if test_apk:
        require(test_apk.is_file(), "TEST_APK_MISSING")
        test_manifest = command([sdk_tool("aapt2"), "dump", "xmltree", str(test_apk), "--file", "AndroidManifest.xml"])
        require(RUNNER.encode() in test_manifest and APPLICATION.encode() in test_manifest, "TEST_RUNNER_MANIFEST_MISMATCH")
        with zipfile.ZipFile(test_apk) as archive:
            fixture = archive.read("assets/ts7-baseline/red.h264.base64")
            require(sha(base64.b64decode(fixture)) == FIXTURE_SHA, "TEST_APK_FIXTURE_MISMATCH")
    return {"status": "APK_INSPECTION_PASS", "application_id": APPLICATION, "min_sdk": int(sdk[1]),
            "apk_sha256": sha(path.read_bytes()), "signer_certificate_sha256": signer_digest,
            "native_libraries": native, "identity_free": True, "permissions": permissions,
            "boot_receiver_disabled": True, "test_artifacts_in_install_apk": False}


def parse_instrumentation(raw, source_sha):
    text = raw.decode(errors="replace")
    results = {}
    for line in text.splitlines():
        match = re.fullmatch(r"INSTRUMENTATION_RESULT: (ts7\.[a-z0-9_]+)=([A-Za-z0-9_-]+)", line)
        if match:
            require(match[1] not in results, "INSTRUMENTATION_DUPLICATE_RESULT")
            results[match[1]] = match[2]
    checks = {name: results.get(f"ts7.{name}") if results.get(f"ts7.{name}") in ("PASS", "FAIL")
              else "NOT_REPORTED" for name in CHECKS}
    # Codes are read from the committed test-only literals, not arbitrary device output.
    fixture_root = Path(__file__).resolve().parents[1] / "mobile/src/androidTest/java/com/shilapi/xcertplay/baseline"
    literals = set()
    for path in fixture_root.glob("*.kt"):
        literals.update(re.findall(r'"([A-Z][A-Z0-9_]{3,79})"', path.read_text()))
    codes = {}
    for name in CHECKS:
        if checks[name] != "PASS":
            candidate = results.get(f"ts7.failure_{name}")
            codes[name] = candidate if candidate in literals else "FAILURE_CODE_NOT_REPORTED"
    details = {"checks": checks, "failure_codes": codes, "api": 27,
               "real_ts7": "NOT_RUN", "real_iphone": "NOT_RUN", "phone_session_proven": False}
    phase = results.get("ts7.media_phase", "NOT_REPORTED")
    details["media_probe"] = {
        "phase": phase if phase in {"FIRST_SURFACE", "REATTACHED_SURFACE", "RESTARTED_STREAM"} else "NOT_REPORTED",
        "decoder_output": results.get("ts7.media_decoder_output") if results.get("ts7.media_decoder_output") in {"YES", "NO"} else "NOT_REPORTED",
    }
    for key in ("frames", "red_frames", "decoder_errors", "output_formats", "backlog_recoveries", "invalid_units", "stalled_recoveries", "queued_jobs",
                "control_input_queued", "control_output_released", "control_frames", "control_red_frames",
                "buffer_input_queued", "buffer_output_released"):
        value = results.get(f"ts7.media_{key}", "")
        details["media_probe"][key] = int(value) if re.fullmatch(r"[0-9]{1,3}", value) else None
    for key in ("worker_alive", "input_attempted", "input_queued"):
        value = results.get(f"ts7.media_{key}")
        details["media_probe"][key] = value if value in {"YES", "NO"} else "NOT_REPORTED"
    for key, allowed in {
        "control_status": CONTROL_STATUSES - {"BYTEBUFFER_OUTPUT_PASS"},
        "buffer_control_status": CONTROL_STATUSES - {"PASS"},
        "codec_kind": {"EMULATOR_HOST_CODEC", "ANDROID_SOFTWARE_CODEC", "OTHER_CODEC"},
        "worker_state": {"NEW", "RUNNABLE", "BLOCKED", "WAITING", "TIMED_WAITING", "TERMINATED"},
        "worker_phase": {"CODEC_INPUT_DEQUEUE", "CODEC_INPUT_QUEUE", "CODEC_INPUT_BUFFER",
                         "CODEC_OUTPUT_DEQUEUE", "CODEC_OUTPUT_RELEASE", "INPUT_LOG_FORMAT",
                         "WAITING_FOR_VIDEO_JOB", "VIDEO_FEED", "VIDEO_CONFIGURE", "VIDEO_RELEASE",
                         "UNKNOWN_CALL_SITE"},
    }.items():
        value = results.get(f"ts7.media_{key}")
        details["media_probe"][key] = value if value in allowed else "NOT_REPORTED"

    def valid(condition, code):
        if not condition:
            raise InstrumentationFailure(code, details)

    valid(re.search(r"^INSTRUMENTATION_CODE: -1\s*$", text, re.M), "INSTRUMENTATION_DID_NOT_FINISH_OK")
    valid(results.get("ts7.source_sha") == source_sha, "INSTRUMENTATION_SHA_MISMATCH")
    valid(results.get("ts7.api") == "27", "INSTRUMENTATION_API_MISMATCH")
    valid(results.get("ts7.suite") == "EMULATOR_PASS", "INSTRUMENTATION_SUITE_FAILED")
    valid(all(value == "PASS" for value in checks.values()), "INSTRUMENTATION_CHECK_FAILED_OR_MISSING")
    return {check: "PASS" for check in CHECKS}


def native_codec_fault_counts(raw):
    # Only the isolated API27 emulator is read. Native log contents never leave memory.
    messages = []
    for line in raw.decode(errors="replace").splitlines():
        match = re.fullmatch(r"[EWF]/(SoftAVC|SoftAVCDec|ACodec|MediaCodec|SoftwareRenderer)\s*\(\s*[0-9]+\):\s*(.*)", line)
        if match:
            messages.append((match[1], match[2]))
    patterns = {
        "unsupported_resolution": (r"SoftAVC(?:Dec)?", r"Unsupported resolution :"),
        "decoder_allocation_failure": (r"SoftAVC(?:Dec)?", r"Allocation failure in decoder"),
        "decoder_argument_failure": (r"SoftAVC(?:Dec)?", r"Decoder arg setup failed"),
        "codec_signal_error": (r"ACodec", r"signalError\(omxError "),
        "codec_buffer_size_failure": (r"ACodec", r"failed to set min buffer size to "),
        "codec_buffer_ownership_failure": (r"ACodec", r"Wrong ownership in (?:EBD|IBF|FBD):"),
        "native_window_failure": (r"(?:ACodec|SoftwareRenderer)",
                                  r"(?:native_window_set_buffer_count failed:|dequeueBuffer failed:|Surface::(?:dequeueBuffer|queueBuffer|set_buffers_timestamp) returned error)"),
    }
    return {name: min(999, sum(bool(re.fullmatch(tag_pattern, tag) and re.match(message_pattern, message))
                              for tag, message in messages))
            for name, (tag_pattern, message_pattern) in patterns.items()}


def emulator(repo, path, test_apk, serial, source_sha):
    require(serial.startswith("emulator-"), "EMULATOR_SERIAL_REQUIRED")
    require(path.is_file() and test_apk.is_file(), "EMULATOR_APK_MISSING")
    adb = shutil.which("adb")
    require(adb is not None, "ADB_UNAVAILABLE")

    def device(*args, timeout=60):
        return command([adb, "-s", serial, *args], timeout=timeout)

    require(device("shell", "getprop", "ro.build.version.sdk").strip() == b"27", "API27_EMULATOR_REQUIRED")
    require(device("shell", "getprop", "ro.product.cpu.abi").strip() == b"x86_64", "X86_64_EMULATOR_REQUIRED")
    require(device("shell", "getprop", "ro.kernel.qemu").strip() == b"1", "QEMU_EMULATOR_REQUIRED")
    for apk in (path, test_apk):
        require(b"Success" in device("install", "-r", "-t", str(apk)), "EMULATOR_INSTALL_FAILED")
    # This operation is confined to the isolated emulator after the three identity checks.
    require(device("shell", "pm", "clear", APPLICATION).strip() == b"Success", "EMULATOR_RESET_FAILED")
    device("logcat", "-c")
    raw = device("shell", "am", "instrument", "-w", "-r", "-e", "source_sha", source_sha,
                 f"{APPLICATION}.test/{RUNNER}", timeout=240)
    original_failure = None
    try:
        results = parse_instrumentation(raw, source_sha)
    except InstrumentationFailure as error:
        original_failure = error
        try:
            logs = device("logcat", "-d", "-v", "brief", "-s", "SoftAVC:E", "SoftAVCDec:E",
                          "ACodec:E", "MediaCodec:E", "SoftwareRenderer:W")
            error.details["native_codec_fault_counts"] = native_codec_fault_counts(logs)
        except CheckFailure:
            error.details["native_codec_fault_counts"] = {"status": "NOT_AVAILABLE"}
        raise
    finally:
        try:
            device("shell", "am", "force-stop", APPLICATION)
        except CheckFailure:
            if original_failure is None:
                raise
            original_failure.details["emulator_cleanup"] = "FORCE_STOP_FAILED"
    return {"status": "EMULATOR_PASS", "api": 27, "abi": "x86_64", "checks": results,
            "apk_sha256": sha(path.read_bytes()), "test_apk_sha256": sha(test_apk.read_bytes()),
            "real_ts7": "NOT_RUN", "real_iphone": "NOT_RUN", "phone_session_proven": False}


def package(repo, args, source_sha):
    require(clean(repo), "RELEASE_SOURCE_DIRTY")
    reports = {}
    for name, expected in (("upstream", "UPSTREAM_BUILD_PASS"), ("source", "SOURCE_INSPECTION_PASS"),
                           ("build", "HOST_TEST_PASS"), ("apk", "APK_INSPECTION_PASS"), ("emulator", "EMULATOR_PASS")):
        path = getattr(args, f"{name}_report")
        require(path is not None and path.is_file(), "REQUIRED_REPORT_MISSING")
        report = json.loads(path.read_text())
        require(report.get("status") == expected, "REQUIRED_GATE_NOT_PASS")
        require(report.get("source_sha") == (UPSTREAM if name == "upstream" else source_sha), "REPORT_SHA_MISMATCH")
        reports[name] = report
    digest = sha(args.apk.read_bytes())
    require(reports["apk"]["apk_sha256"] == digest == reports["emulator"]["apk_sha256"], "TESTED_APK_DIGEST_MISMATCH")
    require(reports["source"]["working_tree_clean"], "SOURCE_GATE_NOT_CLEAN")
    require(reports["source"]["source_tree_sha256"] == reports["build"]["source_tree_sha256"] == source_digest(repo),
            "TESTED_SOURCE_DIGEST_MISMATCH")
    destination = args.artifact_dir / "TS7-DiPlay-FullFork-Baseline-R1.apk"
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(args.apk, destination)
    require(sha(destination.read_bytes()) == digest, "PUBLISHED_APK_DIGEST_MISMATCH")
    return {"status": "BASELINE_R1_PASS", "apk_sha256": digest,
            "gates": {name: report["status"] for name, report in reports.items()},
            "classification": "ENGINEERING BASELINE / NOT YET VERIFIED AS FUNCTIONAL CARPLAY ON TS7",
            "real_ts7": "NOT_RUN", "real_iphone": "NOT_RUN", "phone_session_proven": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("check", choices=("pristine", "source", "build", "apk", "emulator", "package"))
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--require-clean", action="store_true")
    parser.add_argument("--variant", choices=("upstream", "ts7"), default="ts7")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--test-apk", type=Path)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--artifact-dir", type=Path, default=Path("artifacts"))
    for name in ("upstream", "source", "build", "apk", "emulator"):
        parser.add_argument(f"--{name}-report", type=Path)
    args = parser.parse_args()
    report = {"schema": 1, "check": args.check, "upstream_sha": UPSTREAM}
    try:
        repo = args.repo.resolve()
        source_sha = git(repo, "rev-parse", "HEAD").decode().strip()
        report["source_sha"] = source_sha
        if args.check == "pristine":
            result = pristine(repo)
        elif args.check == "source":
            result = source(repo, args.require_clean)
        elif args.check == "build":
            result = build(repo, args.variant)
        elif args.check == "apk":
            require(args.apk is not None, "APK_ARGUMENT_REQUIRED")
            result = inspect_apk(repo, args.apk.resolve(), args.test_apk.resolve() if args.test_apk else None)
        elif args.check == "emulator":
            require(args.apk is not None and args.test_apk is not None, "APK_ARGUMENT_REQUIRED")
            result = emulator(repo, args.apk.resolve(), args.test_apk.resolve(), args.serial, source_sha)
        else:
            require(args.apk is not None, "APK_ARGUMENT_REQUIRED")
            result = package(repo, args, source_sha)
        report.update(result)
    except CheckFailure as error:
        report.update(status="FAIL", failure_code=str(error))
        if isinstance(error, (BuildFailure, InstrumentationFailure)):
            report.update(error.details)
    except Exception:
        # Do not expose ZIP/XML/parser/host exception text: it may contain input data.
        report.update(status="FAIL", failure_code="CHECK_INTERNAL_ERROR")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    print(json.dumps(report, sort_keys=True))
    return 1 if report["status"] == "FAIL" else 0


if __name__ == "__main__":
    sys.exit(main())
