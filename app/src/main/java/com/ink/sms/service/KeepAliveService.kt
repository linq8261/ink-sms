package com.ink.sms.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ink.sms.MainActivity
import com.ink.sms.R

/**
 * 前台保活服务：以“前台”身份常驻，避免被 OPPO ColorOS 等厂商的
 * 「应用速冻 / 后台冻结 / 内存清理」杀掉进程，从而保证后台也能及时收到
 * [android.provider.Telephony.Sms.Intents.SMS_DELIVER_ACTION] 短信广播。
 *
 * 代价：状态栏会有一个常驻通知（已设为低优先级、静默、不可消除）。
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "sms_keep_alive"
        private const val CHANNEL_NAME = "后台保活"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 必须在 onStartCommand 内尽快调用 startForeground，否则系统会报 ANR/崩溃
        startForeground(NOTIFICATION_ID, buildNotification())
        // START_STICKY：进程被回收后系统资源允许时尽量重启服务，保持常驻
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "保持短信应用在后台运行，避免漏收短信"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("短信助手后台运行中")
            .setContentText("保持运行以接收短信")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }
}
