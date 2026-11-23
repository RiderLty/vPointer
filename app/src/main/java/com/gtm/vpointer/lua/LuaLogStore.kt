package com.gtm.vpointer.lua

import android.content.Context

/**
 * Lua 悬浮日志的配置存储。
 *
 * 与光标样式（[com.gtm.vpointer.CursorIconStore]，走 SQLite settings 表）分开放：
 * 这里全是「界面上点一下就变」的轻量开关，没有跨进程查询需求，SharedPreferences
 * 更合适，也不必给已有的 DB 加 migration。
 */
class LuaLogStore(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 日志筛选规则（逗号分隔；普通词=前缀，`re:` 开头=正则）。空串=全部。 */
    var filter: String
        get() = sp.getString(K_FILTER, DEFAULT_FILTER) ?: DEFAULT_FILTER
        set(v) = sp.edit().putString(K_FILTER, v).apply()

    /** 显示行数 1–24 */
    var lines: Int
        get() = sp.getInt(K_LINES, DEFAULT_LINES).coerceIn(1, 24)
        set(v) = sp.edit().putInt(K_LINES, v.coerceIn(1, 24)).apply()

    /** 字号 sp，8–28 */
    var fontSp: Int
        get() = sp.getInt(K_FONT, DEFAULT_FONT_SP).coerceIn(8, 28)
        set(v) = sp.edit().putInt(K_FONT, v.coerceIn(8, 28)).apply()

    /** 背景不透明度 0–255，默认 190（约 75%） */
    var opacity: Int
        get() = sp.getInt(K_OPACITY, DEFAULT_OPACITY).coerceIn(0, 255)
        set(v) = sp.edit().putInt(K_OPACITY, v.coerceIn(0, 255)).apply()

    var x: Int
        get() = sp.getInt(K_X, DEFAULT_X)
        set(v) = sp.edit().putInt(K_X, v).apply()

    var y: Int
        get() = sp.getInt(K_Y, DEFAULT_Y)
        set(v) = sp.edit().putInt(K_Y, v).apply()

    /** 文字窗是否处于隐藏（缩起）状态 —— 按钮条仍然留着，随时可以点回来 */
    var hidden: Boolean
        get() = sp.getBoolean(K_HIDDEN, false)
        set(v) = sp.edit().putBoolean(K_HIDDEN, v).apply()

    companion object {
        const val PREFS_NAME = "lua_log"

        /** WS 路径：与既有日志 API 一致，固定 /ws */
        const val DEFAULT_PATH = "/ws"

        /** 默认不过滤：留空 = 全部日志（前缀/正则规则由用户按需填写） */
        const val DEFAULT_FILTER = ""
        const val DEFAULT_LINES = 6
        const val DEFAULT_FONT_SP = 12
        const val DEFAULT_OPACITY = 190
        const val DEFAULT_X = 24
        const val DEFAULT_Y = 240

        private const val K_FILTER = "filter"
        private const val K_LINES = "lines"
        private const val K_FONT = "font_sp"
        private const val K_OPACITY = "opacity"
        private const val K_X = "x"
        private const val K_Y = "y"
        private const val K_HIDDEN = "hidden"
    }
}

/**
 * 日志筛选：**前缀** 与 **正则** 两种规则混用。
 *
 * 语法（逗号分隔，中英文逗号都认）：
 *
 * | 写法 | 含义 |
 * |---|---|
 * | `liu` | 前缀匹配（先剥 `[lua]` 包装，再比 payload 开头；整行开头也认） |
 * | `[lua` | 整行以 `[lua` 开头 → 显示全部标准 Lua 日志 |
 * | `re:hb` | 正则，对整行与 payload 各做一次 `containsMatchIn` |
 * | `liu,re:档位\\s*\\d` | 任一命中即显示 |
 * | （留空） | 不过滤，全部显示 |
 *
 * 正则写错编译失败时**退化为字面前缀**，不会让整条规则失效或崩服务。
 *
 * 前缀为什么要剥包装（lua-overlay 踩过的坑）：设备输出是 `[lua] liu[recoil] …`，
 * 脚本内容在包装**之后**，直接 `line.startsWith("liu")` 永远不命中。
 */
object LuaLogFilter {

    sealed interface Rule {
        fun matches(line: String, payload: String): Boolean
    }

    private class PrefixRule(private val prefix: String) : Rule {
        override fun matches(line: String, payload: String): Boolean =
            line.startsWith(prefix) || payload.startsWith(prefix)
    }

    private class RegexRule(private val regex: Regex) : Rule {
        override fun matches(line: String, payload: String): Boolean =
            regex.containsMatchIn(line) || (payload !== line && regex.containsMatchIn(payload))
    }

    /** 正则规则的标记前缀 */
    const val REGEX_PREFIX = "re:"

    private val SEPARATORS = Regex("[,，\\s]+")

    fun compile(raw: String): List<Rule> =
        raw.split(SEPARATORS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                if (token.startsWith(REGEX_PREFIX)) {
                    val body = token.removePrefix(REGEX_PREFIX).trim()
                    if (body.isEmpty()) {
                        PrefixRule(token)
                    } else {
                        runCatching { RegexRule(Regex(body)) }.getOrElse { PrefixRule(body) }
                    }
                } else {
                    PrefixRule(token)
                }
            }

    /** 剥掉设备给日志加的标签，返回脚本自己的 payload */
    fun payloadOf(line: String): String {
        if (!line.startsWith("[")) return line
        val end = line.indexOf("] ")
        if (end <= 0) return line
        return line.substring(end + 2)
    }

    fun matches(line: String, rules: List<Rule>): Boolean {
        if (rules.isEmpty()) return true
        val payload = payloadOf(line)
        return rules.any { it.matches(line, payload) }
    }
}
