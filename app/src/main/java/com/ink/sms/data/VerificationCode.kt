package com.ink.sms.data

/** 验证码虚拟会话的固定 threadId（不会与系统真实 threadId（正整数）冲突）。 */
const val VERIFICATION_THREAD_ID: Long = -1L

/**
 * 验证码关键词（正则，内联 `(?i)` 不区分大小写）。仅匹配真正的验证码语境：
 * - 中文明确词：验证码 / 校验码 / 动态码 / 动态口令 / 随机码 / 安全码 / 登录码 / 短信码 / 提现码 / 取件码 / 口令；
 * - 英文明确词：verification / verif / otp / captcha / one-time code / your code；
 * - 单独的 `code` 必须**不是**某单词的前缀（其后不紧跟字母），否则会误命中 `CodeArts` / `Decode` 等普通英文词。
 *
 * 刻意不含宽泛的「码」单字，避免营销短信里的「码道」等被误触发。
 */
private val CODE_KEYWORD_REGEX = Regex(
    """(?i)(?:验证码|校验码|动态码|动态口令|随机码|安全码|登录码|短信码|提现码|取件码|口令|verification|verif|otp|captcha|one[- ]?time code|your code|code(?![a-zA-Z]))"""
)

private val DIGIT_REGEX = Regex("""\d{4,8}""")

/**
 * 从短信正文提取验证码：取「关键词之后紧邻窗口（30 字）内」的第一个 4-8 位数字。
 * 与归类判断共用同一套关键词与数字规则，保证高亮 / 复制 / 归类三者完全一致。
 *
 * **不做「任意位置兜底数字」**：否则营销短信（如含 code 的 CodeArts，或含「码」的词）会被误判，
 * 把积分数字（如 4000）当成验证码高亮 / 复制。规则：必须紧邻明确验证码关键词才有码。
 */
fun extractVerificationCode(body: String): String? {
    if (body.isBlank()) return null
    val window = 30
    var pos = 0
    while (pos < body.length) {
        val rest = body.substring(pos)
        val km = CODE_KEYWORD_REGEX.find(rest) ?: break
        val absFirst = pos + km.range.first
        val absLast = pos + km.range.last
        // 验证码数字可能紧邻关键词「之前」(如「429406为您的登录验证码」) 或「之后」(如「验证码是 653721」)，
        // 故在关键词前后各取一个 window 的片段内匹配第一个 4-8 位数字。
        val start = (absFirst - window).coerceAtLeast(0)
        val end = (absLast + 1 + window).coerceAtMost(body.length)
        DIGIT_REGEX.find(body.substring(start, end))?.let { return it.value }
        pos = absLast + 1
    }
    return null
}

/**
 * 判断一条短信是否为验证码短信：能提取出验证码即视为验证码。
 * 判定逻辑完全由 [extractVerificationCode] 派生，二者不可能分歧。
 */
fun isVerificationCodeMessage(body: String): Boolean = extractVerificationCode(body) != null
