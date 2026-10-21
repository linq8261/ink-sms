package com.ink.sms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// 占位：本应用不处理 MMS 彩信，仅声明以满足默认短信应用的要求。
// 设为默认后收到的彩信不会被写入，这是预期行为（需求已排除彩信）。
class MmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 不做处理
    }
}
