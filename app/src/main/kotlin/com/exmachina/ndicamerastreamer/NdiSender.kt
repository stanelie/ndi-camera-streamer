package com.exmachina.ndicamerastreamer

import android.util.Log

private const val TAG = "NdiSender"

// The NDI Advanced SDK's development/trial license runs a stream for 30 minutes before the SDK
// silently stops actually delivering it to receivers (our own send calls keep "succeeding" —
// nothing on our side errors — but nothing reaches a connecting receiver either, indefinitely,
// until the process restarts with a fresh sender instance). Confirmed directly from the SDK's
// own stdout: "This version of the NDI Advanced SDK is designed for development use and will
// run on a stream for 30 minutes." Restarting the whole app was the only known workaround,
// because that's what created a fresh instance. This proactively recreates just the NDI sender
// — camera and encoder keep running the entire time — comfortably before the 30-minute mark, so
// the cutoff is never actually reached during normal operation. A commercial NDI vendor license
// (licensing@ndi.video) would remove the limit outright; this is the workaround until/unless
// that happens.
private const val PROACTIVE_RESTART_INTERVAL_MS = 28 * 60 * 1000L  // 28 min — tighter margin, chosen to minimize how many times a show is exposed to the swap's confirmed unpredictable reconnect gap

/**
 * Kotlin face of the JNI bridge in app/src/main/cpp/ndi_jni.cpp, which wraps libndi_advanced.so
 * (vendored from the NDI Advanced SDK for Android — see scripts/vendor_ndi_sdk.sh).
 *
 * This sends pre-compressed H.264 (NDI|HX) rather than raw frames — see CameraCapture.kt for
 * the MediaCodec hardware encoder that produces them. NDI itself does no compression in this
 * path; it just tunnels the elementary stream, which is what keeps this off the CPU/battery
 * budget standard NDI was hitting.
 *
 * One instance = one NDI video source. Call start() once, sendCompressedFrame() per encoded
 * access unit, stop() when done. The underlying native NDI instance is swapped out
 * transparently every [PROACTIVE_RESTART_INTERVAL_MS] (see above) — callers never see this;
 * [instancePtr] is read fresh on every call.
 */
class NdiSender {

    private external fun nativeCreate(sourceName: String, configJson: String?): Long
    private external fun nativeSendCompressedFrame(
        ptr: Long, width: Int, height: Int, fpsN: Int, fpsD: Int,
        isPreview: Boolean, isKeyframe: Boolean, ptsHns: Long,
        frame: ByteArray, frameOffset: Int, frameSize: Int,
        extra: ByteArray?, extraSize: Int
    )
    private external fun nativeIsKeyframeRequired(ptr: Long): Boolean
    private external fun nativeWaitForKeyframeRequest(ptr: Long, timeoutMs: Int): Boolean
    private external fun nativeDestroy(ptr: Long)

    @Volatile private var instancePtr: Long = 0
    @Volatile private var sourceName: String = ""
    @Volatile private var configJson: String? = null
    @Volatile private var running = false
    private var restartThread: Thread? = null

    /**
     * [machineName] overrides the "machine name" half of the advertised source name
     * (MACHINE_NAME (SOURCE_NAME)) via the Advanced SDK's per-instance JSON config. Required on
     * Android: gethostname() always returns the literal "localhost" here (there is no real
     * network hostname), which the NDI docs explicitly warn causes machine-name clashes on the
     * network — "incompatible with mDNS and can cause all other sources not to work correctly."
     * Confirmed directly: this device's NDI source browsed as "LOCALHOST (SM-G930W8 (NDI Camera
     * Test))", and a receiver could discover it instantly yet get zero video frames for up to
     * ~60s — consistent with mDNS resolvers that special-case "localhost" as always-loopback and
     * refuse to resolve it over multicast. Caller must pass something unique per physical device
     * (see MainActivity, which derives it from ANDROID_ID) — a duplicate machine name elsewhere
     * on the network reproduces the same class of bug this works around.
     */
    @Synchronized
    fun start(sourceName: String, machineName: String): Boolean {
        val json = """{"ndi":{"machinename":"$machineName"}}"""
        val ptr = nativeCreate(sourceName, json)
        if (ptr == 0L) {
            Log.e(TAG, "nativeCreate failed for '$sourceName'")
            return false
        }
        instancePtr = ptr
        this.sourceName = sourceName
        this.configJson = json
        running = true
        scheduleProactiveRestart()
        Log.i(TAG, "NDI|HX source '$sourceName' started (machine name '$machineName')")
        return true
    }

    private fun scheduleProactiveRestart() {
        val t = Thread({
            try {
                Thread.sleep(PROACTIVE_RESTART_INTERVAL_MS)
            } catch (e: InterruptedException) {
                return@Thread  // stop() cancelling this wakeup — nothing to do
            }
            restartInstance()
        }, "ndi-license-restart")
        t.isDaemon = true
        restartThread = t
        t.start()
    }

    /**
     * Recreates the NDI sender instance under the same source name. Tried pre-warming the
     * replacement *before* tearing down the current one (to have its listener/mDNS advertisement
     * already live the instant the old connection drops) — confirmed directly that the SDK
     * rejects it: `NDIlib_send_create_v2` fails outright with a second live instance under the
     * same name. So this is destroy-then-create instead: not gapless (there's a brief window,
     * bounded by the grace period below plus however fast the receiver reconnects, where no
     * sender exists at all), but it's the approach that actually works with this SDK, and it's
     * still far better than the alternative — a full app/camera restart the operator has to
     * notice and trigger manually.
     */
    private fun restartInstance() {
        val name: String
        val json: String?
        synchronized(this) {
            if (!running) return
            name = sourceName
            json = configJson
        }

        Log.w(TAG, "proactively recreating NDI sender instance ahead of the Advanced SDK's " +
                "30-minute trial limit — camera/encoder keep running, only the NDI connection resets")

        var oldPtr = 0L
        synchronized(this) {
            if (!running) return
            oldPtr = instancePtr
            instancePtr = 0  // no sends/keyframe-queries use the old instance from this point on
        }

        // Grace period before freeing the old instance, deliberately outside the lock above so
        // sendCompressedFrame() isn't blocked for its duration. isKeyframeRequired() and
        // waitForKeyframeRequest() are deliberately NOT synchronized with sendCompressedFrame
        // (so the keyframe thread's up-to-50ms native wait can never block the frame-send hot
        // path) — which means a call already in flight when instancePtr flips above may still
        // be holding the OLD pointer value. Both calls are bounded (the keyframe wait by its
        // own 50ms timeout; the others effectively instant), so waiting comfortably longer than
        // that guarantees nothing still references the old instance before it's freed.
        Thread.sleep(250)
        nativeDestroy(oldPtr)

        val newPtr = nativeCreate(name, json)
        if (newPtr == 0L) {
            Log.e(TAG, "proactive restart: nativeCreate failed after freeing the old instance " +
                    "— NDI source is temporarily down; will keep retrying on schedule")
            synchronized(this) { if (running) scheduleProactiveRestart() }
            return
        }
        synchronized(this) {
            if (!running) { nativeDestroy(newPtr); return }
            instancePtr = newPtr
        }
        Log.i(TAG, "NDI sender instance swapped")

        synchronized(this) { if (running) scheduleProactiveRestart() }
    }

    /**
     * [isPreview] selects the 640-wide preview stream's FourCC over the full-bandwidth one; an
     * NDI|HX source must publish both. [frame] is one H.264 access unit's VCL NAL bytes (Annex-B start codes, no SPS/PPS).
     * [extra] is SPS+PPS (Annex-B, concatenated) and required (non-null) exactly when
     * [isKeyframe] is true; pass null otherwise. [ptsHns] is the presentation timestamp in
     * 100ns units (NDI's native unit — see NDIlib_compressed_packet_t.pts).
     */
    // Serialized: the program and preview streams are drained on separate threads but share one
    // NDI sender instance, and the SDK makes no thread-safety promise for concurrent sends. Also
    // serializes against the ptr-swap moment in restartInstance() (see above).
    @Synchronized
    fun sendCompressedFrame(
        width: Int, height: Int, fps: Int,
        isPreview: Boolean, isKeyframe: Boolean, ptsHns: Long,
        frame: ByteArray, frameOffset: Int, frameSize: Int,
        extra: ByteArray?
    ) {
        val ptr = instancePtr
        if (ptr == 0L) return
        nativeSendCompressedFrame(
            ptr, width, height, fps, 1,
            isPreview, isKeyframe, ptsHns,
            frame, frameOffset, frameSize,
            extra, extra?.size ?: 0
        )
    }

    /** When true, force the next encoded frame to be an IDR. */
    fun isKeyframeRequired(): Boolean {
        val ptr = instancePtr
        return ptr != 0L && nativeIsKeyframeRequired(ptr)
    }

    /**
     * Blocks (up to [timeoutMs]) until NDI's keyframe requirement changes. Prefer this over
     * polling [isKeyframeRequired]: NDI|HX requires an I-frame within 100ms of being asked, and
     * the SDK validates that — a stream that misses the window is terminated as non-compliant,
     * which downstream shows up as receivers rendering a "video decoder not found" placeholder.
     */
    fun waitForKeyframeRequest(timeoutMs: Int): Boolean {
        val ptr = instancePtr
        return ptr != 0L && nativeWaitForKeyframeRequest(ptr, timeoutMs)
    }

    @Synchronized
    fun stop() {
        running = false
        restartThread?.interrupt()
        val ptr = instancePtr
        if (ptr == 0L) return
        instancePtr = 0
        nativeDestroy(ptr)
    }

    companion object {
        init {
            System.loadLibrary("ndi_jni")
        }
    }
}
