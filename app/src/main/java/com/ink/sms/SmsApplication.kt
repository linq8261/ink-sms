package com.ink.sms

import android.app.Application
import com.ink.sms.service.KeepAliveService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SmsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 进程一启动就拉起前台保活服务：无论是用户打开 APP，还是系统通过
        // SMS_DELIVER 广播唤醒 APP，都能保证进程常驻，后台不漏收短信。
        KeepAliveService.start(this)
    }
}
