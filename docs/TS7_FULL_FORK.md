# TS7 full-fork engineering baseline

Primary receiver: https://github.com/nnnc8/ts7-diplay

Upstream: https://github.com/programmerguohuajing/DiPlay-Legacy-Android
Exact start: `c8884adcc75bfda3c134db63877bd6c6f83beb74`.
Original project: https://github.com/shihabal3amri/DiPlay

`baseline/upstream-legacy-0.2.7` is the unchanged upstream commit. All 428 tracked
files and all two commits exposed by this upstream repository are retained. The
upstream itself starts at a public source snapshot; this does not manufacture an
ancestry connection to the original DiPlay repository.

`feature/ts7-android81-baseline` starts there. TS7 commits are separate. Keep
`main` unchanged until separately authorized. Do not begin
`feature/ts7-minimal-optimization` before an accepted measured baseline.

## Toolchain and current evidence

Original-source local build PASS: JDK25.0.4.1, wrapper Gradle9.5.0 (upstream
checksum), AGP9.3.0, SDK37.0, build-tools36.0.0, NDK25.2.9519653. No rewritten
build system. Invoke the original non-executable wrapper with `bash gradlew`.
The compatibility branch only restores its executable file mode for the
unchanged upstream workflow; the wrapper bytes and pristine branch are untouched.

```
bash gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug --no-daemon --max-workers=4
```

326 tests, zero failures/errors/skips; lint and original debug APK PASS at exact
upstream source. APK minSdk19/targetSdk37, ARMv7 and x86_64 native libraries
present. This is UPSTREAM_BUILD_PASS, not TS7_RUNTIME_PASS. The original APK is
not published: it contains separately restricted/provenance-uncertain images.

TS7 public artifact is `:mobile:assembleBaseline`, not the upstream HUD debug
APK. It installs independently as `com.shihab.diplay.ts7`, preserves the Classic
Activity and all modules, and uses a test/debug signer, not a production key.
Public builds reject external accessory assets. BYD donor PNGs are excluded at
asset merging and a neutral original resource overrides the Apple icon. Sources
and original notices remain in the Fork; APK/source inspection is still required.

The exact-commit CI reports and engineering prerelease record the emulator and
artifact acceptance results; a host build alone cannot establish those results.
Real TS7 and real iPhone acceptance remain separate. Missing legally provisioned
authentication must remain AUTH_BLOCKED, never fake CarPlay success. Prior
firmware-derived experimental credentials are not approved for this new public
Fork, CI or release.

## Complete module and wireless path

| Module | Preserved responsibilities | TS7 baseline |
| --- | --- | --- |
| mobile/ | Standalone application, manifest, Gradle variants | Separate baseline variant; no HUD debug entry points |
| common/ | Classic home/settings, phone selection, host Activity, session service, diagnostics, persistence | Retained; necessary compatibility/privacy fixes only |
| shared/ | Controller, Bluetooth RFCOMM, iAP2, AirPlay, authentication interfaces, radio backends, video/audio/touch, native code | Full source retained |
| automotive/ | Android Automotive application | Retained; minSdk28, not the TS7 APK |
| site/ | Upstream AGPL website | Retained; no TS7 website deployment |

Wireless: original settings/selected paired phone → original Controller → chosen
Android-compatible hotspot backend → Bonjour/AirPlay listeners → Bluetooth
RFCOMM/iAP2 identification/authentication → Wi-Fi handoff/tunneled iAP2 → verified
AirPlay pairing/control → original CarPlayMediaEngine → AndroidMediaSink →
MediaCodec/Surface and AudioTrack. Original microphone uplink, HID/touch, stop and
recovery code remain. Wired/USB/NCM/MFi common dependencies are not deleted.

Radio state, authentication-provider availability, protocol acceptance, decoded
output and real phone success are separate. No ordinary network flag or generated
test pattern can establish a phone session.

## Preserved TS7 engineering history

https://github.com/nnnc8/ts7-carplay-lite remains the diagnostic/hardware/regression
and historical project. Preserve Diagnosticv0.2, every prior release/commit,
feature/diplay-ts7-port, fixed5/fixed13 relay, Issues, PR18 and actual reports.
Do not develop a second competing receiver there or merge PR18 automatically.

Physical platform: Android8.1/API27/ARMv7/2GB/1280×720, SPRD strings VERIFIED;
precise silicon UNKNOWN. Original synthetic H264 evidence: OMX.sprd.h264.decoder,
~29.85fps/8380 rendered/7 dropped/0 restarts, not full CarPlay or a duration gate.
Two prior reports cover12 individual platform checks, not full handoff/coexistence.

Dev.1 failure https://github.com/nnnc8/ts7-carplay-lite/issues/13#issuecomment-6052472030
shows HOTSPOT_SECURITY_UNSUPPORTED before decoder/session. Old custom port checks
WPA_PSK only; complete upstream maps WPA2_PSK separately. Actual TS7 key-management
bits were not uploaded, so the exact hardware security type is UNKNOWN. Keep
separate original, patched and device evidence; never silently accept open APs,
guess a channel or log passphrases.

## Evidence gates and next car visit

Before release: pristine upstream build, patched tests/lint/build, ARMv7 ELF/APK
inspection, actual API27 emulator startup/stop/restart and native load, original
video Surface exercise, privacy/license/source scans and exact-commit CI.

Label all results HOST_TEST_PASS / EMULATOR_PASS / REAL_TS7_PASS /
REAL_IPHONE_PASS independently. R1 must say ENGINEERING BASELINE / NOT YET
VERIFIED AS FUNCTIONAL CARPLAY ON TS7.

First car milestone only: install R1, open Classic UI, choose a paired iPhone,
observe hotspot result, Bluetooth bootstrap phase and terminal reason. If no
authorized authentication exists, report AUTH_BLOCKED and stop. Do not repeat the
old12 platform probes or old synthetic5/15-minute run. Only a real session permits
audio/touch/reconnect acceptance and subsequent baseline-vs-optimization benchmarks.
