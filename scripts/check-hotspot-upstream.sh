#!/usr/bin/env bash
# Creates a disposable test branch; never merges, signs or publishes an APK.
set -euo pipefail
base=72dbc164114c9fa16f2c7fb77f36ecd5d48aa25d
patch_ref=${PATCH_REF:-feature/ts7-hotspot-wake-v0248}
requested_tag=${1:-latest}
test -z "$(git status --porcelain)" || { echo 'A clean checkout is required.' >&2; exit 1; }
git fetch https://github.com/programmerguohuajing/DiPlay-Legacy-Android.git 'refs/tags/*:refs/tags/*'
if [[ "$requested_tag" == latest ]]; then
  requested_tag=$(git tag --list --sort=-version:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1)
fi
[[ "$requested_tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo 'Invalid release tag.' >&2; exit 1; }
git merge-base --is-ancestor "$base" "$patch_ref"
mapfile -t patches < <(git rev-list --reverse "$base..$patch_ref")
test "${#patches[@]}" -gt 0
branch="hotspot-sync/${requested_tag}-$(date -u +%Y%m%d%H%M%S)"
git switch -c "$branch" "$requested_tag^{commit}"
if ! git cherry-pick -x "${patches[@]}"; then
  echo 'MERGE_CONFLICT: inspect the disposable branch; the original branch is unchanged.' >&2
  git diff --name-only --diff-filter=U
  exit 2
fi
echo "NO_MERGE_CONFLICT: $branch based on $requested_tag"
bash ./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug \
  :mobile:assembleDebug :home:assembleDebug :maphost:assembleDebug \
  :mobile:assembleDebugAndroidTest --max-workers=2 -I scripts/test-jvm.gradle
# Run this script inside an API27 emulator job; the same test is used by normal CI.
bash scripts/test-hotspot-api27.sh
echo "API27_PASS: $branch (stock emulator; real TS7 remains unverified)"
