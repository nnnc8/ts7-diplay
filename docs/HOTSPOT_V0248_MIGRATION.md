# TS7 Hotspot Wake Patch: v0.2.48 migration

This is a version port, with no new runtime features.

## Source and preserved history

- Upstream: `programmerguohuajing/DiPlay-Legacy-Android`.
- Exact v0.2.48 base: `72dbc164114c9fa16f2c7fb77f36ecd5d48aa25d`.
- Fork: `nnnc8/ts7-diplay`.
- Original branch: `feature/ts7-hotspot-wake-v0246`, retained at
  `1d7f8c2e5e3f80fa1c901ffab2f5ad05ac92fbb4`.
- New branch: `feature/ts7-hotspot-wake-v0248`.

The five original commits were cherry-picked in this order with `git cherry-pick -x`:

| Original | v0.2.48 | Change |
| --- | --- | --- |
| `66abba9` | `f95316e` | Hotspot wake recovery |
| `672f51c` | `486b60a` | JVM and API27 tests |
| `46b5f07` | `a236b6a` | Disposable upstream checks |
| `f4ead80` | `f20f2a1` | Android SDK provisioning |
| `1d7f8c2` | `4c78281` | Wait for actual socket release in the network test |

All five applied without conflicts. `DiPlayActivity.kt` and the English/Chinese
resources merged automatically. Stable patch IDs match each original commit;
`git range-diff` shows only the added provenance lines. In particular, the
v0.2.48 floating-dot setting and API17 accessibility guard remain present.

The v0.2.48 CarPlay Host, AirPlay/VPN Bonjour advertisement and error handling,
controller/Bluetooth bootstrap behavior, and iAP2 transport changes remain
byte-for-byte identical to the exact upstream commit. Android manifests retain
upstream content plus the original TS7 observer service declaration. Android
Gradle files remain identical to upstream, including versionCode 68/versionName
0.2.48, SDK/NDK settings and authentication/signing gates.

## Existing CI evidence and port-only adjustments

The old branch's run
[38403252512](https://github.com/nnnc8/ts7-diplay/actions/runs/38403252512)
failed with two `BindException` assertions in `AndroidEightCarPlayServiceTest`.
The original `1d7f8c2` fix waits up to five seconds for a real bind after detach;
it does not modify production network behavior.

The old branch's run
[38414417548](https://github.com/nnnc8/ts7-diplay/actions/runs/38414417548)
passed the full JVM/lint/build command and its API27 instrumentation reported
`Tests run: 1, failures: 0, ignored: 0` / `INSTRUMENTATION_CODE: -1`.
The overall job nevertheless failed because `rg` was missing during result
validation. The new branch uses portable `grep` for that validation and tag
selection. Failure assertions remain mandatory.

Other maintenance changes enable push CI for the new branch, point disposable
checks at its exact base/branch, retain `-x` during future syncs, and upload the
identity-free upstream debug APK with package/version and credential checks.
No runtime source, test behavior, Gradle configuration or feature was added.

## Equivalence and required validation

The TS7 wake observer, recovery policy, tethering changes, preference, boot
integration, translated descriptions and tests are unchanged from v0.2.46.
The opt-in still defaults to false and applies only to saved MANUAL car-hotspot
mode with wireless enabled. Recovery retains its 60-second wake budget, three
8-second attempts, five-second retry spacing and three-second radio settling;
sleep/opt-out/live-session cancellation and existing permission behavior remain.

Run the full AGENTS.md JVM/lint/debug-build command with the existing Mockito
init script and `:mobile:assembleDebugAndroidTest`, then
`bash scripts/test-hotspot-api27.sh` on stock API27. The application is built with
upstream compileSdk 37/minSdk 19; API27 compatibility is checked by actual APK
installation and instrumentation on Android 8.1, not by downgrading compileSdk.

The diagnostic artifact is the existing upstream debug app
`com.shihab.diplay.hudtest`, labelled **DiPlay HUD Test**. It contains no accessory
certificate/private key and cannot establish standalone authenticated CarPlay.
It does not replace official `com.shihab.diplay`. No release APK or merge is
performed. Final CI evidence and APK SHA-256 are supplied with the migration
report after validation.

**Real TS7 verification: NOT YET.** Source equivalence and stock-emulator tests
do not establish TS7 ROM permission support or physical wake restoration. Use
the parked-vehicle checklist in [Hotspot wake recovery](HOTSPOT_WAKE_RECOVERY.md)
and record the exact diagnostic APK hash, logcat and unchanged system AP details.
