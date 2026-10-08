# R1 synthetic media fixture

`assets/ts7-baseline/red.h264.base64` was generated for this test on 2026-10-08
from FFmpeg's solid-red color source, not a phone capture, donor image, Lite
receiver or hardware report. The 1320 decoded bytes are 12 all-intra AVC baseline
frames at 160x96, 10fps. SHA256:
`62a054a4e4f667f7c95a0dd774f7a81caa0e8f1145c4b964e017cf80f007cc54`.
FFmpeg 8.1.1 / libx264 core165 r3222 b35605a produced the committed bytes.
The original synthetic clip and test code are provided under GPL-3.0-only.

Reproduction command (stdout only, no existing file is overwritten):

```sh
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=red:s=160x96:r=10 \
  -frames:v 12 -an -c:v libx264 -profile:v baseline -level:v 3.0 \
  -pix_fmt yuv420p -threads 1 -tune zerolatency \
  -x264-params keyint=1:min-keyint=1:scenecut=0:repeat-headers=1:aud=1 \
  -bsf:v filter_units=remove_types=6 -f h264 pipe:1 | base64
```

Encoder versions can change the bytes; the pinned fixture digest, not a new
encoding, defines the test. The source set packages the fixture, runner and EGL
consumer only into the Android test APK. The public install APK checker rejects
their presence. Synthetic rendering establishes only the emulator MediaCodec /
Surface lifecycle, never phone authentication, protocol acceptance or TS7 silicon
performance. Both native ELF libraries are loaded without calling their exports.

Parent integration: the mobile defaultConfig runner must be
`com.shilapi.xcertplay.baseline.BaselineInstrumentation`, with
`testBuildType = "baseline"`. No additional dependency is needed. Gradle checks
use one worker and disable configuration caching for stable lint, without
changing the upstream build files. CI uses a real
API27 x86_64 AVD under Ubuntu KVM. The runner fails on another API or physical
device and requires all eight fixed checks to pass. The controller uses missing
local authentication and no paired phone. The hotspot preflight is not triggered
by instrumentation.
