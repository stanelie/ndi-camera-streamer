# ndi-camera-streamer

Test app: stream an Android phone's camera over NDI, for use with Millumin or any other
NDI receiver. Built to see whether a direct NDI pipeline can beat the official NDI HX Camera
app's latency, the way [EpocCam-streamer](../EpocCam-streamer) beat EpocCam's.

Deliberately separate from EpocCam-streamer — no shared code, no shared risk to that
show-critical app. A few *techniques* were carried over (see comments in the source), but
nothing is copy-pasted wholesale.

## Pipeline history (why it looks like this)

1. **v1 — standard NDI, raw NV12.** Camera2 → raw NV12 → `NDIlib_send_send_video_v2`, letting
   NDI's own software compressor do the work. Measured on-device: ~143 Mbps on the wire and
   181% CPU just for compression — this phone's old Exynos silicon can't keep up with NDI's
   software codec at 720p30, which is what caused the ~1s latency and stutter, independent of
   Wi-Fi quality (confirmed with a dedicated AP/clear channel — still stuttered).
2. **v2 — NDI|HX, hardware H.264 (current).** Camera2 → MediaCodec (hardware H.264 encoder) →
   NDI|HX compressed send. NDI does no compression in this path; it just tunnels MediaCodec's
   Annex-B output over the wire, prefixed with an `NDIlib_compressed_packet_t` header. Same
   ASIC EpocCam-streamer uses for its ~0.1s latency — the goal here is to see whether NDI's
   transport can match that once the CPU-bound software codec is out of the picture.

Requires the **Advanced SDK** (application/approval via ndi.video) — the standard SDK doesn't
expose the compressed-frame API at all.

## One-time setup

1. Install the NDI Advanced SDK for Android and NDI Advanced SDK for Apple (already done on
   this machine).
2. Vendor the SDK into this project (headers + prebuilt `.so`s aren't committed to git —
   proprietary, not ours to redistribute):
   ```bash
   ./scripts/vendor_ndi_sdk.sh
   ```
3. Build:
   ```bash
   ./gradlew assembleDebug
   ```

## Verifying the stream

With NDI Tools installed on this Mac (from the NDI SDK for Apple / NDI Tools), open **NDI
Studio Monitor** to watch the phone's source directly — faster iteration than routing through
Millumin for every test.

## Root cause of the long "video decoder not found" hunt

Receivers rendered an NDI-branded "Video decoder not found" placeholder instead of video. The
cause was in **this app**: `splitNalUnits` reports each NAL's offset *past* its Annex-B start
code, and the drain loop wrote from that offset — so every frame went out as bare NAL bodies
with the `00 00 00 01` delimiters stripped. NDI's spec is explicit: "NDI assumes that all H.264
data is as specified in Annex B... The data must include the start codes." The SPS/PPS extra
data was unaffected because start codes are prepended there by hand.

Things that were investigated and are **not** the cause — recorded so they are not re-chased:

- The H.264 itself. It decodes in VideoToolbox and VLC. That test was misleading: it ran
  against a raw MediaCodec dump taken *before* NAL splitting, so those bytes still had their
  start codes. It validated the encoder, not what we transmit.
- Millumin, the SDK generation (v5/v6), `recv_create_v3` vs `v4`, colour format, vendor IDs,
  NDI Tools, and the macOS version. A Mac on macOS 12.7.6 decodes NDI|HX correctly — proven by
  building the Advanced SDK's own `NDIlib_Send_H264` reference sender and receiving it locally.
  **Build that reference sender first** when a receiver misbehaves; it separates "our sender is
  broken" from "this receiver cannot decode" in minutes.
- Note a genuinely useful diagnostic found along the way: the SDK reports HX stream-validation
  failures on **stdout**, which Android discards. `redirectStdioToLogcat` in `ndi_jni.cpp` pipes
  it into logcat, which is how the I-frame timing violation below was found. Keep it.

## Operator controls

A focus-mode button sits top-right on the phone:

- **AF** — continuous autofocus; the HAL refocuses on its own.
- **MF** — tap-to-focus. Pressing the button runs one autofocus sweep and locks the lens there;
  tapping anywhere on the preview re-locks at that moment. **MF?** means the sweep finished but
  the HAL did not consider the result in focus. Pressing the button again returns to AF.

Two behaviours are deliberate, both carried over from EpocCam-streamer where they were needed on
this class of camera HAL: resuming continuous AF issues an explicit `CONTROL_AF_TRIGGER_CANCEL`
first (merely setting the mode back leaves the lens parked at its locked position), and a second
tap is ignored while the previous sweep is still converging (it would otherwise cancel the
in-flight attempt, so focus could appear never to lock). A 4s timeout keeps the control from
sticking if the HAL never reports a final AF state.

## Latency

Glass-to-glass measured in Millumin, on a Galaxy S7 (Exynos, API 26).

- **Capture → encoder output: ~100ms**, measured on-device via the camera buffer timestamp
  (`PIPE LATENCY` in logcat). This is a hard floor on this hardware — unchanged across
  `clock_video=false`, Baseline profile, B-frame suppression, Samsung's vendor low-latency
  switch, and shrinking the viewfinder stream. It is sensor/ISP pipeline depth, not encoding.
- **Everything beyond that** is NDI transport plus receiver-side display buffering, which the
  sender cannot influence.

What actually reduced end-to-end latency was **`clock_video=false`**: with clocking enabled NDI
rate-limits sends to the declared frame rate, deliberately holding frames back. It is meant for
sources without their own clock; a camera already has one.

Capture is capped at 30fps — the sensor offers no 60fps AE range, and high-speed configs cannot
run three concurrent outputs.

### Low-latency encoder hints

`applyLowLatencyHints` in `CameraCapture.kt` handles this across devices, and needs to stay that
way: `KEY_LOW_LATENCY`/`KEY_LATENCY` are the standard switches but only exist from API 30, so on
this API 26 device they are inert; the vendor keys cover older hardware but are SoC-specific
(the Qualcomm key does nothing on Exynos and vice versa). All known spellings are set rather
than branching on `Build.HARDWARE`, since unrecognised keys are ignored and SoC detection is
what silently breaks on the next device. On API 31+ the codec is asked which it supports.

## Status

Working end to end: the S7 streams NDI|HX to Millumin at 30fps, with the operator viewfinder
live on the phone. Program 1280x720 plus the HX-required preview stream (800x450 — the camera
rejects the spec's 640x360, so the size is negotiated from the camera's supported list at
runtime). Hardware H.264 encode costs ~37% of one core, against ~181% and ~143 Mbps when
standard NDI's software compressor was doing the work.

The encoder uses **Main** profile. Both profiles were measured end to end in Millumin:

| | Baseline | Main (chosen) |
|---|---|---|
| Bitrate at the same quality ceiling | ~3.3 Mbps | ~2.1-2.7 Mbps |
| Capture -> encode | ~100ms | ~99ms |
| Glass-to-glass in Millumin | no perceptible difference | no perceptible difference |

Latency is indistinguishable, so the decision came down to bandwidth: Main's CABAC entropy
coding reaches the same quality (both hit the encoder's min-QP floor) for roughly 25% fewer
bits. Bitrate lands under the 3.5 Mbps `BITRATE` target for that reason, not because the target
is being missed; raise it in `MainActivity.kt` only if the picture actually needs more bits.

Main *permits* B-frames, which would add a frame of decoder reordering delay, but this encoder
emits none — verified by watching output PTS ordering. That check is permanent: `OUT-OF-ORDER
PTS` in logcat means another device's encoder is reordering, and the fix is Baseline profile
(which cannot emit them) or `KEY_MAX_B_FRAMES=0` on API 29+.

Note that `PIPE LATENCY` only covers capture to encoder output on the phone. Decode cost lands
on the receiver and is invisible to it, so profile changes must be judged end to end, not from
that number alone.

Video only, no audio yet. Bitrate is fixed at 3.5 Mbps CBR (`MainActivity.kt`), matching
EpocCam-streamer's HD target — NDI|HX's FourCC tag ("highest_bandwidth" vs
"lowest_bandwidth") is a receiver-facing quality-tier hint, not a bitrate control; the actual
bitrate is entirely MediaCodec's CBR setting. Next: re-measure CPU/battery and Millumin
latency against this path.
