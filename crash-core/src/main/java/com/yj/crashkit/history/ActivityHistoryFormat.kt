package com.yj.crashkit.history

/**
 * 把链路上**相邻**的同一页生命周期收进括号。
 *
 * 内部顺序是时间从早到晚（栈底 → 栈顶）。上报用 [fromTop]，从栈顶往回写，
 * 超长时整段丢掉栈底旧页，不截断当前页类名。
 *
 * `R:SettingActivity>C:DebugActivity>S:DebugActivity>R:DebugActivity`
 * → compact：`SettingActivity(R)-DebugActivity(C:S:R)`
 * → fromTop：`DebugActivity(C:S:R)-SettingActivity(R)`
 *
 * 中间插了别的页就拆开：`A(C:S:R)-B(C)-A(D)-B(S:R)`。
 */
internal object ActivityHistoryFormat {
    const val SEP = "-"

    fun compact(raw: String): String {
        if (raw.isEmpty()) {
            return ""
        }
        val spans = ArrayList<Span>()
        for (token in tokenize(raw)) {
            absorb(spans, token)
        }
        return render(spans)
    }

    /**
     * 从栈顶（最近一页）往回拼路径。超出 [maxPages] 或 [maxChars] 时丢掉栈底，
     * 当前页始终完整保留。
     */
    fun fromTop(raw: String, maxPages: Int = Int.MAX_VALUE, maxChars: Int = Int.MAX_VALUE): String {
        val compact = compact(raw)
        if (compact.isEmpty()) {
            return ""
        }
        val pages = compact.split(SEP)
        val recent = if (pages.size <= maxPages) pages else pages.takeLast(maxPages)
        return fitFromTop(recent.asReversed(), maxChars)
    }

    fun render(spans: List<Span>): String {
        if (spans.isEmpty()) {
            return ""
        }
        val sb = StringBuilder()
        for (span in spans) {
            if (sb.isNotEmpty()) {
                sb.append(SEP)
            }
            span.appendTo(sb)
        }
        return sb.toString()
    }

    private fun fitFromTop(pages: List<String>, maxChars: Int): String {
        if (pages.isEmpty()) {
            return ""
        }
        var count = pages.size
        while (count > 1) {
            val text = pages.subList(0, count).joinToString(SEP)
            if (text.length <= maxChars) {
                return text
            }
            count--
        }
        return pages[0]
    }

    fun absorb(spans: MutableList<Span>, name: String, letter: Char) {
        val last = spans.lastOrNull()
        if (last != null && last.name == name) {
            last.add(letter)
            return
        }
        spans.add(Span(name, letter))
    }

    private fun absorb(spans: MutableList<Span>, token: String) {
        val parsed = parseToken(token) ?: return
        val last = spans.lastOrNull()
        if (last != null && last.name == parsed.name) {
            last.addAll(parsed.letters)
            return
        }
        spans.add(parsed)
    }

    private fun tokenize(raw: String): List<String> {
        val delim = when {
            raw.contains(" -> ") -> " -> "
            raw.contains(">") -> ">"
            else -> SEP
        }
        return raw.split(delim).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseToken(token: String): Span? {
        val open = token.indexOf('(')
        if (open > 0 && token.endsWith(')')) {
            val name = token.substring(0, open)
            val inside = token.substring(open + 1, token.length - 1).replace(":", "")
            if (name.isNotEmpty() && inside.isNotEmpty()) {
                return Span(name, inside)
            }
        }
        val colon = token.indexOf(':')
        if (colon == 1 && token.length > 2) {
            val letter = token[0]
            if (letter in "CSRPD") {
                return Span(token.substring(2), letter)
            }
        }
        return null
    }

    class Span(val name: String, letters: CharSequence) {
        constructor(name: String, letter: Char) : this(name, letter.toString())

        private val buf = StringBuilder(letters)

        val letters: String get() = buf.toString()

        fun add(letter: Char) {
            buf.append(letter)
        }

        fun addAll(more: String) {
            buf.append(more)
        }

        fun appendTo(sb: StringBuilder) {
            sb.append(name).append('(')
            for (i in 0 until buf.length) {
                if (i > 0) {
                    sb.append(':')
                }
                sb.append(buf[i])
            }
            sb.append(')')
        }
    }
}
