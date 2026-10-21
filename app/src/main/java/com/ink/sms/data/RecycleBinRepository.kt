package com.ink.sms.data

import com.ink.sms.data.dao.RecycleBinDao
import com.ink.sms.data.entity.RecycledMessage
import javax.inject.Inject
import javax.inject.Singleton

/** 回收站数据访问：被删除短信的 Room 持久化、观察、恢复与过期清理。 */
@Singleton
class RecycleBinRepository @Inject constructor(
    private val dao: RecycleBinDao,
    private val smsRepository: SmsRepository
) {
    /** 把一条短信副本加入回收站（系统库中的原记录由调用方负责删除）。 */
    suspend fun add(msg: RecycledMessage): Long = dao.insert(msg)

    fun observeAll() = dao.observeAll()

    /** 恢复：把消息写回系统短信库，并从回收站删除。 */
    suspend fun restore(msg: RecycledMessage) {
        smsRepository.restoreMessage(msg)
        dao.delete(msg)
    }

    /** 从回收站永久删除一条（不写回系统库）。 */
    suspend fun remove(msg: RecycledMessage) = dao.delete(msg)

    /**
     * 清理超过 30 天的记录。轻量方案：触发时机已统一为**每次进入首页**
     * （[com.ink.sms.feature.conversation.ConversationListViewModel.purgeExpiredRecycleBin]），
     * 不再在 App 启动或打开回收站页时触发，不引入后台定时任务。
     */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) =
        dao.deleteOlderThan(now - THIRTY_DAYS_MS)

    companion object {
        private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000
    }
}
