package com.ink.sms.util

/**
 * 从短信正文**开头**提取签名身份，作为「号码无联系人」时的兜底显示名。
 *
 * 行业规则：国内企业 / 验证码 / 通知短信常在正文开头（个别在结尾）以 `【签名】` / `【签名】` /
 * `[签名]` / `(签名)` 包裹发送方身份（如 `【中国移动】`），该签名需在运营商侧报备。
 * 这里只匹配开头，并限定内容长度（≤20、不含换行），以降低对普通消息里括号的误判。
 *
 * @return 签名文本（已去除外层括号），无命中返回 null。
 */
fun extractSmsSignature(body: String?): String? {
    if (body.isNullOrBlank()) return null
    val match = Regex("""^\s*[【\[（]([^】\]）\n]{1,20})[】\]）]""").find(body) ?: return null
    val sig = match.groupValues[1].trim()
    return sig.takeIf { it.isNotBlank() }
}
