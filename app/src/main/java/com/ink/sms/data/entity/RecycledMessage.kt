package com.ink.sms.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 被「左滑删除」移入回收站的短信副本。系统短信库中的原记录已删除，
 * 副本只保存在本地 Room 数据库，30 天后由 [com.ink.sms.data.RecycleBinRepository.purgeExpired] 在每次进入首页时清理。
 */
@Entity(tableName = "recycled_messages")
data class RecycledMessage(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    /** 原系统短信库的 `_id`（content://sms 中的行 id）。 */
    val originalId: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    /** 原短信的时间戳(ms)。 */
    val date: Long,
    /** 原短信类型（INBOX / SENT …），恢复时写回系统库。 */
    val type: Int,
    val read: Boolean,
    /** 进入回收站的时间戳(ms)，用于计算 30 天过期。 */
    val deletedAt: Long
)
