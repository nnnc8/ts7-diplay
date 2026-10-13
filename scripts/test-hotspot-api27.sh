#!/usr/bin/env bash
set -euo pipefail
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = 27
adb install -r mobile/build/outputs/apk/debug/mobile-debug.apk
adb install -r mobile/build/outputs/apk/androidTest/debug/mobile-debug-androidTest.apk
adb shell am instrument -w -r -e class CarHotspotRecoveryRuntimeTest \
  com.shihab.diplay.hudtest.test/com.shilapi.xcertplay.T3LegacyInstrumentation | tee api27-hotspot.txt
# The custom legacy runner reports -1 for Activity.RESULT_OK. Never accept just exit status.
grep -Fxq 'INSTRUMENTATION_RESULT: stream=Tests run: 1, failures: 0, ignored: 0' api27-hotspot.txt
grep -Fxq 'INSTRUMENTATION_CODE: -1' api27-hotspot.txt
