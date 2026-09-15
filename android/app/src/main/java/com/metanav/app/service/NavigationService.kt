package com.metanav.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.metanav.app.MainActivity
import com.metanav.app.MetanavApp
import com.metanav.app.R
import com.metanav.app.frames.SourceKind

/**
 * Keeps guidance alive with the screen off and the phone in a pocket: a foreground notification
 * plus a partial wake lock. The engine itself lives in [com.metanav.app.engine.NavigationController].
 */
class NavigationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MetanavApp.controller(application).stop()
            stopSelf()
            return START_NOT_STICKY
        }
        val kind = intent?.getStringExtra(EXTRA_SOURCE)?.let { runCatching { SourceKind.valueOf(it) }.getOrNull() } ?: SourceKind.GLASSES
        val type = when (kind) {
            SourceKind.GLASSES -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            SourceKind.PHONE_CAMERA -> ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        startForeground(NOTIFICATION_ID, buildNotification(), type)
        acquireWakeLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Metanav::Guidance").apply {
            acquire(2 * 60 * 60 * 1000L)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NavigationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "guidance"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP = "com.metanav.app.STOP"
        private const val EXTRA_SOURCE = "source"

        fun start(context: Context, kind: SourceKind) {
            val intent = Intent(context, NavigationService::class.java).putExtra(EXTRA_SOURCE, kind.name)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationService::class.java))
        }
    }
}
