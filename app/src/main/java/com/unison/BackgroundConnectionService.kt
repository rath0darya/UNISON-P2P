package com.unison

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/**
 * Foreground-service foundation. It shows a truthful persistent status notification;
 * direct P2P socket/session management must be connected to this lifecycle before
 * this service can claim to keep a peer connection alive.
 */
class BackgroundConnectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "UNISON connection",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "Shows when UNISON background connection is enabled." }
            )
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }.setSmallIcon(R.drawable.ic_unison)
            .setContentTitle("UNISON")
            .setContentText("Background connection service is active")
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "unison_connection"
        private const val NOTIFICATION_ID = 4101
    }
}
