package com.ink.sms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsMessage
import com.ink.sms.data.SmsRepository
import com.ink.sms.data.SmsRepositoryEntryPoint
import com.ink.sms.notification.NotificationHelper
import dagger.hilt.android.EntryPointAccessors

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val extras = intent.extras ?: return
        val pdus = extras.get("pdus") as? Array<*> ?: return
        val format = extras.getString("format")

        val repository = EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmsRepositoryEntryPoint::class.java
        ).smsRepository()

        val bodyBuilder = StringBuilder()
        var address: String? = null
        var timestamp = 0L
        for (pdu in pdus) {
            val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            } else {
                SmsMessage.createFromPdu(pdu as ByteArray)
            }
            if (address == null) address = sms.originatingAddress
            if (timestamp == 0L) timestamp = sms.timestampMillis
            bodyBuilder.append(sms.messageBody)
        }

        val addr = address ?: return
        repository.persistIncoming(addr, bodyBuilder.toString(), timestamp)
        NotificationHelper.postSms(context, addr, bodyBuilder.toString())
    }
}
