# TS7 full-fork contract

Read docs/TS7_FULL_FORK.md, upstream README.md, docs/BUILD.md and the exact task
issue before edits. Upstream pin c8884adcc75bfda3c134db63877bd6c6f83beb74.
Full fork first, make it work, measure baseline, optimize afterward.

Preserve every upstream source/module/license and Git history. Baseline branch
must remain the exact upstream commit. Keep TS7 changes isolated; no destructive
reset, source deletion, controller/protocol rewrite or stable-main overwrite.
Original Classic UI, audio, video, touch, wireless and shared wired dependencies
are retained. Only necessary API27/ARMv7/startup/privacy/license patches precede
baseline acceptance. Disable vendor features with configuration, not deletion.

No extracted or unlicensed accessory identity in Git, CI, APK, source archives,
releases or diagnostics. Keep upstream authentication interfaces; only explicitly
authorized external provisioning. Missing provider is AUTH_BLOCKED, not success.
Reject open-hotspot silent fallback, unknown security and guessed AP channels.
No raw identifiers, protocol/media contents, secrets or exception strings in logs.

Keep nnnc8/ts7-carplay-lite, all historical artifacts/data/relay and PR18 unchanged
apart from a clear cross-repository handoff. No PR18 merge. Do not repeat existing
platform or synthetic car tests. Publish only meaningful tested milestones.

Separate host/emulator/realTS7/realiPhone evidence. Inspect exact release source,
APK permissions/ABI/assets/signature and downloaded checksums. Preserve GPL/AGPL
and third-party notices. Exclude non-distributable assets from APK without removing
the preserved upstream source. Independent review for high-impact deltas.
