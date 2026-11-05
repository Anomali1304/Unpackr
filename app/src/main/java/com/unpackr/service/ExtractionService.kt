package com.unpackr.service

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
import androidx.core.app.NotificationCompat
import com.unpackr.MainActivity
import com.unpackr.R
import com.unpackr.engine.model.ExtractionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object ExtractionStateHolder {
    val currentEvent = MutableStateFlow<ExtractionEvent?>(null)
    val isExtracting = MutableStateFlow(false)
    val cancelRequested = MutableStateFlow(false)
}

class ExtractionService : Service() {

    companion object {
        const val CHANNEL_ID = "unpackr_extraction_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.unpackr.unpackr.START_EXTRACTION"
        const val ACTION_CANCEL = "com.unpackr.unpackr.CANCEL_EXTRACTION"

        fun startService(context: Context) {
            val intent = Intent(context, ExtractionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ExtractionService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundWithNotification()
                observeEvents()
            }
            ACTION_CANCEL -> {
                ExtractionStateHolder.cancelRequested.value = true
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(
            title = getString(R.string.notification_extracting_title),
            text = "Preparing partition extraction...",
            progress = 0
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun observeEvents() {
        scope.launch {
            ExtractionStateHolder.currentEvent.collect { event ->
                when (event) {
                    is ExtractionEvent.PartitionStarted -> {
                        updateNotification(
                            title = "Extracting ${event.partition}",
                            text = "Partition ${event.index} of ${event.total}",
                            progress = 0
                        )
                    }
                    is ExtractionEvent.BytesWritten -> {
                        val percent = (event.overallProgressFraction * 100).toInt()
                        val speedMb = event.speedBytesPerSec / (1024.0 * 1024.0)
                        updateNotification(
                            title = "Extracting ${event.partition}",
                            text = "${"%.1f".format(speedMb)} MB/s • $percent%",
                            progress = percent
                        )
                    }
                    is ExtractionEvent.AllFinished -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    null -> {}
                    else -> {}
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, text: String, progress: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = Intent(this, ExtractionService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .addAction(0, getString(R.string.extract_cancel_button), cancelPendingIntent)
            .build()
    }

    private fun updateNotification(title: String, text: String, progress: Int) {
        val notification = buildNotification(title, text, progress)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
