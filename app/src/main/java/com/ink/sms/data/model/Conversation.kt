package com.ink.sms.data.model

data class Conversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val read: Boolean,
    val messageCount: Int,
    /** 该会话下未读（系统短信库 READ=0）消息条数，列表展示未读标识用。 */
    val unreadCount: Int = 0,
    /** 是否置顶（本地 SharedPreferences 维护，非系统短信库字段）。 */
    val pinned: Boolean = false,
    /** 号码对应的显示名（系统联系人 / 公共号码库命中）；未命中为 null，UI 回退显示 address。 */
    val displayName: String? = null,
    /** 单条消息的 `_id`（仅非聚合行有效；聚合行恒为 -1）。 */
    val messageId: Long = -1,
    /** 接收/发送该会话最新一条短信所用的 SIM 卡订阅 ID（系统短信库 sub_id 列）；<=0 表示未知。 */
    val subId: Int = -1,
    /** 是否验证码虚拟会话行。 */
    val isCode: Boolean = false,
    /** 是否为聚合（按会话/手机号/验证码）行；false 表示单条消息行。 */
    val aggregated: Boolean = false,
    /** 列表唯一键 + 置顶存储键：聚合行 = threadId 字符串，单条行 = "m<messageId>"。 */
    val pinKey: String = ""
)
