package dev.pocketagent.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.pocketagent.MainActivity
import dev.pocketagent.PocketAgentApp
import dev.pocketagent.R

/** Keeps the Telegram bot listening while the phone is idle. */
class TelegramService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as PocketAgentApp).container
        if (intent?.action == ACTION_STOP) {
            container.telegram.stop()
            container.settings.update { it.copy(telegramEnabled = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        container.telegram.start(container.appScope)
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 caps dataSync services; stop cleanly, the app restarts the bot when opened.
        (application as PocketAgentApp).container.telegram.stop()
        stopSelf()
    }

    override fun onDestroy() {
        (application as PocketAgentApp).container.telegram.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, TelegramService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, PocketAgentApp.CHANNEL_AGENT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Telegram bot is on")
            .setContentText("Message your bot to talk to TruAgent")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 4711
        private const val ACTION_STOP = "dev.pocketagent.TELEGRAM_STOP"

        /** Safe to call from the foreground UI; Android refuses foreground services started from the background. */
        fun start(context: Context): Boolean = runCatching {
            ContextCompat.startForegroundService(context, Intent(context, TelegramService::class.java))
        }.isSuccess

        fun stop(context: Context) {
            context.stopService(Intent(context, TelegramService::class.java))
        }
    }
}
