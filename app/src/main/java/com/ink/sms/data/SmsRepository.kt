package com.ink.sms.data

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.content.ContextCompat
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import com.ink.sms.badge.BadgeManager
import com.ink.sms.data.dao.RecycleBinDao
import com.ink.sms.data.entity.RecycledMessage
import com.ink.sms.data.model.Conversation
import com.ink.sms.data.model.Message
import com.ink.sms.util.extractSmsSignature
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** 一张 SIM 卡（订阅）的简要信息，用于发送前选择用哪张卡。 */
data class SimCard(
    val subId: Int,
    val displayName: String
)

@Singleton
class SmsRepository @Inject constructor(
    private val app: Application,
    private val contactResolver: ContactResolver,
    private val recycleBinDao: RecycleBinDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, _ -> })
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    /** 数据变更版本号：任何写库操作（接收/发送/删除/标已读/恢复）后自增，供详情页监听以刷新消息。 */
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    /** 置顶会话的 threadId 集合，持久化在应用私有 SharedPreferences。 */
    private val pinPrefs = app.getSharedPreferences(PIN_PREFS, Context.MODE_PRIVATE)

    /** 是否已授予读取短信权限：未授权时不应触碰系统短信库，否则会抛 SecurityException 导致进程崩溃。 */
    private fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    init {
        // 构造时尚未申请权限，必须在授权后再执行；否则后台协程查询 content://sms 会直接崩。
        if (hasSmsPermission()) {
            reload()
            recategorizeVerificationCodes()
        }
    }

    /** 读取当前置顶的键集合（聚合行=threadId 字符串，单条行="m<messageId>"）。 */
    private fun pinnedKeys(): Set<String> =
        pinPrefs.getStringSet(KEY_PINNED, emptySet())!!.toSet()

    /** 切换某列表项的置顶状态，并触发列表重排。 */
    fun togglePin(pinKey: String) {
        scope.launch {
            val current = pinPrefs.getStringSet(KEY_PINNED, emptySet())!!.toMutableSet()
            if (current.contains(pinKey)) current.remove(pinKey) else current.add(pinKey)
            pinPrefs.edit().putStringSet(KEY_PINNED, current).apply()
            reload()
        }
    }

    fun reload() {
        if (!hasSmsPermission()) return
        scope.launch {
            _conversations.value = queryConversations()
            _dataVersion.value += 1
            // 跨 OEM 桌面角标：角标数 = 系统短信库未读数（READ=0 的总条数）。
            // 注意：ColorOS 16 第三方 App 无法直接写角标 Provider（签名权限限制），此处对其他 OEM 仍生效。
            BadgeManager.setCount(app, getTotalUnreadCount())
        }
    }

    /**
     * 统计系统短信库中所有未读短信条数（READ=0），作为桌面角标数字。
     * 在 IO 协程中执行；[reload] 刷新后会自动调用，无需额外触发。
     */
    fun getTotalUnreadCount(): Int {
        if (!hasSmsPermission()) return 0
        return try {
            var count = 0
            app.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.READ} = ?",
                arrayOf("0"),
                null
            )?.use { count = it.count }
            count
        } catch (e: Exception) {
            0
        }
    }

    fun persistIncoming(address: String, body: String, date: Long) {
        scope.launch {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, date)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
            val uri = app.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
            if (uri != null) {
                val id = ContentUris.parseId(uri)
                // 验证码短信重新归类到固定的“验证码”会话，保留原始发送人（address 不改写）。
                if (isVerificationCodeMessage(body)) {
                    val update = ContentValues().apply {
                        put(Telephony.Sms.THREAD_ID, VERIFICATION_THREAD_ID)
                    }
                    app.contentResolver.update(
                        Telephony.Sms.CONTENT_URI,
                        update,
                        "${Telephony.Sms._ID} = ?",
                        arrayOf(id.toString())
                    )
                }
            }
            reload()
        }
    }

    /**
     * 发送一条短信：调用指定 SIM 卡的 [SmsManager] 真正发出，并把已发送消息写入系统短信库
     * （仅默认短信应用可写库）。[subId] 为订阅 ID（[SubscriptionManager]），<=0 时使用默认卡。
     * 返回该会话的 threadId 以便跳转，失败返回 null。
     */
    fun sendMessage(address: String, body: String, subId: Int = -1): Long? {
        if (address.isBlank() || body.isBlank()) return null

        val smsManager = if (subId > 0) {
            SmsManager.getSmsManagerForSubscriptionId(subId)
        } else {
            SmsManager.getDefault()
        }
        val parts = smsManager.divideMessage(body)
        if (parts.size > 1) {
            smsManager.sendMultipartTextMessage(address, null, parts, null, null)
        } else {
            smsManager.sendTextMessage(address, null, body, null, null)
        }

        return try {
            val threadId = Telephony.Threads.getOrCreateThreadId(app, address)
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.BODY, body)
                put(Telephony.Sms.DATE, System.currentTimeMillis())
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
                put(Telephony.Sms.THREAD_ID, threadId)
                if (subId > 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
            }
            app.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
            reload()
            threadId
        } catch (e: Exception) {
            reload()
            null
        }
    }

    /**
     * 列出当前可用的 SIM 卡（订阅）。需要 [android.Manifest.permission.READ_PHONE_STATE]。
     * 单卡或无卡时返回对应列表（可能为空）。
     */
    fun getSimCards(): List<SimCard> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return emptyList()
        val sm = app.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        val active: List<SubscriptionInfo> = sm.activeSubscriptionInfoList ?: return emptyList()
        return active.map { info ->
            val name = (info.displayName?.toString()?.takeIf { it.isNotBlank() }
                ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() })
                ?: "SIM ${info.subscriptionId}"
            SimCard(subId = info.subscriptionId, displayName = name)
        }
    }

    /**
     * 构建首页列表（混合模型）：
     * - 验证码会话（[VERIFICATION_THREAD_ID]）恒聚合为一行；
     * - 手机号（[com.ink.sms.data.ContactResolver.isMobileNumber]）发来的短信聚合为一行；
     * - 其余（服务号 / 短码 / 一次性通知等）每条短信单独成行，不聚合。
     * 所有行合并后按「置顶优先 + 时间倒序」一起排序。
     */
    private fun queryConversations(): List<Conversation> {
        if (!hasSmsPermission()) return emptyList()
        return try {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID
        )
        val messages = mutableListOf<RawSms>()
        app.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            val idi = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val ti = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val ai = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bi = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val di = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val ri = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val si = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (cursor.moveToNext()) {
                messages.add(
                    RawSms(
                        id = cursor.getLong(idi),
                        threadId = cursor.getLong(ti),
                        address = cursor.getString(ai) ?: "",
                        body = cursor.getString(bi) ?: "",
                        date = cursor.getLong(di),
                        unread = cursor.getInt(ri) == 0,
                        subId = if (si >= 0) cursor.getInt(si) else -1
                    )
                )
            }
        }

        // 每个 thread 是否聚合：验证码会话恒聚合；其余仅当“是手机号”才聚合。
        val aggregateThread = LinkedHashMap<Long, Boolean>()
        for (m in messages) {
            if (aggregateThread.containsKey(m.threadId)) continue
            val isCode = m.threadId == VERIFICATION_THREAD_ID
            aggregateThread[m.threadId] = isCode || contactResolver.isMobileNumber(m.address)
        }

        val pinned = pinnedKeys()
        val result = mutableListOf<Conversation>()

        // 聚合行：每个 thread 一行（取最新一条为正文）
        for ((tid, agg) in aggregateThread) {
            if (!agg) continue
            val msgs = messages.filter { it.threadId == tid }
            val latest = msgs.first() // 已按时间倒序，首条即最新
            val isCode = tid == VERIFICATION_THREAD_ID
            val unread = msgs.count { it.unread }
            result.add(
                Conversation(
                    threadId = tid,
                    messageId = -1,
                    address = if (isCode) "" else latest.address,
                    snippet = latest.body,
                    date = latest.date,
                    read = unread == 0,
                    messageCount = msgs.size,
                    unreadCount = unread,
                    pinned = pinned.contains(tid.toString()),
                    displayName = if (isCode) {
                        "验证码"
                    } else {
                        contactResolver.resolveFriendlyName(latest.address)
                            ?: extractSmsSignature(latest.body)
                    },
                    isCode = isCode,
                    aggregated = true,
                    pinKey = tid.toString(),
                    subId = latest.subId
                )
            )
        }

        // 非聚合行：每条短信单独一行（不响应单击，仅左滑/长按）
        for (m in messages) {
            if (aggregateThread[m.threadId] == true) continue
            result.add(
                Conversation(
                    threadId = m.threadId,
                    messageId = m.id,
                    address = m.address,
                    snippet = m.body,
                    date = m.date,
                    read = !m.unread,
                    messageCount = 1,
                    unreadCount = if (m.unread) 1 else 0,
                    pinned = pinned.contains("m${m.id}"),
                    displayName = contactResolver.resolveFriendlyName(m.address)
                        ?: extractSmsSignature(m.body),
                    isCode = false,
                    aggregated = false,
                    pinKey = "m${m.id}",
                    subId = m.subId
                )
            )
        }

        // 置顶组在前（组内按时间倒序），未置顶组在后（按时间倒序）
        return result.sortedWith(
            compareByDescending<Conversation> { it.pinned }.thenByDescending { it.date }
        )
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 单条短信的轻量结构，仅供 [queryConversations] 内部聚合/拆分使用。 */
    private data class RawSms(
        val id: Long,
        val threadId: Long,
        val address: String,
        val body: String,
        val date: Long,
        val unread: Boolean,
        val subId: Int = -1
    )

    /**
     * 把指定会话下所有未读短信（系统短信库 READ=0）标记为已读（READ=1），并刷新列表。
     * 打开会话详情时调用，与系统短信 App 共享已读状态。验证码会话与其他会话逻辑一致。
     */
    fun markThreadRead(threadId: Long) {
        scope.launch {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            val affected = app.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(threadId.toString())
            )
            // 仅当确有未读行被置为已读（影响行数 > 0）才刷新，
            // 避免详情页监听 dataVersion 后反复触发 markThreadRead 造成无限刷新。
            if (affected > 0) reload()
        }
    }

    fun getMessages(threadId: Long): List<Message> {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID
        )
        val result = mutableListOf<Message>()
        app.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString()),
            "${Telephony.Sms.DATE} ASC"
        )?.use { cursor ->
            val idi = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
            val ti = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
            val ai = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bi = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val di = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val tyi = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            val ri = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
            val si = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            while (cursor.moveToNext()) {
                val address = cursor.getString(ai) ?: ""
                val body = cursor.getString(bi) ?: ""
                result.add(
                    Message(
                        id = cursor.getLong(idi),
                        threadId = cursor.getLong(ti),
                        address = address,
                        body = body,
                        date = cursor.getLong(di),
                        type = cursor.getInt(tyi),
                        read = cursor.getInt(ri) != 0,
                        subId = if (si >= 0) cursor.getInt(si) else -1,
                        displayName = contactResolver.resolveFriendlyName(address)
                            ?: extractSmsSignature(body)
                    )
                )
            }
        }
        return result
    }

    /**
     * 按 `_id` 读取单条短信（含显示名解析），供单条删除时写入回收站使用。
     */
    fun getMessageById(id: Long): Message? {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.READ,
            Telephony.Sms.SUBSCRIPTION_ID
        )
        app.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            "${Telephony.Sms._ID} = ?",
            arrayOf(id.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val ti = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val ai = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bi = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val di = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val tyi = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val ri = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                val si = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                val address = cursor.getString(ai) ?: ""
                val body = cursor.getString(bi) ?: ""
                return Message(
                    id = id,
                    threadId = cursor.getLong(ti),
                    address = address,
                    body = body,
                    date = cursor.getLong(di),
                    type = cursor.getInt(tyi),
                    read = cursor.getInt(ri) != 0,
                    subId = if (si >= 0) cursor.getInt(si) else -1,
                    displayName = contactResolver.resolveFriendlyName(address)
                        ?: extractSmsSignature(body)
                )
            }
        }
        return null
    }

    /**
     * 从系统短信库删除指定行（[id] 为 content://sms 中的 `_id`）。
     * 仅默认短信应用可写/删系统短信库，无需额外权限。删除后刷新会话列表。
     */
    fun deleteSystemMessage(id: Long) {
        app.contentResolver.delete(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id),
            null, null
        )
        reload()
    }

    /**
     * 把指定短信从验证码虚拟会话移出：按发送方 address 还原其真实归属会话，再刷新会话列表。
     * 真实 thread_id 取自该 address 已有的正常会话（查询系统短信库）；若发送方没有其它正常会话，
     * 则新生成一个不与 -1（验证码会话）冲突的负数 thread_id。
     * 用于清理被一次性迁移误归类到验证码会话的历史短信。
     */
    fun moveMessageOutOfVerification(id: Long, address: String) {
        scope.launch {
            val realThread = resolveRealThreadId(address)
            val values = ContentValues().apply { put(Telephony.Sms.THREAD_ID, realThread) }
            app.contentResolver.update(
                Telephony.Sms.CONTENT_URI,
                values,
                "${Telephony.Sms._ID} = ?",
                arrayOf(id.toString())
            )
            reload()
        }
    }

    /**
     * 解析发送方 address 的真实会话 thread_id：优先复用该 address 已有的正常会话（thread_id != -1）；
     * 不存在则生成新的负数 thread_id（不与验证码会话 -1 冲突，且同一 address 多次移出会复用同一 id）。
     */
    private fun resolveRealThreadId(address: String): Long {
        val proj = arrayOf(Telephony.Sms.THREAD_ID)
        app.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            proj,
            "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.THREAD_ID} != ?",
            arrayOf(address, VERIFICATION_THREAD_ID.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID))
            }
        }
        return -System.currentTimeMillis()
    }

    /**
     * 删除某会话下的全部短信行（按 thread_id），并刷新会话列表。
     * 回收站写入由调用方负责，这里只删系统短信库。
     */
    fun deleteThread(threadId: Long) {
        app.contentResolver.delete(
            Telephony.Sms.CONTENT_URI,
            "${Telephony.Sms.THREAD_ID} = ?",
            arrayOf(threadId.toString())
        )
        reload()
    }

    /**
     * 把回收站中的消息写回系统短信库（恢复）。写回后刷新会话列表。
     * 注意：写回的 `_id` 由系统重新分配，与 [RecycledMessage.originalId] 不同。
     */
    fun restoreMessage(msg: RecycledMessage) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, msg.address)
            put(Telephony.Sms.BODY, msg.body)
            put(Telephony.Sms.DATE, msg.date)
            put(Telephony.Sms.READ, if (msg.read) 1 else 0)
            put(Telephony.Sms.TYPE, msg.type)
            put(Telephony.Sms.THREAD_ID, msg.threadId)
        }
        app.contentResolver.insert(Telephony.Sms.CONTENT_URI, values)
        reload()
    }

    /**
     * 把历史验证码短信重新归类到固定的“验证码”会话（改写 thread_id，保留 address）。
     *
     * 使用**版本号**而非一次性布尔标记：每当验证码识别逻辑（[isVerificationCodeMessage] /
     * [extractVerificationCode]）改进后，把 [RECAT_VERSION] 自增即可让所有历史短信用新规则重新扫描，
     * 确保此前被旧逻辑漏判的短信（如「429406为您的登录验证码」这类数字在关键词之前的消息）能被纠正。
     * 新收到的验证码短信仍由 [persistIncoming] 即时归类，本方法只负责补扫存量。
     *
     * 注意：本方法会把「当前规则判定为验证码」的历史短信移入验证码会话，可能覆盖用户曾手动移出的操作；
     * 仅在 [RECAT_VERSION] 递增的那次启动执行，平时不重复扫描。
     */
    private fun recategorizeVerificationCodes() {
        val prefs = recatPrefs()
        if (prefs.getInt(KEY_RECAT_VERSION, 0) >= RECAT_VERSION) return
        if (!hasSmsPermission()) return
        scope.launch {
            try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.BODY,
                Telephony.Sms.THREAD_ID
            )
            app.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                val idi = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val bi = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val ti = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val ids = mutableListOf<Long>()
                while (cursor.moveToNext()) {
                    val tid = cursor.getLong(ti)
                    if (tid == VERIFICATION_THREAD_ID) continue
                    val body = cursor.getString(bi) ?: ""
                    if (isVerificationCodeMessage(body)) {
                        ids.add(cursor.getLong(idi))
                    }
                }
                ids.forEach { id ->
                    val v = ContentValues().apply {
                        put(Telephony.Sms.THREAD_ID, VERIFICATION_THREAD_ID)
                    }
                    app.contentResolver.update(
                        Telephony.Sms.CONTENT_URI,
                        v,
                        "${Telephony.Sms._ID} = ?",
                        arrayOf(id.toString())
                    )
                }
            }
            prefs.edit().putInt(KEY_RECAT_VERSION, RECAT_VERSION).apply()
            reload()
            } catch (e: Exception) {}
        }
    }

    /**
     * 自动清理过期的验证码短信：把归入验证码会话（[VERIFICATION_THREAD_ID]）、且距接收时间超过 1 天的消息，
     * 与「点击删除按钮」完全相同的流程处理 —— 先写入本地回收站（Room 副本），再从系统短信库删除原记录。
     * 在 [recategorizeVerificationCodes] 之后调用，确保先完成归类再清理。
     *
     * 触发时机：每次 App 启动（[init]）。个人自用场景下不做后台定时任务，下次启动即会补齐清理。
     */
    /**
     * 清理过期的验证码短信：把归入验证码会话（[VERIFICATION_THREAD_ID]）、且距接收时间超过 1 天的消息，
     * 与「点击删除按钮」完全相同的流程处理 —— 先写入本地回收站（Room 副本），再从系统短信库删除原记录。
     *
     * 触发时机改为**每次进入首页**（`ConversationListScreen` 经 [com.ink.sms.feature.conversation.ConversationListViewModel]
     * 调用），不再在 [init] 中随进程启动执行；在仓库自身的长生命周期 [scope] 中执行，确保删除即使离开页面也能完成。
     */
    fun autoCleanupVerificationCodes() {
        scope.launch {
            val cutoff = System.currentTimeMillis() - ONE_DAY_MS
            val expired = getMessages(VERIFICATION_THREAD_ID).filter { it.date <= cutoff }
            if (expired.isEmpty()) return@launch
            expired.forEach { msg ->
                recycleBinDao.insert(
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
                deleteSystemMessage(msg.id)
            }
            reload()
        }
    }

    private fun recatPrefs() =
        app.getSharedPreferences(RECAT_PREFS, Context.MODE_PRIVATE)

}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SmsRepositoryEntryPoint {
    fun smsRepository(): SmsRepository
}

private const val PIN_PREFS = "sms_pinned_conversations"
private const val KEY_PINNED = "pinned_thread_ids"
private const val RECAT_PREFS = "sms_verification_recat"

/** 历史验证码补扫逻辑的版本号：识别规则改进时递增，触发存量短信重新归类。 */
private const val KEY_RECAT_VERSION = "recat_version"
private const val RECAT_VERSION = 1

/** 验证码短信自动清理的过期阈值：接收超过 1 天即移入回收站并删除系统原记录。 */
private const val ONE_DAY_MS = 24L * 60 * 60 * 1000
