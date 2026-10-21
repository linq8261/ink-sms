package com.ink.sms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ink.sms.service.KeepAliveService

/**
 * 开机后启动前台保活服务。需用户已在 OPPO 设置中开启本应用的「自启动」，
 * 否则 ColorOS 不会派发 BOOT_COMPLETED。APP 进程被短信广播唤醒时也会通过
 * [com.ink.sms.SmsApplication] 重新拉起该服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            KeepAliveService.start(context)
        }
    }
}
