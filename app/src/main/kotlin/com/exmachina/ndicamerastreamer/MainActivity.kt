package com.exmachina.ndicamerastreamer

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

private const val TAG = "MainActivity"
private const val WIDTH = 1280
private const val HEIGHT = 720
private const val FPS = 30
// Matches EpocCam-streamer's HD bitrate target — this is what actually controls the wire
// bitrate now (NDI|HX just tunnels whatever MediaCodec produces; see CameraCapture.kt).
private const val BITRATE = 3_500_000
// The on-screen viewfinder is a third camera output alongside the two NDI encoder streams, and
// left to itself a match_parent SurfaceView asks the camera for a full display-sized buffer
// (1920x1080 here — bigger than the program stream, to drive a 5" panel). Pinning it to a small
// camera-supported 16:9 size cuts that stream's ISP/memory cost by ~80% and reduces contention
// with the encoder streams, at no visible cost to framing on a phone screen.
private const val VIEWFINDER_WIDTH = 800
private const val VIEWFINDER_HEIGHT = 450

/**
 * Deliberately minimal: this is a latency test bed, not a production streamer. No mDNS
 * bookkeeping here — NDI advertises its own sources via NDI's own discovery (mDNS-SD under the
 * hood), same as EpocCam-streamer found reason to hold a MulticastLock for. See startCapture()
 * for why we hold one here too.
 */
class MainActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var statusText: TextView
    private lateinit var previewView: AspectRatioSurfaceView
    private lateinit var surfaceHolder: SurfaceHolder

    private val ndiSender = NdiSender()
    @Volatile private var capture: CameraCapture? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        previewView = findViewById(R.id.surfaceView)
        previewView.aspectRatio = WIDTH.toFloat() / HEIGHT
        surfaceHolder = previewView.holder
        surfaceHolder.setFixedSize(VIEWFINDER_WIDTH, VIEWFINDER_HEIGHT)
        surfaceHolder.addCallback(this)

        val missingPerms = listOf(Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) startCapture()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val cameraOk = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!cameraOk) return
        if (capture == null) startCapture()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        capture?.updatePreview(holder)
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        capture?.updatePreview(null)
    }

    private fun startCapture() {
        if (capture != null) return
        Log.i(TAG, "startCapture")

        // NDI's discovery is mDNS-SD under the hood; without a MulticastLock the Wi-Fi
        // driver on some phones filters out the inbound multicast/broadcast traffic that
        // makes discovery reliable — same issue EpocCam-streamer's viewer had for plain ARP.
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("ndi_camera_streamer").also {
            it.setReferenceCounted(false)
            it.acquire()
        }

        val deviceLabel = android.os.Build.MODEL ?: "Android"
        if (!ndiSender.start("$deviceLabel (NDI Camera Test)")) {
            statusText.text = "NDI init failed — see logcat"
            return
        }

        capture = CameraCapture(
            context = this,
            previewHolder = surfaceHolder.takeIf { it.surface?.isValid == true },
            width = WIDTH,
            height = HEIGHT,
            fps = FPS,
            bitrate = BITRATE,
            ndiSender = ndiSender
        ).also { it.start() }

        statusText.text = "Streaming NDI: $deviceLabel"
    }

    private fun stopCapture() {
        val c = capture; capture = null
        c?.stop()
        ndiSender.stop()
        try { multicastLock?.release() } catch (_: Exception) {}
        multicastLock = null
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        moveTaskToBack(true)  // minimize instead of finish, matches EpocCam-streamer's behavior
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) stopCapture()
    }
}
