package com.exmachina.ndicamerastreamer

import android.util.Log

private const val TAG = "NdiSender"

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

    private external fun nativeCreate(sourceName: String): Long
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

    fun start(sourceName: String): Boolean {
        val ptr = nativeCreate(sourceName)
        if (ptr == 0L) {
            Log.e(TAG, "nativeCreate failed for '$sourceName'")
            return false
        }
        instancePtr = ptr
        Log.i(TAG, "NDI|HX source '$sourceName' started")
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

    fun stop() {
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
