package com.exmachina.ndicamerastreamer

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaCodec as AndroidMediaCodec
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface
import android.view.SurfaceHolder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "CameraCapture"

// An NDI|HX source must publish two streams: the full-bandwidth "program" stream and a preview
// stream that the SDK docs require to be 640 pixels wide with square pixels. Height follows the
// program stream's aspect ratio (640x360 at 16:9).
private const val PREVIEW_STREAM_WIDTH = 640

/**
 * Captures camera frames via Camera2, hardware-encodes them to H.264 via MediaCodec, and hands
 * each encoded access unit to [NdiSender] as NDI|HX — no software video compression anywhere
 * in this app. That split (hardware encode, NDI just tunnels the bytes) is the whole point of
 * this design: standard NDI's software compressor was pegging ~2 CPU cores on this hardware
 * (measured directly — see README), which MediaCodec's encoder ASIC does for essentially free,
 * the same way EpocCam-streamer gets its ~0.1s latency.
 *
 * Two encoders run concurrently, one per required NDI|HX stream (see [EncoderStream]). Both are
 * fed by the same capture session, so the camera fans out to three surfaces: the on-screen
 * preview plus the two encoder input surfaces.
 *
 * The CaptureRequest tuning and MediaCodec settings below (ZSL off, CBR + KEY_LATENCY hint,
 * TEMPLATE_RECORD, direct camera→encoder Surface) mirror techniques EpocCam-streamer found
 * necessary on this camera hardware — written fresh for this pipeline, not copied. The direct
 * Surface path (no ImageReader/ImageWriter indirection) is deliberate: that workaround exists
 * for a Qualcomm HAL quirk and actively breaks Samsung/Exynos hardware, which is what this
 * phone (SM-G930W8, Exynos "hero" family) is.
 */
class CameraCapture(
    private val context: Context,
    @Volatile var previewHolder: SurfaceHolder?,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val ndiSender: NdiSender
) {
    private val running = AtomicBoolean(false)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    // Set once the HAL refuses screen-preview + both encoders together (3 concurrent camera
    // outputs is beyond what Camera2 guarantees at LIMITED/FULL level, and this phone rejects
    // it). The two NDI streams are the point of the app, so the on-screen preview is what gets
    // dropped; startCaptureSession then retries with encoders only.
    private val dropScreenPreview = AtomicBoolean(false)

    private val cameraThread = HandlerThread("ndi-camera").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)

    // Built in start(), once the camera's actually-supported output sizes are known — the
    // HX-spec 640x360 preview size is not universally available (this Exynos HAL rejects it
    // outright: "Invalid preview size(640x360)"), so the size is negotiated, not assumed.
    private lateinit var streams: List<EncoderStream>

    /**
     * Picks the preview-stream size: the smallest camera-supported size that matches the program
     * stream's aspect ratio and is at least [PREVIEW_STREAM_WIDTH] wide. The HX docs ask for 640
     * wide with square pixels; where the camera cannot produce exactly that, the nearest larger
     * matching size is the closest we can get while keeping the aspect ratio correct (a
     * different aspect ratio would distort the preview stream relative to the program stream).
     */
    private fun choosePreviewSize(characteristics: CameraCharacteristics): Pair<Int, Int> {
        val targetAspect = width.toDouble() / height
        val supported = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(AndroidMediaCodec::class.java)
            ?.toList()
            .orEmpty()
        val sameAspect = supported.filter {
            kotlin.math.abs(it.width.toDouble() / it.height - targetAspect) < 0.02
        }
        val pick = sameAspect.filter { it.width >= PREVIEW_STREAM_WIDTH }.minByOrNull { it.width }
            ?: sameAspect.maxByOrNull { it.width }
        if (pick == null) {
            Log.w(TAG, "no aspect-matching preview size; falling back to program size")
            return width to height
        }
        if (pick.width != PREVIEW_STREAM_WIDTH) {
            Log.w(TAG, "camera cannot output ${PREVIEW_STREAM_WIDTH}-wide preview; using ${pick.width}x${pick.height}")
        }
        return pick.width to pick.height
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!running.compareAndSet(false, true)) return
        Log.i(TAG, "start() program=${width}x${height}@${fps} bitrate=$bitrate")

        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: manager.cameraIdList[0]

        val (pw, ph) = choosePreviewSize(manager.getCameraCharacteristics(cameraId))
        streams = listOf(
            EncoderStream("program", width, height, bitrate, isPreview = false),
            EncoderStream(
                "preview", pw, ph,
                // Bitrate scaled by pixel count relative to the program stream.
                (bitrate.toLong() * pw * ph / (width.toLong() * height))
                    .toInt().coerceAtLeast(300_000),
                isPreview = true
            )
        )
        Log.i(TAG, "streams: program=${width}x${height}@${bitrate} preview=${pw}x${ph}@${streams[1].bitrate}")

        streams.forEach { it.create() }
        streams.forEach { it.drainThread.start() }
        keyframeThread.start()

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (!running.get()) { camera.close(); return }
                cameraDevice = camera
                startCaptureSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                Log.w(TAG, "camera onDisconnected")
                camera.close(); cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                Log.e(TAG, "camera onError $error"); camera.close(); cameraDevice = null
            }
        }, cameraHandler)
    }

    private fun startCaptureSession(camera: CameraDevice) {
        val preview = if (dropScreenPreview.get()) null else previewHolder?.surface
        val encoderSurfaces = if (::streams.isInitialized) streams.mapNotNull { it.inputSurface } else emptyList()
        val surfaces = listOfNotNull(preview) + encoderSurfaces
        try {
            camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!running.get()) { session.close(); return }
                    captureSession = session
                    try {
                        session.setRepeatingRequest(
                            buildRequest(camera, preview, encoderSurfaces), null, cameraHandler)
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "onConfigured: session superseded: $e")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "capture session config FAILED (${surfaces.size} surfaces)")
                    // Retry once without the on-screen preview: two encoder streams alone are
                    // within the guaranteed Camera2 configurations, and the NDI output matters
                    // more here than the local viewfinder.
                    if (preview != null && dropScreenPreview.compareAndSet(false, true)) {
                        Log.w(TAG, "retrying without on-screen preview (encoders only)")
                        cameraHandler.post { if (running.get()) startCaptureSession(camera) }
                    }
                }
            }, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "startCaptureSession: camera disconnected: $e")
        }
    }

    private fun buildRequest(camera: CameraDevice, preview: Surface?, encoders: List<Surface>) =
        camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            preview?.let { addTarget(it) }
            encoders.forEach { addTarget(it) }
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
            set(CaptureRequest.CONTROL_ENABLE_ZSL, false)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
        }.build()

    fun updatePreview(holder: SurfaceHolder?) {
        val wasNull = previewHolder == null
        previewHolder = holder
        val cam = cameraDevice ?: return
        if (wasNull && holder == null) return
        captureSession?.close()
        captureSession = null
        startCaptureSession(cam)
    }

    // NDI tells us when a receiver needs a fresh IDR (new connection, detected packet loss, ...)
    // and NDI|HX requires us to deliver it within 100ms. The SDK actively validates this and
    // terminates a stream that misses the window — which is what "I-Frame insertion must occur
    // in less than 100 ms to be NDI|HX compliant" on the SDK's stdout means. So this is
    // event-driven via wait_for_keyframe_request rather than polled: a poll interval alone can
    // burn most or all of the 100ms budget before the encoders are even asked. Both streams get
    // the keyframe, since either may be the one a receiver is watching.
    private val keyframeThread = Thread({
        while (running.get()) {
            ndiSender.waitForKeyframeRequest(50)  // wakes as soon as the requirement changes
            if (!running.get()) break
            if (ndiSender.isKeyframeRequired()) streams.forEach { it.requestSyncFrame() }  // both streams
        }
    }, "ndi-keyframe")

    fun stop() {
        Log.i(TAG, "stop()")
        running.set(false)
        captureSession?.close(); captureSession = null
        cameraDevice?.close(); cameraDevice = null
        if (::streams.isInitialized) streams.forEach { it.release() }
        cameraThread.quitSafely()
    }

    /**
     * One MediaCodec H.264 encoder plus the drain loop that turns its output into NDI|HX frames.
     * Each instance owns its own SPS/PPS state, since the two streams have different resolutions
     * and therefore different parameter sets.
     */
    private inner class EncoderStream(
        val label: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val isPreview: Boolean
    ) {
        private var codec: MediaCodec? = null
        var inputSurface: Surface? = null
            private set

        // Accumulated SPS+PPS (Annex-B, 4-byte start codes), refreshed whenever the encoder emits
        // new config NALs. Sent as NDIlib_compressed_packet_t's extra_data on every keyframe — NDI
        // requires this on each IDR, not just the first (a receiver joining mid-stream only has
        // something decodable starting from a keyframe).
        private var configBytes: ByteArray? = null

        private var frameCount = 0
        private var byteCount = 0L
        private var lastHeartbeatMs = 0L
        private var lastPtsUs = Long.MIN_VALUE
        private var reorderWarnings = 0

        val drainThread = Thread({ drainLoop() }, "ndi-drain-$label")

        fun create() {
            val format = MediaFormat.createVideoFormat("video/avc", width, height).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                // NDI requests keyframes on demand (see keyframeThread), but a bounded interval
                // is still a useful floor.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // Main rather than Baseline: Main allows CABAC (~10-15% better coding efficiency
                // than Baseline's CAVLC). Main also *permits* B-frames, which would cost a frame
                // of decoder reordering delay — but the drain loop watches output PTS ordering
                // and warns if any appear, and none do on this encoder. If that warning ever
                // fires on other hardware, drop to AVCProfileBaseline (which cannot emit them)
                // or set KEY_MAX_B_FRAMES=0 where API 29+ is available.
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileMain)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                // B-frames force the decoder to reorder, adding at least a frame of display
                // delay. NDI's own guidance is I and P only for low latency. This key needs
                // API 29; below that, the Baseline profile above is what rules them out.
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                }
            }
            val enc = MediaCodec.createEncoderByType("video/avc")
            applyLowLatencyHints(format, enc, label)
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = enc.createInputSurface()
            enc.start()
            codec = enc
        }

        fun requestSyncFrame() {
            try {
                codec?.setParameters(Bundle().apply {
                    putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                })
            } catch (e: Exception) {
                Log.w(TAG, "[$label] requestSyncFrame failed: $e")
            }
        }

        private fun drainLoop() {
            val info = MediaCodec.BufferInfo()
            val enc = codec ?: return
            val vclOut = ByteArrayOutputStream(64 * 1024)

            while (running.get()) {
                val idx = try {
                    enc.dequeueOutputBuffer(info, 10_000L)
                } catch (e: Exception) {
                    break  // encoder stopped from another thread
                }
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = enc.outputFormat
                    val lowLatencyState = LOW_LATENCY_VENDOR_KEYS
                        .mapNotNull { k -> runCatching { "$k=${fmt.getInteger(k)}" }.getOrNull() }
                        .joinToString(" ")
                        .ifEmpty { "(codec reports no vendor low-latency key)" }
                    Log.w(TAG, "[$label] output format: " +
                            "${fmt.getInteger(MediaFormat.KEY_WIDTH)}x${fmt.getInteger(MediaFormat.KEY_HEIGHT)}" +
                            "  $lowLatencyState")
                    continue
                }
                if (idx < 0) continue

                if (info.presentationTimeUs < lastPtsUs && reorderWarnings < 3) {
                    reorderWarnings++
                    Log.w(TAG, "[$label] OUT-OF-ORDER PTS (${info.presentationTimeUs} < $lastPtsUs)" +
                            " — encoder is emitting B-frames, which add decode delay")
                }
                lastPtsUs = info.presentationTimeUs

                val buf = enc.getOutputBuffer(idx)
                if (buf == null) { enc.releaseOutputBuffer(idx, false); continue }
                buf.position(info.offset); buf.limit(info.offset + info.size)
                val data = ByteArray(info.size)
                buf.get(data)
                enc.releaseOutputBuffer(idx, false)

                vclOut.reset()
                var sawIdr = false
                var sawVcl = false

                for (nal in splitNalUnits(data, 0, data.size)) {
                    when (nal.type) {
                        7, 8 -> appendConfig(data, nal.start, nal.end)   // SPS / PPS
                        5 -> { sawIdr = true; sawVcl = true; writeAnnexB(vclOut, data, nal) }
                        1 -> { sawVcl = true; writeAnnexB(vclOut, data, nal) }
                        6, 9, 12 -> {}  // SEI / AUD / filler — drop
                        else -> {}
                    }
                }
                if (!sawVcl) continue

                val frameBytes = vclOut.toByteArray()
                val extra = if (sawIdr) configBytes else null
                if (sawIdr && extra == null) {
                    Log.w(TAG, "[$label] IDR with no SPS/PPS yet — sending as non-keyframe")
                }
                val isKeyframe = sawIdr && extra != null
                ndiSender.sendCompressedFrame(
                    width, height, fps, isPreview, isKeyframe, info.presentationTimeUs * 10L,
                    frameBytes, 0, frameBytes.size,
                    if (isKeyframe) extra else null
                )

                frameCount++
                byteCount += frameBytes.size
                val now = android.os.SystemClock.uptimeMillis()
                // presentationTimeUs is the camera buffer's own timestamp carried through the
                // input Surface, so diffing it against the clock here isolates capture+encode
                // latency from anything downstream (NDI, network, receiver). Camera timestamps
                // are elapsedRealtime-based on most devices and uptime-based on some, so report
                // both and read whichever is plausible.
                if (now - lastHeartbeatMs >= 2000) {
                    val nowRealtimeUs = android.os.SystemClock.elapsedRealtimeNanos() / 1000
                    val nowUptimeUs = now * 1000
                    Log.w(TAG, "[$label] PIPE LATENCY vsRealtime=" +
                            "${(nowRealtimeUs - info.presentationTimeUs) / 1000}ms " +
                            "vsUptime=${(nowUptimeUs - info.presentationTimeUs) / 1000}ms")
                    val mbps = (byteCount * 8) / 1_000_000.0 /
                            ((now - lastHeartbeatMs).coerceAtLeast(1) / 1000.0)
                    Log.w(TAG, "[$label] $frameCount frames/2s, ~${"%.2f".format(mbps)} Mbps")
                    frameCount = 0; byteCount = 0; lastHeartbeatMs = now
                }
            }
        }

        /**
         * Appends one NAL to [out] **with its 4-byte Annex-B start code**. NalRange.start points
         * past the start code (that's what makes the type byte easy to read), so writing the
         * range alone would emit a bare NAL body — and NDI requires the opposite: "NDI assumes
         * that all H.264 data is as specified in Annex B... The data must include the start
         * codes." Sending start-code-less payloads is what made every receiver report "video
         * decoder not found" while the same frames decoded fine in VideoToolbox/VLC (those were
         * tested against a raw encoder dump, which still had its start codes).
         */
        private fun writeAnnexB(out: ByteArrayOutputStream, data: ByteArray, nal: NalRange) {
            out.write(0); out.write(0); out.write(0); out.write(1)
            out.write(data, nal.start, nal.end - nal.start)
        }

        private fun appendConfig(data: ByteArray, start: Int, end: Int) {
            // Normalize to a 4-byte start code (matches the NDI SDK's own H.264 example data) and
            // accumulate — SPS then PPS arrive as separate NALs but belong together as one
            // extra_data blob. A fresh SPS restarts the blob; a PPS following it appends.
            val nalBody = data.copyOfRange(start, end)
            val withStartCode = byteArrayOf(0, 0, 0, 1) + nalBody
            configBytes = if (nalBody.isNotEmpty() && (nalBody[0].toInt() and 0x1F) == 7) {
                withStartCode
            } else {
                (configBytes ?: ByteArray(0)) + withStartCode
            }
        }

        fun release() {
            inputSurface?.release(); inputSurface = null
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            codec = null
        }
    }
}

// Vendor-specific low-latency encoder switches, by SoC family. MediaCodec ignores keys a codec
// does not recognise, so setting all of them is safe; on API 31+ we can ask the codec which it
// actually supports and set only those.
private val LOW_LATENCY_VENDOR_KEYS = listOf(
    "vendor.qti-ext-enc-low-latency.enable",   // Qualcomm Snapdragon (most Pixels, incl. Pixel 5)
    "vendor.rtc-ext-enc-low-latency.enable",   // Samsung Exynos (this S7; also Tensor-derived parts)
    "vendor.low-latency.enable"                // generic spelling seen on some other vendors
)

/**
 * Applies every low-latency encoder hint that applies to the device actually running the app.
 *
 * There are two layers, because neither alone covers the fleet:
 *  - [MediaFormat.KEY_LOW_LATENCY]/[MediaFormat.KEY_LATENCY] are the standard, vendor-neutral
 *    switches, but only exist from API 30. On older devices (this S7 is API 26) they are inert,
 *    which is why setting them alone silently did nothing here.
 *  - The vendor keys cover those older devices, but are SoC-specific: the Qualcomm key does
 *    nothing on Exynos and vice versa. We set all known spellings rather than branching on
 *    Build.HARDWARE, since an unrecognised key is ignored and the SoC-detection approach is
 *    exactly the kind of thing that silently breaks on the next device.
 */
private fun applyLowLatencyHints(format: MediaFormat, codec: MediaCodec, label: String) {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
        format.setInteger(MediaFormat.KEY_LATENCY, 1)
        format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
    }

    val keys = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        val supported = runCatching { codec.supportedVendorParameters }.getOrDefault(emptyList())
        LOW_LATENCY_VENDOR_KEYS.filter { it in supported }
    } else {
        LOW_LATENCY_VENDOR_KEYS
    }
    keys.forEach { format.setInteger(it, 1) }
    Log.i(TAG, "[$label] low-latency hints: api=${android.os.Build.VERSION.SDK_INT} " +
            "standardKeys=${android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R} " +
            "vendorKeys=$keys")
}

private class NalRange(val type: Int, val start: Int, val end: Int)

/** Scans Annex-B data for start codes (3- or 4-byte) and returns each NAL's type + byte range
 *  (range starts right after the start code, i.e. at the NAL header byte). */
private fun splitNalUnits(data: ByteArray, offset: Int, size: Int): List<NalRange> {
    val end = offset + size
    val result = mutableListOf<Pair<Int, Int>>()
    var i = offset
    var nalStart = -1
    while (i < end - 3) {
        val is3 = data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()
        val is4 = i + 3 < end && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()
        if (is3 || is4) {
            if (nalStart >= 0) result.add(nalStart to i)
            nalStart = i + if (is4) 4 else 3
            i += if (is4) 4 else 3
        } else i++
    }
    if (nalStart >= 0) result.add(nalStart to end)

    return result.mapNotNull { (s, e) ->
        if (s >= e) null else NalRange(data[s].toInt() and 0x1F, s, e)
    }
}
