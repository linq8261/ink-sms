package com.ink.sms.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ink.sms.MainActivity
import com.ink.sms.R
import com.ink.sms.data.extractVerificationCode
import com.ink.sms.receiver.CopyCodeReceiver

object NotificationHelper {
    private const val CHANNEL_ID = "sms_incoming_v2"
    private const val CHANNEL_NAME = "收到短信"

    /** 已弹出的短信通知 ID，用于“打开 App 时统一清除”。 */
    private val smsNotificationIds = mutableSetOf<Int>()

    /**
     * 收到一条短信就弹一条通知（多少条短信弹多少条，不聚合）。
     * 通知点击后自动消失（AUTO_CANCEL）；打开 App 时由 [cancelSmsNotifications] 统一清掉。
     */
    fun postSms(context: Context, address: String?, body: String) {
        val appContext = context.applicationContext
        val manager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "收到新短信时提醒"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            appContext,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (!address.isNullOrBlank()) address else "短信"
        val notificationId = System.currentTimeMillis().toInt() and 0x7FFFFFFF
        smsNotificationIds.add(notificationId)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        val code = extractVerificationCode(body)
        if (code != null) {
            val copyIntent = Intent(appContext, CopyCodeReceiver::class.java).apply {
                action = CopyCodeReceiver.ACTION_COPY_CODE
                putExtra(CopyCodeReceiver.EXTRA_CODE, code)
            }
            val copyPendingIntent = PendingIntent.getBroadcast(
                appContext,
                notificationId,
                copyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_notification,
                "复制验证码：$code",
                copyPendingIntent
            )
        }

        manager.notify(notificationId, builder.build())
    }

    /** 打开 App 时清掉所有已弹出的短信通知（保留保活前台服务的通知）。 */
    fun cancelSmsNotifications(context: Context) {
        val appContext = context.applicationContext
        val manager =
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        smsNotificationIds.forEach { manager.cancel(it) }
        smsNotificationIds.clear()
    }
}
