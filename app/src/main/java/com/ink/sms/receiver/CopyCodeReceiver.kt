package com.ink.sms.receiver

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class CopyCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COPY_CODE) return
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val clipboard =
            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("验证码", code))
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, "验证码已复制：$code", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val ACTION_COPY_CODE = "com.ink.sms.action.COPY_CODE"
        const val EXTRA_CODE = "code"
    }
}
