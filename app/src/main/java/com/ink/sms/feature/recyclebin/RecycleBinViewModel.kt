package com.ink.sms.feature.recyclebin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ink.sms.data.RecycleBinRepository
import com.ink.sms.data.entity.RecycledMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val repository: RecycleBinRepository
) : ViewModel() {

    val items: StateFlow<List<RecycledMessage>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun restore(msg: RecycledMessage) {
        viewModelScope.launch(Dispatchers.IO) { repository.restore(msg) }
    }

    fun remove(msg: RecycledMessage) {
        viewModelScope.launch(Dispatchers.IO) { repository.remove(msg) }
    }
}
