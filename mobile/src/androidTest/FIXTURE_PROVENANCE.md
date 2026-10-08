# R1 synthetic media fixture

`assets/ts7-baseline/red.h264.base64` was generated for this test on 2026-10-08
from FFmpeg's solid-red color source, not a phone capture, donor image, Lite
receiver or hardware report. The 276 decoded bytes are 12 AVC constrained-baseline
frames at 160x96, 10fps: one IDR followed by eleven dependent P pictures, no
B pictures, one reference frame, and declared `max_dec_frame_buffering=1`. SHA256:
`167967c78c074f7e3fc9335d9374c359703da9f0e8e64cea328867e8521d7f9e`.
FFmpeg 8.1.1 / libx264 core165 r3222 b35605a produced the committed bytes.
The original synthetic clip and test code are provided under GPL-3.0-only.

Reproduction command (stdout only, no existing file is overwritten):

```sh
ffmpeg -hide_banner -loglevel error -f lavfi -i color=c=red:s=160x96:r=10 \
  -frames:v 12 -an -c:v libx264 -profile:v baseline -level:v 3.0 \
  -pix_fmt yuv420p -threads 1 -tune zerolatency \
  -x264-params keyint=12:min-keyint=12:ref=1:bframes=0:scenecut=0:repeat-headers=1:aud=1 \
  -bsf:v filter_units=remove_types=6 -f h264 pipe:1 | base64
```

Encoder versions can change the bytes; the pinned fixture digest, not a new
encoding, defines the test. The source set packages the fixture, runner and EGL
consumer only into the Android test APK. The public install APK checker rejects
their presence. Synthetic rendering establishes only the emulator MediaCodec /
Surface lifecycle, never phone authentication, protocol acceptance or TS7 silicon
performance. Both native ELF libraries are loaded without calling their exports.

The loader preserves each encoder-produced access unit (first AUD/SPS/PPS/IDR,
then AUD/P) when converting it to length-prefixed input. It does not drop encoded
frame delimiters or in-band parameter sets. The actual upstream conversion must
recognize the first random-access frame and subsequent dependent pictures. The
original decoder and reference-chain handling, at least three new callbacks and
three red pixels per lifecycle phase, and all eight emulator gates are unchanged.

## Preserved all-intra diagnostic case

`assets/ts7-baseline/red-all-intra-zero-dpb.h264.base64` preserves the former 1320
bytes unchanged, SHA256
`62a054a4e4f667f7c95a0dd774f7a81caa0e8f1145c4b964e017cf80f007cc54`.
Its reproduction command is the same except
`keyint=1:min-keyint=1:scenecut=0:repeat-headers=1:aud=1`. It declares zero reference
frames and zero decoded-picture buffering.

Actual API27 CI at `3ed77ef50e972c2c550c13898f650550b4641fb9` accepted twelve inputs
in matched real platform Surface and ByteBuffer controls but returned zero output
buffers. Original sink also queued input, with no pending jobs or reported errors.
Neither control forgave the original failing Surface gate; no R1 was published.

An independent host build of unmodified AOSP Android8.1 libavc
`5125a41d888a5b2e68d5e071cb5283e6b6eab235` reproduced zero streaming frames, two
flush-only frames and ten native `0x461` unavailable-picture-buffer errors in
display-order mode. Changing only native output order yielded twelve frames
matching FFmpeg byte-for-byte. Android8.1 SoftAVC selects display order; its wrapper
does not report this particular native error as an OMX exception. Host reference
evidence does not identify the emulator binary revision or prove Surface output.
The case is retained as a known decoder/fixture limitation, not silently marked
supported, removed, or used as phone evidence. The receiver's native output mode
and MediaCodec algorithm are not changed to accommodate this diagnostic clip.

The normal-GOP replacement tests streaming keyframe and dependent-picture
handling. A paired run of the same AOSP pin in unchanged display-order mode
produced ten streaming frames and two flush frames, with all twelve picture calls
returning `0/0`. Both the original reference test and complete-AU/CSD probe output
276480 YUV420P bytes, exactly matching FFmpeg's twelve decoded frames. All 328
native source files matched the pinned archive. This is host fixture validation;
actual Android Surface acceptance still requires the unchanged gates. It does
not justify changing production native output order or dropping a decoder gate.

Parent integration: the mobile defaultConfig runner must be
`com.shilapi.xcertplay.baseline.BaselineInstrumentation`, with
`testBuildType = "baseline"`. No additional dependency is needed. Gradle checks
use one worker and disable configuration caching for stable lint, without
changing the upstream build files. CI uses a real
API27 x86_64 AVD under Ubuntu KVM. The runner fails on another API or physical
device and requires all eight fixed checks to pass. The controller uses missing
local authentication and no paired phone. The hotspot preflight is not triggered
by instrumentation.
