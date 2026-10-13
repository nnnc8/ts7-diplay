# Existing system hotspot recovery

Base: v0.2.48, exact upstream source commit
`72dbc164114c9fa16f2c7fb77f36ecd5d48aa25d`.
The original `feature/ts7-hotspot-wake-v0246` branch remains at
`1d7f8c2e5e3f80fa1c901ffab2f5ad05ac92fbb4`; none of its commits were rewritten.
See [the version migration record](HOTSPOT_V0248_MIGRATION.md).

Only the saved **Built-in car hotspot / MANUAL** path is affected. The existing
auto-hotspot preference defaults off. Non-BYD units can enable it without being
identified as BYD or gaining any BYD vehicle features. BYD's existing ADB grant
card is retained. No SSID, password, security, authentication, Bluetooth, iAP2,
AirPlay, video or audio configuration is changed.

## Observation and bounds

An opted-in foreground service displays an ongoing notification. It registers
screen on/off, airplane mode and Wi-Fi radio/AP broadcasts at runtime, independently
of Activity callbacks and CarPlay's session service. Activity resume reconciles
service eligibility; boot starts the observer but is not used to detect wake.
START_STICKY permits Android to recreate a killed service. Force-stop and a ROM
that prevents service restart remain outside an ordinary application's control.

There is one worker per observer and a generation invalidates work on screen-off
or destruction. Repeated screen-on/resume cannot reset the wake budget. The
existing tethering mutex serializes recovery with connection startup, rechecks AP
state after acquiring the lock, and waits if AP is already enabling/disabling.
An established CarPlay session suppresses recovery and cancels pending attempts.

Each launch/wake has a 60-second elapsed-time limit, at most three 8-second start
attempts, and at least five seconds between attempts. Airplane mode must be off
and the station radio stable for three seconds. Every radio/airplane event
invalidates settling, including rapid changes between polls. Attempts use the
existing `CarHotspotTethering.enable()` and its AP ENABLED observation; API or
shell acceptance alone is insufficient. No work runs while asleep. There are no
wake locks, alarms, periodic idle polls, or hotspot stop/configuration calls.

Results are logged under `DiPlay-Hotspot` and persisted as `last_recovery` in
`diplay_car_hotspot`: `PERMISSION_REQUIRED`, `HOTSPOT_UNSUPPORTED`, or
`HOTSPOT_START_FAILED`; success/skip results are `HOTSPOT_RESTORED`,
`ALREADY_ENABLED`, `SESSION_ACTIVE`, `CANCELLED`. Airplane/radio readiness timing
out reports `HOTSPOT_START_FAILED` without issuing any start request.

## Android 8.1 permissions and supported control paths

[AOSP Android 8.1 ConnectivityManager](https://android.googlesource.com/platform/frameworks/base/+/android-8.1.0_r1/core/java/android/net/ConnectivityManager.java)
`enforceTetherChangePermission()` checks `TETHER_PRIVILEGED` when the ROM defines
a mobile-hotspot provisioning app; otherwise it checks the calling package's
WRITE_SETTINGS app-op. Thus `Settings.System.canWrite()` is only a preflight,
never evidence that restricted tethering permission has been granted. The actual
framework request can still return `PERMISSION_REQUIRED`.

On non-BYD units, the App permissions button opens Android's user-controlled
system settings access screen. It does not grant signature permissions. The
existing local ADB fallback is usable only with an already-approved key and a
ROM-advertised start command. It does not offer a debugging approval prompt in
the background, guess binder transaction numbers, use root, or create a local-only
replacement AP. AOSP 8.1 does not guarantee these vendor shell commands exist.
If neither framework nor advertised ADB path works, manual system settings remain
necessary. TS7's particular ROM permission/provisioning policy needs device evidence.

[Android background execution limits](https://developer.android.com/about/versions/oreo/background)
require a foreground service for continuous background observation on API26+.
The observer calls `startForeground()` promptly and unregisters on destruction.

## Validation and device checklist

CI runs the AGENTS.md build/lint/unit command, then an identity-free debug APK
and an API27 stock emulator instrumentation test. Deterministic unit tests cover
delayed/stuck airplane mode, readiness settling, AP already enabled, failures,
permission denial, unsupported APIs, bounded retries, session establishment,
sleep/opt-out cancellation and radio flapping. API27 service tests cover duplicate
signals and background wake without Activity resume. Existing tethering tests
cover AP confirmation, ADB fallback and serialized starts.

The CI command uses `-I scripts/test-jvm.gradle`: Mockito is attached at test JVM
startup on Java 25. This affects host-side tests only, not the APK.

The stock emulator test backgrounds the app, uses actual sleep/wake key events,
delays airplane exit and checks the fixed permission-denial result. It cannot prove
privileged TS7 hotspot restoration or CarPlay authentication. **Real TS7: NOT YET.**

On TS7, first enable the option in MANUAL mode, grant only permissions the ROM
allows, and confirm the background notification. With the existing hotspot
details unchanged, sleep and wake repeatedly, including a delayed airplane exit,
an already-on AP, foreground/background DiPlay and an established CarPlay session.
Collect `adb logcat -s DiPlay-Hotspot` and current AP state from `dumpsys wifi`.
Check that the saved hotspot name/password/security are unchanged. Do not replace
the known-working official installation with a differently signed source build.
Ordinary builds have no accessory identity and are not a complete CarPlay release.

The v0.2.48 CI artifact `ts7-hotspot-diagnostic-v0248` contains an identity-free
debug APK named `ts7-hotspot-diagnostic-v0.2.48.apk`. It uses the upstream debug
application ID `com.shihab.diplay.hudtest` (launcher label **DiPlay HUD Test**),
not the official `com.shihab.diplay`. CI verifies the package/version and absence
of authentication assets before uploading it. It can coexist with official DiPlay;
it updates an existing HUD Test app only if that app has the same signing key.
Keep the official app's auto-hotspot option off while testing this diagnostic app
so that two observers do not compete for the same system hotspot.

## Upstream maintenance

The runtime/settings patch is isolated from test/CI and sync tooling so it can be
cherry-picked independently. On a clean checkout with Android tooling and a stock
API27 emulator connected, run:

```sh
bash scripts/check-hotspot-upstream.sh latest
```

This fetches stable release tags, creates a new local `hotspot-sync/...` branch,
cherry-picks the patch branch's commits, reports merge conflicts, and runs the
build plus API27 tests. It never touches main/R1, merges, pushes, signs or publishes
an APK. Inspect a conflicted disposable branch before `git cherry-pick --abort`.
The workflow performs the same disposable check and uploads reports; its weekly
schedule is active only once that workflow exists on the fork's default branch.
Keeping main unchanged means no scheduled job is silently installed there.
