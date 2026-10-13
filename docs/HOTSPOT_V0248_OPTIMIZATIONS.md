# v0.2.48 targeted hotspot optimizations

Follow-up to the port-only baseline `a1e08fd01f6cf3c06c178f2f3033151519027860`.
Upstream remains `72dbc164114c9fa16f2c7fb77f36ecd5d48aa25d`. The preserved
v0.2.46 branch is not rewritten. This follow-up implements the ten specifically
requested optimizations; it adds no settings or protocol features.

1. Completed failed cycles can restart on airplane exit; mid-session tethered AP
   drops can restart after a 10-second cooldown. Active workers remain deduplicated.
2. Preference listeners check only eligibility keys. Foreground channel/notification
   creation occurs once; every service start still fulfills `startForeground`.
3. Wi-Fi methods, ConnectivityManager service field and per-class tethering methods
   are cached, including unavailable lookups with existing failure semantics.
4. ADB parser regexes compile once; production dump polling uses 500ms.
5. Shared daemon ADB workers replace per-request threads. A temporary cancellation
   watcher removes timed-get polling and guarantees abort/cancel cleanup.
6. Pending setup and invalid configured credentials block recovery. As clarified,
   valid non-empty SSIDs remain eligible. Empty-default test setup remains valid.
7. Settings ADB probes share one in-flight check, target current controls, ignore
   stale authorization-era results and refresh after authorization/vehicle work.
8. Approval quiet checks default to two seconds. Tests use a short injected interval.
   Cancel/close abort original and probe sockets and prevent further probes.
9. Stateless hotspot status/result policies live in `CarHotspotUiSupport`. Existing
   categories, sections, resource IDs and search indexing remain in the Activity.
   The existing search UI test waits for the probe Future rather than the lifetime
   of its reused thread; its single-probe, category and control assertions remain.
10. Sync supports ordered `PATCH_COMMITS` while retaining full-range default and `-x`.
    Both CI workflows cache the pinned SDK/NDK. The 15 checksum sidecars are archived
    unchanged after checking scripts, workflows and test path dependencies.

The required public-tree check previously rejected the unchanged upstream public
TLS CA bundle. Its exception now pins both the exact path and SHA-256. Other PEM
files, modified bundles, private-key blocks and credential containers remain denied.
Mobile authentication/signing tasks, manifest permissions and upstream CarPlay,
AirPlay, VPN, Bluetooth and iAP2 protocol sources are unchanged.

## Validation

Local public-tree check, five Python script/security regression tests, shell syntax,
workflow YAML/cache ordering, checksum byte equivalence and protected-file checks
are run before Android validation. Android results will be recorded after running
the full AGENTS.md JVM/lint/build command plus API27 instrumentation.

**Real TS7 verification: NOT YET.** A stock emulator cannot establish the TS7 ROM's
privileged tethering support. Use the existing parked-device checklist, including
airplane exit beyond 60 seconds and an AP drop while the screen stays on.
