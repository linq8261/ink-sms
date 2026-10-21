package com.ink.sms.data.model

import android.provider.Telephony

data class Message(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Boolean,
    /** 接收/发送该短信所用的 SIM 卡订阅 ID（系统短信库 sub_id 列）；<=0 表示未知。 */
    val subId: Int = -1,
    /** 号码对应的显示名（系统联系人 / 公共号码库命中）；未命中为 null，UI 回退显示 address。 */
    val displayName: String? = null
) {
    val isIncoming: Boolean
        get() = type == Telephony.Sms.MESSAGE_TYPE_INBOX
}
