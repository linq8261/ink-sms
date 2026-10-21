package com.ink.sms.feature.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.sms.data.RecycleBinRepository
import com.ink.sms.data.SimCard
import com.ink.sms.data.SmsRepository
import com.ink.sms.data.entity.RecycledMessage
import com.ink.sms.data.model.Message
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConversationDetailViewModel @Inject constructor(
    private val repository: SmsRepository,
    private val recycleBinRepository: RecycleBinRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val threadId: Long = savedStateHandle.get<Long>("threadId") ?: 0L

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    /** 当前可用 SIM 卡，供回复时选择用哪张卡发送。 */
    val simCards: List<SimCard> get() = repository.getSimCards()

    init {
        // 打开会话即把该会话下所有未读短信标记为已读（与系统短信 App 共享已读状态）。
        repository.markThreadRead(threadId)
        load()
        // 实时监听仓库数据变更（接收/发送/删除短信等），自动刷新当前会话消息列表；
        // 收到当前会话新消息时一并标记为已读（打开详情页期间视为已读），修复
        // 「打开详情页时收到新消息不更新」且遗留未读的问题。
        viewModelScope.launch {
            repository.dataVersion.collect {
                repository.markThreadRead(threadId)
                load()
            }
        }
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            _messages.value = repository.getMessages(threadId)
        }
    }

    fun send(address: String, body: String, subId: Int = -1) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.sendMessage(address, body, subId)
            _messages.value = repository.getMessages(threadId)
        }
    }

    /**
     * 左滑删除：先把消息副本写入回收站，再从系统短信库删除原记录，最后刷新本页列表。
     * 无二次确认。
     */
    fun deleteMessage(msg: Message) {
        viewModelScope.launch(Dispatchers.IO) {
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
            repository.deleteSystemMessage(msg.id)
            _messages.value = repository.getMessages(threadId)
        }
    }

    /**
     * 把该条短信从验证码会话移出，还原到发送方 address 的真实会话（见 [SmsRepository.moveMessageOutOfVerification]）。
     * 非挂起：内部用 viewModelScope 切到 IO 执行，可直接作为 `(Message) -> Unit` 回调传入。
     */
    fun moveOutOfVerification(msg: Message) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.moveMessageOutOfVerification(msg.id, msg.address)
        }
    }
}
