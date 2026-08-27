package com.exmachina.ndicamerastreamer

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
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
    private lateinit var focusModeButton: Button
    private lateinit var lockButton: Button
    private lateinit var lockedBadge: TextView

    // App pinning (screen pinning / lock-task mode) — for a phone left unattended for the
    // length of a show, so a stray touch can't back out to the launcher or notification shade
    // and drop the stream. Polled rather than event-driven because Android has no callback for
    // lock-task state changes; see unlockPollRunnable.
    private var locked = false
    private var pinningConfirmed = false
    private val unlockPollHandler = android.os.Handler(android.os.Looper.getMainLooper())

    // false = continuous autofocus (HAL refocuses on its own).
    // true  = tap-to-focus: the lens is driven once and locked, and each tap re-locks.
    private var tapFocusMode = false
    // Guards against a second tap landing while the first is still converging — that would
    // cancel the in-flight attempt, so focus could appear never to lock and the button label
    // would flicker as attempts completed out of order.
    @Volatile private var focusInProgress = false
    private val focusTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

        focusModeButton = findViewById(R.id.focusModeButton)
        focusModeButton.setOnClickListener {
            tapFocusMode = !tapFocusMode
            if (tapFocusMode) {
                // Lock immediately rather than waiting for a separate tap; a later tap re-locks
                // at that point through the same path.
                triggerFocus()
            } else {
                // Cancel any in-flight attempt so its timeout cannot later overwrite this "AF"
                // with a stale "MF?" — setContinuousAf() clears the camera-side listener, which
                // would otherwise leave triggerAfAndLock's callback pending forever.
                focusInProgress = false
                focusTimeoutHandler.removeCallbacksAndMessages(null)
                focusModeButton.text = "AF"
                capture?.setContinuousAf()
            }
        }

        findViewById<FrameLayout>(R.id.rootLayout).setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && tapFocusMode) { triggerFocus(); true }
            else false
        }

        lockButton = findViewById(R.id.lockButton)
        lockedBadge = findViewById(R.id.lockedBadge)
        lockButton.setOnClickListener { enableLock() }

        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missingPerms = needed
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missingPerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), 1)
        }
    }

    private fun triggerFocus() {
        if (focusInProgress) return  // ignore a retap while the previous attempt converges
        val c = capture ?: return
        focusInProgress = true
        focusModeButton.text = "..."
        // Safety net: if the HAL never reports a final AF state, don't leave focus permanently
        // stuck ignoring taps.
        focusTimeoutHandler.postDelayed({ finishFocus(focused = false) }, 4_000L)
        c.triggerAfAndLock { focused -> runOnUiThread { finishFocus(focused) } }
    }

    private fun finishFocus(focused: Boolean) {
        if (!focusInProgress) return  // already finished (e.g. timeout fired after the callback)
        focusInProgress = false
        focusTimeoutHandler.removeCallbacksAndMessages(null)
        focusModeButton.text = if (focused) "MF" else "MF?"
    }

    private val unlockPollRunnable = object : Runnable {
        override fun run() {
            if (!locked) return
            val state = getSystemService(ActivityManager::class.java).lockTaskModeState
            if (state == ActivityManager.LOCK_TASK_MODE_PINNED ||
                state == ActivityManager.LOCK_TASK_MODE_LOCKED) {
                // startLockTask() is asynchronous; this confirms the OS actually entered the
                // mode before disableLock() is allowed to trust a later NONE reading as a real
                // unpin rather than pinning simply not having taken effect yet.
                pinningConfirmed = true
            } else if (state == ActivityManager.LOCK_TASK_MODE_NONE && pinningConfirmed) {
                disableLock()
                return
            }
            unlockPollHandler.postDelayed(this, 500)
        }
    }

    private fun enableLock() {
        locked = true
        pinningConfirmed = false
        lockButton.visibility = View.GONE
        lockedBadge.visibility = View.VISIBLE
        startLockTask()
        unlockPollHandler.postDelayed(unlockPollRunnable, 500)
    }

    private fun disableLock() {
        locked = false
        unlockPollHandler.removeCallbacks(unlockPollRunnable)
        lockedBadge.visibility = View.GONE
        lockButton.visibility = View.VISIBLE
    }

    // While locked, swallow touches/keys before they reach the system (Back, Recents, the
    // notification shade edge-swipe) rather than relying on lock-task mode alone to block them —
    // matches EpocCam-streamer, where lock-task alone was found not to be enough on this class
    // of device/launcher combination.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (locked) return true
        return super.dispatchTouchEvent(ev)
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (locked) return true
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (locked) return true
        return super.onKeyUp(keyCode, event)
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

        // Foreground service first: it is what keeps the stream alive once the app is no longer
        // in front, and Android requires it to be running before we rely on that.
        startForegroundService(Intent(this, StreamingService::class.java))

        val deviceLabel = android.os.Build.MODEL ?: "Android"
        // Android has no real network hostname (gethostname() is always literally "localhost"
        // here), which the NDI SDK otherwise uses as the advertised "machine name" — see
        // NdiSender.start() for why that broke reconnection. ANDROID_ID is stable across
        // restarts of this app on this device and unique per device, which is exactly what the
        // NDI docs require of a manually-set machine name (a clash on the network reproduces
        // the same bug this works around).
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        // Sanitized (not just interpolated raw) because this ends up inside a hand-built JSON
        // string literal in NdiSender.kt — Build.MODEL is normally plain alnum/dash/space, but
        // nothing guarantees a device vendor never puts a quote or backslash in it.
        val machineName = "$deviceLabel-${androidId.takeLast(6)}"
            .filter { it.isLetterOrDigit() || it == '-' }
        if (!ndiSender.start("$deviceLabel (NDI Camera Test)", machineName)) {
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
        stopService(Intent(this, StreamingService::class.java))
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
