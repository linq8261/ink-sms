package com.ink.sms.feature.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ink.sms.data.SimCard

/**
 * 根据 subId 在可用 SIM 卡列表中的顺序渲染「卡1 / 卡2」小徽标，显示在消息日期左侧。
 * subId <= 0 或无法匹配到当前可用卡时返回 null（不渲染），避免误标。
 */
@Composable
fun SimBadge(subId: Int, simCards: List<SimCard>, modifier: Modifier = Modifier) {
    val label = remember(subId, simCards) {
        if (subId <= 0) return@remember null
        val idx = simCards.indexOfFirst { it.subId == subId }
        if (idx >= 0) "卡${idx + 1}" else null
    }
    if (label != null) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = modifier
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
