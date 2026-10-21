package com.ink.sms.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 号码 → 显示名 解析器。
 *
 * 数据源（优先级从高到低）：
 *  1. 系统通讯录（ContactsContract，需 READ_CONTACTS 权限，无权限时降级为空）
 *  2. 内置公共号码库（assets/public_numbers.json，离线、无需权限）
 *  3. 都查不到 → 返回原始号码
 *
 * 两个数据源都加载进内存 Map，冷启动一次，进程存活期内复用；
 * 系统联系人可在授权后调用 [reloadContacts] 刷新。不使用任何本地数据库。
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val app: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val contactMap = HashMap<String, String>() // normalized -> 联系人名
    private val publicMap = HashMap<String, String>()   // normalized -> 公共号码名
    private val lock = Any()

    init {
        loadPublicNumbers()
        reloadContacts()
    }

    /** 重新加载系统通讯录（授权后或联系人变更时调用）。无权限则清空联系人数。 */
    fun reloadContacts() {
        scope.launch {
            synchronized(lock) {
                contactMap.clear()
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_CONTACTS)
                    != PackageManager.PERMISSION_GRANTED
                ) return@synchronized

                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                app.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    val ni = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )
                    val di = cursor.getColumnIndexOrThrow(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )
                    while (cursor.moveToNext()) {
                        val key = normalize(cursor.getString(ni) ?: "")
                        val name = cursor.getString(di) ?: ""
                        if (key.isNotBlank() && name.isNotBlank()) contactMap[key] = name
                    }
                }
            }
        }
    }

    /** 解析显示名：命中返回名称，未命中返回原始号码。 */
    fun resolveDisplayName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val keys = candidateKeys(raw)
        synchronized(lock) {
            for (k in keys) contactMap[k]?.let { return it }
            for (k in keys) publicMap[k]?.let { return it }
        }
        return raw
    }

    /** 解析友好名：命中返回名称，未命中返回 null（UI 用 null 回退显示原号码）。 */
    fun resolveFriendlyName(raw: String?): String? {
        val name = resolveDisplayName(raw)
        return name.takeIf { it != raw }
    }

    /**
     * 是否为国内手机号：11 位、以 `1[3-9]` 开头，忽略 `+86` 国家码前缀。
     * 用于首页是否对某个号码聚合的判断（与服务号 / 短码区分）。
     */
    fun isMobileNumber(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val n = normalize(raw).removePrefix("86")
        return n.length == 11 && n.matches(Regex("^1[3-9]\\d{9}$"))
    }

    private fun loadPublicNumbers() {
        runCatching {
            app.assets.open("public_numbers.json").use { stream ->
                val text = stream.bufferedReader().readText()
                val arr = JSONArray(text)
                synchronized(lock) {
                    publicMap.clear()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val name = obj.getString("name")
                        val nums = obj.getJSONArray("numbers")
                        for (j in 0 until nums.length()) {
                            val key = normalize(nums.getString(j))
                            if (key.isNotBlank()) publicMap[key] = name
                        }
                    }
                }
            }
        }
    }

    private fun normalize(number: String): String =
        PhoneNumberUtils.normalizeNumber(number).replace(" ", "")

    /** 生成候选 key：基础 normalize 形式 + 去掉国家码前缀 86 的形式。 */
    private fun candidateKeys(raw: String): List<String> {
        val base = normalize(raw)
        val keys = mutableListOf(base)
        if (base.startsWith("86") && base.length > 11) {
            keys.add(base.removePrefix("86"))
        }
        return keys
    }
}
