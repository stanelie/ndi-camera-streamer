package com.exmachina.ndicamerastreamer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

private const val CHANNEL_ID = "ndi_stream"
private const val NOTIFICATION_ID = 1

/**
 * Keeps the stream alive when the app is not in the foreground.
 *
 * Without this, Android treats a backgrounded camera app as disposable and will reclaim it — on
 * a phone left running for the length of a show, that means the feed disappearing unannounced.
 *
 * The intended behaviour is "survives everything except a deliberate dismissal":
 *  - START_STICKY so the system restarts the service if it kills it under memory pressure.
 *  - `android:stopWithTask="true"` in the manifest so swiping the app away in the task switcher
 *    *does* stop it. That is the deliberate operator gesture, and it should work; the goal is to
 *    survive accidents (Back, Home, screen-off), not to be impossible to shut down.
 */
class StreamingService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "NDI Camera", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while the NDI stream is live" }
        )

        // Tapping the notification returns to the app rather than starting a second instance.
        val reopen = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("NDI camera streaming")
                .setContentText("Live — swipe the app away in recents to stop")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentIntent(reopen)
                .setOngoing(true)
                .build()
        )
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
