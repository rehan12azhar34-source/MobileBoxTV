package com.tvbox.mobileemu

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class ProjectionService : Service() {

    companion object {
        var onForegroundReady: (() -> Unit)? = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "mobilebox_display"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "MobileBox Virtual Display",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }

        val notif = Notification.Builder(this, channelId)
            .setContentTitle("MobileBox TV")
            .setContentText("Virtual Phone Display Active")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(101, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(101, notif)
        }

        onForegroundReady?.invoke()
        onForegroundReady = null
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
