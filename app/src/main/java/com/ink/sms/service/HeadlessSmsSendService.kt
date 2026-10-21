package com.ink.sms.service

import android.app.Service
import android.content.Intent
import android.os.IBinder

// 占位：通知栏/车载快捷回复入口（RESPOND_VIA_MESSAGE）。
// 本期未实现具体回复逻辑（需求中已排除快捷回复）。
class HeadlessSmsSendService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }
}
