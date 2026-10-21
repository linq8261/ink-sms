package com.ink.sms.feature.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.sms.data.RecycleBinRepository
import com.ink.sms.data.SimCard
import com.ink.sms.data.SmsRepository
import com.ink.sms.data.VERIFICATION_THREAD_ID
import com.ink.sms.data.entity.RecycledMessage
import com.ink.sms.data.model.Conversation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationListViewModel @Inject constructor(
    private val repository: SmsRepository,
    private val recycleBinRepository: RecycleBinRepository
) : ViewModel() {
    val conversations: StateFlow<List<Conversation>> = repository.conversations

    /** 当前可用 SIM 卡，供列表项展示「卡1 / 卡2」徽标时映射 subId 用。 */
    val simCards: List<SimCard> get() = repository.getSimCards()

    /** 切换列表项置顶状态（聚合行传 threadId，单条行传 "m<id>"）。 */
    fun togglePin(pinKey: String) = repository.togglePin(pinKey)

    /**
     * 进入首页时触发：清理超过 1 天的验证码短信（写回收站 + 删系统库），
     * 与详情页「左滑删除」逻辑一致。由 [com.ink.sms.feature.conversation.ConversationListScreen]
     * 在每次进入首页时用 `LaunchedEffect` 调用，做到「每次进首页检查一次」。
     */
    fun cleanupExpiredVerificationCodes() = repository.autoCleanupVerificationCodes()

    /**
     * 进入首页时顺带清理回收站中超过 30 天的记录（与验证码清理同一时机，不再在启动 / 打开回收站页时触发）。
     */
    fun purgeExpiredRecycleBin() {
        viewModelScope.launch(Dispatchers.IO) { recycleBinRepository.purgeExpired() }
    }

    /**
     * 删除整个会话：先将其下所有消息写入回收站，再从系统短信库删除，并刷新列表。
     * 单条/多条均走回收站；UI 层按消息数决定是否二次确认。
     */
    fun deleteConversation(threadId: Long) {
        // 固定的“验证码”会话不可删除，避免误删全部验证码短信。
        if (threadId == VERIFICATION_THREAD_ID) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.getMessages(threadId).forEach { msg ->
                recycleBinRepository.add(
                    RecycledMessage(
                        originalId = msg.id,
                        threadId = msg.threadId,
                        address = msg.address,
                        body = msg.body,
                        date = msg.date,
                        type = msg.type,
                        read = msg.read,
                        deletedAt = System.currentTimeMillis()
                    )
                )
            }
            repository.deleteThread(threadId)
        }
    }

    /**
     * 删除单条短信（非聚合行）：先将其写入回收站，再从系统短信库删除并刷新列表。
     */
    fun deleteSingleMessage(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getMessageById(id)?.let { msg ->
                recycleBinRepository.add(
                    RecycledMessage(
                        originalId = msg.id,
                        threadId = msg.threadId,
                        address = msg.address,
                        body = msg.body,
                        date = msg.date,
                        type = msg.type,
                        read = msg.read,
                        deletedAt = System.currentTimeMillis()
                    )
                )
            }
            repository.deleteSystemMessage(id)
        }
    }
}
