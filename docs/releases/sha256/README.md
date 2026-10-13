# Archived legacy release checksums

These 15 upstream `.apk.sha256` sidecars were moved from the repository root without
changing their bytes. They describe historical releases, not builds from this branch.

Checked `scripts/build-beta.py`, `scripts/build_site.py`, `scripts/check_public_tree.py`,
all workflows and source/test references before moving. No code depends on their
root paths. Update-client tests refer to HTTP release-asset filenames; those names
and remote release assets remain unchanged. No APK or release was published.
