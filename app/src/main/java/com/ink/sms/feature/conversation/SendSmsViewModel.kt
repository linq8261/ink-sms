package com.ink.sms.feature.conversation

import androidx.lifecycle.ViewModel
import com.ink.sms.data.SimCard
import com.ink.sms.data.SmsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SendSmsViewModel @Inject constructor(
    private val repository: SmsRepository
) : ViewModel() {
    /** 当前可用的 SIM 卡列表，供发送前选择用哪张卡。 */
    val simCards: List<SimCard> get() = repository.getSimCards()

    fun send(address: String, body: String, subId: Int = -1): Long? =
        repository.sendMessage(address, body, subId)
}
