package com.adphc.remotesupport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Android requires an active foreground service (with type "mediaProjection")
 * for the whole time the screen is being captured, otherwise the system
 * kills the capture as soon as the app goes to the background. This service
 * only shows a persistent notification - all the actual WebRTC/capture logic
 * lives in MainActivity.
 */
class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screen_capture_channel"
        const val NOTIFICATION_ID = 1001

        // MainActivity sets this right before calling startForegroundService(),
        // and we invoke it only once startForeground() has actually completed.
        // This is required on Android 14+ (targetSdk 34): MediaProjection capture
        // must not start until the foreground service of type mediaProjection is
        // confirmed running, otherwise the system throws a SecurityException.
        var onForegroundStarted: (() -> Unit)? = null
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Screen sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADPHC Remote Support")
            .setContentText("Your screen is being shared with IT support")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // Foreground service is now confirmed running — safe to start MediaProjection capture.
        onForegroundStarted?.invoke()
        onForegroundStarted = null

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
