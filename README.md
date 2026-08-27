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

## Qualcomm ~1s latency (Pixel 5, and any Qualcomm device)

Feeding the camera straight into `MediaCodec.createInputSurface()` is the documented zero-copy
path, but on Qualcomm devices that surface's `USAGE_VIDEO_ENCODE` HardwareBuffer flag puts the
driver into a mode that holds ~1s of frames, independent of any MediaCodec/CaptureRequest
tuning — see [Google Issue Tracker #254027327](https://issuetracker.google.com/issues/254027327),
closed "Won't Fix (Obsolete)" with no platform fix. Confirmed directly on a Pixel 5.

Fixed the same way EpocCam-streamer already had it: on Qualcomm hardware the camera targets an
`ImageReader` instead, and each frame is forwarded to the encoder via `ImageWriter` — a zero-copy
GPU handoff that avoids the flag.

**Gated to Qualcomm on purpose.** The same path produces solid green frames on Exynos (seen
previously on a Samsung S6), so a version-only gate would break the S7 this app targets.
`IS_QUALCOMM` in `CameraCapture.kt` checks `Build.SOC_MANUFACTURER` (API 31+) and falls back to
`Build.HARDWARE` ("qcom") on older devices. Confirmed on both phones this app has actually run
on: S7 stays on the direct-Surface path (unaffected), Pixel 5 takes the ImageReader path
(latency fixed).

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

## Staying alive

A foreground service (`StreamingService`) keeps the stream running when the app is not in front.
Without it Android treats a backgrounded camera app as disposable and reclaims it — on a phone
left running for the length of a show, that means the feed vanishing unannounced.

Verified on the S7:

| Action | Result |
| --- | --- |
| Back | minimises (`moveTaskToBack`), keeps streaming |
| Home | keeps streaming, process alive |
| Screen off | keeps streaming |
| Swipe away in the task switcher | stops |

That last row is deliberate. `START_STICKY` gets the service restarted if the *system* kills it
under memory pressure, while `android:stopWithTask="true"` lets the *operator* stop it on
purpose. The aim is to survive accidents, not to be impossible to shut down.

Note the camera is owned by the Activity, not the service, which is fine on the target device
(API 26) and matches EpocCam-streamer. Android 9+ blocks background camera access for apps
without a camera-typed foreground service; the service is declared
`foregroundServiceType="camera"` for that reason, but if this is ever run on a newer device and
the feed stops on backgrounding, moving capture into the service is the fix.

## App pinning (unattended operation)

A LOCK button, lower-right, engages Android's screen pinning (lock-task mode) so a phone left
unattended for a show can't be backed out of the app by a stray touch, a notification-shade
swipe, or Recents. Ported from EpocCam-streamer, where the same control was needed for the same
reason.

Tap LOCK, confirm the one-time system "Turn on Pin windows" dialog (Android's own OS-level
consent, shown once per pinning session — not something the app can skip), and the badge
switches to **LOCKED**. While locked, `dispatchTouchEvent`/`onKeyDown`/`onKeyUp` swallow input
before it reaches the system as an extra layer on top of lock-task mode itself. To unlock: the
standard Android gesture (hold Back + Recents together), which is intentionally not something
the app can trigger from inside itself — that's the point of pinning.

Android has no callback for lock-task state changes, so `unlockPollRunnable` polls
`lockTaskModeState` every 500ms and flips the UI back once it sees `NONE`. Verified end to end
on the S7: engaging shows `PINNED` and suppresses Back; forcing lock-task off at the OS level
(`am task lock stop`) produces Android's "App unpinned" toast and the UI correctly reverts to
the LOCK button.

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

## The actual root cause of "won't reconnect": a 30-minute trial limit

After the async-buffer fix above and its own follow-up, Millumin still couldn't reconnect. The
real cause was hiding in a line of the SDK's own stdout, which had been read as boilerplate and
ignored:

```
This version of the NDI Advanced SDK is designed for development use and will run on a stream
for 30 minutes. For a commercial use license, please email licensing@ndi.video.
```

**The Advanced SDK's development license silently stops delivering a stream to receivers after
30 minutes** — the sender's own encode/send loop keeps reporting healthy throughput the entire
time (confirmed live, mid-failure), because nothing on the sending side actually errors. This
explains everything, including why the async-buffer fix didn't help: it's a licensing gate
inside Vizrt's closed library, unreachable by anything in this app's send code. "Restarting the
app fixes it" was never about clearing corrupted state; it fixes it because a fresh process
means a fresh `NDIlib_send_instance_t`, which resets the 30-minute timer.

**Two real fixes, not mutually exclusive:**
1. A commercial NDI vendor license (`licensing@ndi.video`) removes the limit outright.
2. Until/unless that happens: `NdiSender` proactively recreates just the NDI sender instance
   every 25 minutes — 5 minutes of margin under the SDK's cutoff — while the camera and encoder
   keep running the entire time. See `NdiSender.restartInstance()`.

### Why it isn't gapless

The natural ask was to pre-warm a second instance under the same name before tearing down the
first, so the new one's listener/mDNS advertisement is already live the instant the old
connection drops. Tried it, and confirmed directly that the SDK rejects it outright:
`NDIlib_send_create_v2` fails with a second live instance under the same source name. So the
swap is destroy-then-create instead — there's a brief real window (bounded by a 250ms grace
period plus however fast the receiver notices and reconnects) where no sender exists. Far
smaller than a full app/camera restart, but not literally zero.

The 250ms grace period before freeing the old instance exists because `isKeyframeRequired()`/
`waitForKeyframeRequest()` are deliberately not synchronized with `sendCompressedFrame()` (so
the keyframe thread's up-to-50ms native wait can never block the frame-send hot path) — a call
already in flight when the pointer swaps may still be holding the old instance handle for a
moment. The wait comfortably outlasts that.

Verified directly (with the interval temporarily shortened for testing): camera/encoder frame
throughput never paused across a swap, no crash, and the schedule correctly re-arms for
repeated cycles.

## Fixed: reconnect after Millumin disconnect stopped working

After running for a while, disconnecting Millumin and trying to reconnect would fail: the phone
kept encoding and sending frames normally (steady heartbeat in logcat, no errors), but a *new*
receiver connection got nothing — the exact same symptom as an earlier internal bug during
testing (see the git history around v0.1/v0.2), just now triggered by a normal Millumin
disconnect rather than repeatedly force-killing test tools.

Root cause: async sending. `NDIlib_send_send_video_async_v2` hands the SDK a pointer and returns
immediately; that buffer must stay valid until the SDK is done with it. This app assumed "done"
meant "after 3 more frames go out" and blindly rotated through 3 fixed buffers. The SDK's own
docs on asynchronous sending completions say otherwise: *"if a connection is stalled and holds
onto a buffer until the connection times-out"* — which is exactly what an ungracefully
disconnected receiver (closing the app, a dropped connection) looks like — the buffer can be held
far longer than a few frames' worth of rotation. The blind rotation could overwrite a buffer NDI
still referenced for that stale connection, corrupting what got sent and plausibly explaining why
new connections received nothing afterward.

Fixed per the SDK's documented mechanism: `NDIlib_send_set_video_async_completion` registers a
callback that fires exactly when each buffer is actually free. The fixed 3-slot array is now a
pool that grows on demand and only reuses a buffer once the SDK has confirmed it through that
callback — see `ndi_jni.cpp`.

Verified: sender stays healthy (steady encode/send throughput, no stalls) through repeated
connect/ungraceful-disconnect cycles, and reconnecting in Millumin after a real disconnect is
fast again.

## Dropped the preview stream: real thermal/CPU win, no functional loss

The NDI|HX docs describe publishing two streams — the full-bandwidth "program" stream and a
640-wide "preview" stream — and this app did that for a while. In hindsight that was added as a
*hypothesis* partway through the "video decoder not found" investigation, before the real cause
(missing Annex-B start codes, see above) was found. It never actually fixed anything, but stayed
in the code afterward without being re-tested in isolation.

Prompted by the phone running hot, it was re-tested: single-stream raises no complaint on NDI's
own stream-validation stdout, and decodes cleanly in both this SDK's own receiver tooling and
Millumin. Measured on the S7 (`/proc/<pid>/stat` sampling, 5s window):

| Process | Two streams | One stream |
| --- | --- | --- |
| `media.codec` (encoder driver overhead) | ~29% | ~17.6% |
| This app | ~37% | ~34% |
| `cameraserver` (ISP) | ~47% | ~47% (unchanged — the surviving 800x450 viewfinder stream is nearly as much ISP work as the removed preview stream was) |

Camera surface count also dropped from 3 to 2 (viewfinder + program), which matters separately:
Camera2 only strictly guarantees 2 concurrent outputs at LIMITED hardware level, so this is also
a robustness improvement, not just a thermal one.

If some other receiver ever turns out to need the preview stream, `EncoderStream` in
`CameraCapture.kt` still takes an `isPreview` flag — reintroducing it is adding a second instance
back into the `streams` list in `start()`, not rebuilding the mechanism.

## Fixed: Millumin sometimes slow to connect (up to ~60s, even on a fresh launch)

Root cause: **the advertised "machine name" was the literal string `"localhost"`.**

`gethostname()` on Android always returns `"localhost"` — there is no real per-device network
hostname (confirmed directly: `/proc/sys/kernel/hostname` reads `localhost` on the S7, and
`getprop net.hostname` is empty). The NDI SDK uses that as the machine-name half of the
advertised source name (`MACHINE_NAME (SOURCE_NAME)`), so this app's source browsed on the
network as `LOCALHOST (SM-G930W8 (NDI Camera Test))`.

That's not just a cosmetic problem. NDI's own docs warn that a machine-name clash "is
incompatible with mDNS and can cause all other sources not to work correctly," and `"localhost"`
is about the worst possible name to advertise under: it's a reserved word that many mDNS
resolvers (confirmed on macOS) special-case as always-meaning-loopback and refuse to resolve
over multicast like a normal name, regardless of what NDI itself actually published. Confirmed
directly with a throwaway NDI receiver (`gap_timer`, this repo's own test tooling): connecting to
the `LOCALHOST`-named source succeeded immediately (source found, connect call returned) but
delivered **zero video frames in 25 seconds** — "connected," but nothing arriving, exactly
matching the reported symptom. `dns-sd -B _ndi._tcp` also showed the phone's own mDNS
advertisement literally reading `LOCALHOST (...)`, confirming this wasn't receiver-side.

Fix: pass a JSON `p_config_data` string overriding `ndi.machinename` to something unique per
device, at `NDIlib_send_create_v2` — see `NdiSender.kt` (`start()`/`restartInstance()`, which
both now build and pass this) and `ndi_jni.cpp` (`nativeCreate`, which forwards it). The name is
derived in `MainActivity.kt` from `Build.MODEL` + the last 6 characters of `ANDROID_ID`
(sanitized to alnum/dash, since it's interpolated into a hand-built JSON literal) — stable across
app restarts on one device, and unique enough across devices to avoid the exact clash this bug
was caused by. Re-verified after the fix with the same receiver: connect-to-first-frame dropped
to ~3.5s (normal codec/keyframe startup), 466 frames delivered in a 20s window, no long stalls —
against 0 frames in 25s before.

This — not the 30-minute trial-limit swap gap (see above) — was the cause of the "won't connect
for a long time" reports that persisted even on fresh app launches with no swap involved.

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
