#!/usr/bin/env python3
"""Package exact committed TS7 source without credentials or unbundled historical images."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repo = args.repo.resolve()
    if git(repo, "status", "--porcelain").strip():
        raise SystemExit("SOURCE_WORKTREE_DIRTY")
    commit = git(repo, "rev-parse", "HEAD").decode().strip()
    upstream = "c8884adcc75bfda3c134db63877bd6c6f83beb74"
    subprocess.check_call(["git", "-C", str(repo), "merge-base", "--is-ancestor", upstream, commit])
    files = [name for name in git(repo, "ls-tree", "-r", "--name-only", "-z", commit).decode().split("\0") if name]
    # These files are preserved in Git, not relicensed or shipped in the install APK.
    excluded = [name for name in files if name == "common/src/main/res/drawable/ic_carplay.png"
                or (name.startswith("shared/src/main/assets/byd-hud-icons/") and name.endswith(".png"))
                or (name.startswith(("site/assets/", "asset/")) and name.endswith((".png", ".jpg", ".jpeg")))]
    forbidden = {".pk8", ".p7b", ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".apk", ".aab"}
    private_key = re.compile(rb"-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----\s+[A-Za-z0-9+/=\r\n]{40,}")
    if any(Path(name).suffix.lower() in forbidden or "offline-mfi" in Path(name).parts for name in files):
        raise SystemExit("SOURCE_CREDENTIAL_CONTAINER")
    manifest = {
        "source_sha": commit, "upstream_sha": upstream,
        "excluded_historical_images": excluded,
        "exclusions_preserved_in_git": True, "receiver_source_and_modules_retained": True,
        "build": "JAVA_HOME=JDK25 ANDROID_HOME=SDK bash gradlew :mobile:assembleBaseline",
        "classification": "ENGINEERING BASELINE / NOT YET VERIFIED AS FUNCTIONAL CARPLAY ON TS7",
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    prefix = "TS7-DiPlay-FullFork-Baseline-R1-source/"
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in files:
            if name in excluded:
                continue
            data = git(repo, "show", f"{commit}:{name}")
            if private_key.search(data):
                raise SystemExit("SOURCE_PRIVATE_KEY_BLOCK")
            item = zipfile.ZipInfo(prefix + name, (2026, 1, 1, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            item.external_attr = 0o644 << 16
            archive.writestr(item, data)
        archive.writestr(prefix + "TS7_SOURCE_MANIFEST.json", json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    with zipfile.ZipFile(args.output) as archive:
        if archive.testzip() is not None:
            raise SystemExit("SOURCE_ZIP_INVALID")
        for required in ("LICENSE", "docs/THIRD_PARTY_NOTICES.md", "mobile/build.gradle.kts",
                         "common/src/debug/res/drawable/ic_carplay.xml", "docs/TS7_RELEASE_NOTICES.md"):
            if prefix + required not in archive.namelist():
                raise SystemExit("SOURCE_REQUIRED_INPUT_MISSING")
    print(json.dumps({"source_sha": commit, "source_archive_sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
                      "files_included": len(files) - len(excluded), "historical_images_excluded": len(excluded)}, sort_keys=True))


if __name__ == "__main__":
    main()
