package com.ink.sms.feature.conversation

import com.ink.sms.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.AndroidUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ink.sms.data.VERIFICATION_THREAD_ID
import com.ink.sms.data.model.Conversation
import com.ink.sms.feature.conversation.Avatar
import com.ink.sms.util.formatSmsTime

/** 标题栏与置顶列表项共用的浅色背景。 */
private val PinnedItemColor = Color(0xFFE3F2FD)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    onItemClick: (Long) -> Unit = {},
    onNewConversation: () -> Unit = {},
    onOpenRecycleBin: () -> Unit = {},
    viewModel: ConversationListViewModel = hiltViewModel()
) {
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val simCards = remember(viewModel) { viewModel.simCards }
    var menuThreadId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteThreadId by remember { mutableStateOf<Long?>(null) }

    // 每次进入首页（composable 进入组合）即检查一次：清理超过 1 天的验证码短信、以及回收站中超过 30 天的记录。
    // 删除/清理在后台线程（IO）执行，即使快速离开首页也能完成；幂等，反复进出不会重复删除。
    LaunchedEffect(Unit) {
        viewModel.cleanupExpiredVerificationCodes()
        viewModel.purgeExpiredRecycleBin()
    }

    // “验证码”会话：有验证码短信时由数据层正常返回并参与日期排序（不强制置顶）；
    // 无验证码短信时显示占位项（date=0，自然排在列表末尾）。
    val displayList = conversations.toMutableList().apply {
        if (none { it.threadId == VERIFICATION_THREAD_ID }) {
            add(
                Conversation(
                    threadId = VERIFICATION_THREAD_ID,
                    address = "",
                    snippet = "暂无验证码短信",
                    date = 0,
                    read = true,
                    messageCount = 0,
                    displayName = "验证码",
                    isCode = true,
                    aggregated = true,
                    pinKey = VERIFICATION_THREAD_ID.toString()
                )
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenRecycleBin) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "回收站")
                    }
                    IconButton(onClick = onNewConversation) {
                        Icon(Icons.Filled.Add, contentDescription = "新建短信")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        if (displayList.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("暂无会话", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(displayList, key = { it.pinKey }) { conv ->
                    val isCode = conv.isCode
                    ConversationSwipeItem(
                        conv = conv,
                        isCode = isCode,
                        simCards = simCards,
                        onClick = { if (conv.aggregated) onItemClick(conv.threadId) },
                        onLongClick = { if (!isCode) menuThreadId = conv.pinKey },
                        menuExpanded = !isCode && menuThreadId == conv.pinKey,
                        onDismissMenu = { menuThreadId = null },
                        onTogglePin = { viewModel.togglePin(conv.pinKey); menuThreadId = null },
                        onDelete = {
                            if (conv.aggregated) {
                                if (conv.messageCount <= 1) {
                                    viewModel.deleteConversation(conv.threadId)
                                } else {
                                    pendingDeleteThreadId = conv.threadId
                                }
                            } else {
                                viewModel.deleteSingleMessage(conv.messageId)
                            }
                        }
                    )
                }
            }
        }
    }

    if (pendingDeleteThreadId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteThreadId = null },
            title = { Text("删除会话") },
            text = { Text("该会话包含多条消息，删除后可在回收站保留 30 天。确定删除吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(pendingDeleteThreadId!!)
                    pendingDeleteThreadId = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteThreadId = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ConversationSwipeItem(
    conv: Conversation,
    isCode: Boolean = false,
    simCards: List<com.ink.sms.data.SimCard>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalContext.current.resources.displayMetrics.density
    val revealPx = 100f * density
    Box(Modifier.fillMaxWidth()) {
        // 左滑后露出的删除按钮（右侧，宽 100dp）；验证码固定项不可删除。
        if (!isCode) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    Modifier
                        .width(100.dp)
                        .fillMaxHeight()
                        .clickable { onDelete() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "删除",
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offsetX.toInt(), 0) }
                .background(MaterialTheme.colorScheme.surface)
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = !isCode,
                    state = rememberDraggableState { delta ->
                        offsetX = (offsetX + delta).coerceIn(-revealPx, 0f)
                    },
                    onDragStopped = {
                        val threshold = revealPx * 0.4f
                        offsetX = if (offsetX <= -threshold) -revealPx else 0f
                    }
                )
                .combinedClickable(
                    onClick = {
                        if (offsetX != 0f) {
                            offsetX = 0f
                        } else {
                            onClick()
                        }
                    },
                    onLongClick = onLongClick
                )
        ) {
            ConversationItem(
                conv = conv,
                simCards = simCards,
                menuExpanded = menuExpanded,
                onDismissMenu = onDismissMenu,
                onTogglePin = onTogglePin
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationItem(
    conv: Conversation,
    simCards: List<com.ink.sms.data.SimCard>,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onTogglePin: () -> Unit
) {
    val context = LocalContext.current
    CompositionLocalProvider(LocalUriHandler provides AndroidUriHandler(context)) {
        Column(Modifier.fillMaxWidth()) {
        ExposedDropdownMenuBox(
            expanded = menuExpanded,
            onExpandedChange = { if (!it) onDismissMenu() }
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(if (conv.pinned) PinnedItemColor else Color.Transparent)
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
            ) {
                val unread = conv.unreadCount > 0
                // 未读指示器：仅聚合行（会话/验证码）显示；非聚合单条消息不存在未读状态。
                if (unread && conv.aggregated) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 6.dp, end = 12.dp)
                            .background(
                                MaterialTheme.colorScheme.error,
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            conv.unreadCount.toString(),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        val baseTitle = (conv.displayName ?: conv.address).ifBlank { "(未知号码)" }
                        val titleColor = when {
                            conv.aggregated && unread -> MaterialTheme.colorScheme.error
                            conv.aggregated -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.primary
                        }
                        Text(
                            if (conv.aggregated) "【聚合】$baseTitle" else baseTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = titleColor
                        )
                        val trimUrl = { raw: String ->
                            raw.trimEnd('.', ',', ';', '!', '?', '）', '。', '，', '！', '？', '》', '】', ')')
                        }
                        val urlPattern = remember {
                            Regex(
                                """(https?://[^\s）)。，,!！?？】》]*)|(www\.[^\s）)。，,!！?？】》]*)""",
                                RegexOption.IGNORE_CASE
                            )
                        }
                        val annotatedSnippet = remember(conv.snippet) {
                            buildAnnotatedString {
                                append(conv.snippet)
                                urlPattern.findAll(conv.snippet).forEach { result ->
                                    val start = result.range.first
                                    val end = result.range.last + 1
                                    val raw = trimUrl(result.value)
                                    val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
                                    addStyle(
                                        SpanStyle(
                                            color = Color(0xFF1565C0),
                                            textDecoration = TextDecoration.Underline
                                        ),
                                        start,
                                        end
                                    )
                                    addLink(LinkAnnotation.Url(url), start, end)
                                }
                            }
                        }
                        Text(
                            text = annotatedSnippet,
                            style = MaterialTheme.typography.bodyMedium,
                            // 所有行（聚合 / 非聚合）均展示完整正文，行高随内容自适应。
                            maxLines = Int.MAX_VALUE,
                            overflow = TextOverflow.Clip
                        )
                        Row(
                            Modifier
                                .align(Alignment.End)
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SimBadge(conv.subId, simCards, Modifier.padding(end = 4.dp))
                            Text(
                                formatSmsTime(conv.date),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(
                    text = { Text(if (conv.pinned) "取消置顶" else "置顶") },
                    onClick = {
                        onTogglePin()
                        onDismissMenu()
                    }
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = Color(0xFFE0E0E0))
        }
    }
}
