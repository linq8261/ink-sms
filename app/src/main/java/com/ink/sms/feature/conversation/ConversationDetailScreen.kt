package com.ink.sms.feature.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.Popup
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ink.sms.data.VERIFICATION_THREAD_ID
import com.ink.sms.data.extractVerificationCode
import com.ink.sms.data.model.Message
import com.ink.sms.util.formatSmsTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDetailScreen(
    threadId: Long,
    onBack: () -> Unit,
    viewModel: ConversationDetailViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val simCards = remember(viewModel) { viewModel.simCards }
    val isCodeConversation = threadId == VERIFICATION_THREAD_ID
    val address = messages.firstOrNull()?.address?.takeIf { it.isNotBlank() }.orEmpty()
    var draft by remember { mutableStateOf("") }
    var selectedSubId by remember { mutableStateOf(simCards.firstOrNull()?.subId ?: -1) }
    val listState = rememberLazyListState()
    var expandedMessage by remember { mutableStateOf<Message?>(null) }
    var menuMessageId by remember { mutableStateOf<Long?>(null) }
    // 打开会话时自动滚动到最底部（最新消息）。消息变化（如发送/删除后重载）也保持贴底。
    LaunchedEffect(messages) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val m = messages.firstOrNull()
                    Text(
                        if (isCodeConversation) {
                            "验证码"
                        } else {
                            (m?.displayName ?: m?.address)?.ifBlank { "(未知号码)" } ?: "会话"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        bottomBar = {
            if (!isCodeConversation) ConversationSendBar(
                text = draft,
                onTextChange = { draft = it },
                simCards = simCards,
                selectedSubId = selectedSubId,
                onSubIdChange = { selectedSubId = it },
                onSend = {
                    if (draft.isNotBlank() && address.isNotBlank()) {
                        viewModel.send(address, draft, selectedSubId)
                        draft = ""
                    }
                },
                enabled = address.isNotBlank() && draft.isNotBlank()
            )
        }
    ) { padding ->
        if (messages.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("暂无消息", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(8.dp).imePadding(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    MessageSwipeItem(
                        msg,
                        simCards = simCards,
                        onDelete = viewModel::deleteMessage,
                        onDoubleTap = { expandedMessage = it },
                        onLongClick = { menuMessageId = it.id },
                        menuExpanded = menuMessageId == msg.id,
                        onMenuDismiss = { menuMessageId = null },
                        isCodeConversation = isCodeConversation,
                        onMoveOut = viewModel::moveOutOfVerification
                    )
                }
            }
        }
        if (expandedMessage != null) {
            MessageExpandedOverlay(expandedMessage!!, onDismiss = { expandedMessage = null })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationSendBar(
    text: String,
    onTextChange: (String) -> Unit,
    simCards: List<com.ink.sms.data.SimCard>,
    selectedSubId: Int,
    onSubIdChange: (Int) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean
) {
    Surface(tonalElevation = 0.dp, modifier = Modifier.navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().padding(8.dp).imePadding()) {
            if (simCards.size > 1) {
                Text("发送 SIM 卡", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    simCards.forEach { sim ->
                        Row(
                            Modifier.selectable(
                                selected = selectedSubId == sim.subId,
                                onClick = { onSubIdChange(sim.subId) }
                            ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedSubId == sim.subId,
                                onClick = null
                            )
                            Text(sim.displayName)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    placeholder = { Text("输入消息") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onSend,
                    enabled = enabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text("发送")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MessageSwipeItem(
    msg: Message,
    simCards: List<com.ink.sms.data.SimCard>,
    onDelete: (Message) -> Unit,
    onDoubleTap: (Message) -> Unit = {},
    onLongClick: (Message) -> Unit = {},
    menuExpanded: Boolean = false,
    onMenuDismiss: () -> Unit = {},
    isCodeConversation: Boolean = false,
    onMoveOut: (Message) -> Unit = {}
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    val density = LocalContext.current.resources.displayMetrics.density
    val revealPx = 100f * density
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    Box(Modifier.fillMaxWidth()) {
        // 左滑后露出的删除按钮（右侧，宽 100dp）
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
                    .clickable { onDelete(msg) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        Surface(
            Modifier
                .offset { IntOffset(offsetX.toInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
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
                        }
                    },
                    onDoubleClick = {
                        if (offsetX == 0f) onDoubleTap(msg)
                    },
                    onLongClick = {
                        if (offsetX == 0f) onLongClick(msg)
                    }
                )
        ) {
            MessageBubble(
                msg,
                simCards = simCards,
                onDoubleTap = onDoubleTap,
                onLongPress = { if (offsetX == 0f) onLongClick(msg) }
            )
        }
        if (menuExpanded) {
            Popup(onDismissRequest = onMenuDismiss, alignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 3.dp,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        TextButton(onClick = {
                            clipboardManager.setText(AnnotatedString(msg.body))
                            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            onMenuDismiss()
                        }) {
                            Text("复制文本", color = MaterialTheme.colorScheme.primary)
                        }
                        if (isCodeConversation) {
                            TextButton(onClick = {
                                onMoveOut(msg)
                                Toast.makeText(context, "已移出验证码列表", Toast.LENGTH_SHORT).show()
                                onMenuDismiss()
                            }) {
                                Text("移出验证码列表", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: Message,
    simCards: List<com.ink.sms.data.SimCard>,
    onDoubleTap: (Message) -> Unit = {},
    onLongPress: () -> Unit = {}
) {
    val isIncoming = msg.isIncoming
    val bubbleColor = if (isIncoming) {
        MaterialTheme.colorScheme.surfaceVariant
    } else {
        Color(0xFF95EC69) // 绿色：我发出的消息
    }
    val avatarChar = if (isIncoming) {
        (msg.displayName ?: msg.address).firstOrNull()?.toString() ?: "?"
    } else {
        "我"
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isIncoming) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Top
    ) {
        if (isIncoming) {
            Avatar(avatarChar)
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                // weight(fill=false)：先为头像预留空间，气泡按内容自适应宽度（上限为剩余宽度）；
                // 否则气泡内部子项会把整行宽度吃掉，Row 顺序测量时头像剩余空间为 0，被挤压不可见
                .weight(1f, fill = false)
                .clip(RoundedCornerShape(12.dp))
                .background(bubbleColor)
                .padding(10.dp)
        ) {
            val context = LocalContext.current
            val clipboardManager = LocalClipboardManager.current
            val urlPattern = remember {
                // 排除中文/全角标点与空白，避免把链接后的中文一起吞进链接
                Regex(
                    """(https?://[^\s）)。，,!！?？】》]*)|(www\.[^\s）)。，,!！?？】》]*)""",
                    RegexOption.IGNORE_CASE
                )
            }
            // 去掉 URL 尾部可能被误吞的半角/全角标点
            val trimUrl = { raw: String ->
                raw.trimEnd('.', ',', ';', '!', '?', '）', '。', '，', '！', '？', '》', '】', ')')
            }
            val code = remember(msg.body) { extractVerificationCode(msg.body) }
            val codeRange = remember(msg.body) {
                code?.let { msg.body.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?.let { it until it + code.length }
            }
            val codeToCopy = code
            val annotatedText = remember(msg.body) {
                buildAnnotatedString {
                    val text = msg.body
                    append(text)
                    val linkRanges = mutableListOf<IntRange>()
                    urlPattern.findAll(text).forEach { result ->
                        val start = result.range.first
                        val end = result.range.last + 1
                        linkRanges.add(start until end)
                        addStyle(
                            SpanStyle(
                                color = Color(0xFF1565C0),
                                textDecoration = TextDecoration.Underline
                            ),
                            start,
                            end
                        )
                        addLink(LinkAnnotation.Url(trimUrl(result.value)), start, end)
                    }
                    codeRange?.let { range ->
                        val start = range.first
                        val end = range.last + 1
                        if (linkRanges.none { start >= it.first && end <= it.last }) {
                            addStyle(
                                SpanStyle(
                                    fontWeight = FontWeight.Bold,
                                    background = Color(0xFFFFF176)
                                ),
                                start,
                                end
                            )
                        }
                    }
                }
            }
            Column {
                var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
                Text(
                    text = annotatedText,
                    style = MaterialTheme.typography.bodyMedium.copy(color = Color.Black),
                    onTextLayout = { textLayout = it },
                    modifier = Modifier.pointerInput(msg.id) {
                        detectTapGestures(
                            onTap = { offset ->
                                val layout = textLayout ?: return@detectTapGestures
                                val pos = layout.getOffsetForPosition(offset)
                                annotatedText.getStringAnnotations(start = pos, end = pos)
                                    .firstOrNull()?.let { range ->
                                        val raw = range.item
                                        val url = if (raw.startsWith("http", ignoreCase = true)) {
                                            raw
                                        } else {
                                            "https://$raw"
                                        }
                                        runCatching {
                                            context.startActivity(
                                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                            )
                                        }
                                    }
                            },
                            onDoubleTap = { onDoubleTap(msg) },
                            onLongPress = { onLongPress() }
                        )
                    }
                )
                Row(
                    Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (codeToCopy != null) {
                        TextButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(codeToCopy))
                                Toast.makeText(context, "验证码已复制", Toast.LENGTH_SHORT).show()
                            }
                        ) {
                            Text(
                                "复制验证码",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    SimBadge(
                        msg.subId,
                        simCards,
                        Modifier.padding(start = if (codeToCopy != null) 8.dp else 0.dp)
                    )
                    Text(
                        formatSmsTime(msg.date),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
        if (!isIncoming) {
            Spacer(Modifier.width(8.dp))
            // 发件头像背景与发件气泡同色（绿色），内容为「我」字
            Avatar(avatarChar, backgroundColor = bubbleColor)
        }
    }
}

@Composable
private fun MessageExpandedOverlay(message: Message, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
            SelectionContainer {
                Text(
                    text = message.body,
                    color = Color.White,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(32.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

@Composable
internal fun Avatar(char: String, backgroundColor: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center
    ) {
        Text(
            char,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
    }
}
