package biz.cesena.packride4.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import biz.cesena.packride4.R
import biz.cesena.packride4.debug.DebugLog

class DownloadForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "packride_download"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "START"
        const val ACTION_STOP = "STOP"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            context.startForegroundService(intent)
        }

        fun updateProgress(context: Context, title: String, progress: Int) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification(context, title, progress))
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        private fun buildNotification(context: Context, title: String, progress: Int): Notification {
            createChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(if (progress < 0) "Elaborazione..." else "$progress%")
                .setProgress(100, if (progress < 0) 0 else progress, progress < 0)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }

        private fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Download mappe", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progresso download dati offline"
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Download in corso"
                DebugLog.log("download-service: started for $title")
                startForeground(NOTIFICATION_ID, buildNotification(this, title, 0))
            }
            ACTION_STOP -> {
                DebugLog.log("download-service: stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
