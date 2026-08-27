package com.exmachina.ndicamerastreamer

import android.util.Log

private const val TAG = "NdiSender"

// The NDI Advanced SDK's development/trial license silently stops delivering a stream to
// receivers after 5 minutes on mobile platforms (documented at
// docs.ndi.video/all/developing-with-ndi/sdk/licensing, and confirmed on this device to
// sub-second precision — see README). Our own send calls keep "succeeding" the whole time —
// nothing on our side errors — but nothing reaches a connecting receiver either, until a fresh
// app process starts (confirmed: an app restart alone resets it, no reboot required).
//
// An earlier revision proactively recreated the NDI sender instance every 28 minutes, on the
// wrong belief that the limit was ~30 minutes and per-instance. Removed — it was built on a
// wrong model and could not have worked, and there's a real license question inherent in any
// code whose purpose is to reset this timer before it fires. A commercial NDI vendor license
// (licensing@ndi.video) is the actual fix.
//
// Confirmed directly (destroy+recreate in place, same process, camera/encoder untouched, then
// removed once answered — see git history) that recreating just the instance does NOT reset the
// clock: a receiver got 0 frames both immediately after and ~35s after such a recreate. Only a
// full app restart (new process) has ever been observed to reset it.

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
 * access unit, stop() when done.
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
        Log.i(TAG, "NDI|HX source '$sourceName' started (machine name '$machineName')")
        return true
    }

    /**
     * [isPreview] selects the 640-wide preview stream's FourCC over the full-bandwidth one; an
     * NDI|HX source must publish both. [frame] is one H.264 access unit's VCL NAL bytes (Annex-B start codes, no SPS/PPS).
     * [extra] is SPS+PPS (Annex-B, concatenated) and required (non-null) exactly when
     * [isKeyframe] is true; pass null otherwise. [ptsHns] is the presentation timestamp in
     * 100ns units (NDI's native unit — see NDIlib_compressed_packet_t.pts).
     */
    // Serialized: the program and preview streams are drained on separate threads but share one
    // NDI sender instance, and the SDK makes no thread-safety promise for concurrent sends.
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
        val ptr = instancePtr
        if (ptr == 0L) return
        instancePtr = 0
        nativeDestroy(ptr)
    }

    companion object {
        init {
            System.loadLibrary("ndi_jni")
        }

        /**
         * Set by MainActivity to get notified when the NDI Advanced SDK prints its trial/license
         * warning to stdout (see ndi_jni.cpp's stdoutPumpLoop) — called from an attached native
         * pthread, NOT the main thread, so listeners must post to the UI thread themselves.
         */
        @JvmStatic
        var warningListener: ((String) -> Unit)? = null

        /** Called from native code (ndi_jni.cpp's notifyJavaOfWarning) — do not rename/remove
         *  without updating the JNI GetStaticMethodID lookup there. */
        @JvmStatic
        fun onNativeWarning(message: String) {
            Log.w(TAG, "SDK warning surfaced to UI: $message")
            warningListener?.invoke(message)
        }
    }
}
