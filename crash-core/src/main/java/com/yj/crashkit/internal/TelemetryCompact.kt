package com.yj.crashkit.internal

import com.yj.crashkit.CrashKit
import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetryPayload
import com.yj.crashkit.CrashType
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * 把一次崩溃压成埋点扩展字段。默认硬顶 [MAX_CHARS] 个 Java 字符（UTF-16 code unit）。
 *
 * 丢：logcat、userLog、maps 全文、FD 列表、全线程栈、hprof。
 * 留：压缩栈；ANR 带压缩 traces.txt（去 maps）和最后一次 main_stack 样本。
 */
internal object TelemetryCompact {
    const val MAX_CHARS = 9000
    private const val DUMP_READ_MAX = 48 * 1024
    private const val EXCEPTION_MAX = 180
    private const val HISTORY_MAX = 96
    private const val EXT_MAX = 280
    private const val RES_MAX = 220
    private const val HEADER_RESERVE = 720
    private const val MAX_FRAMES = 36
    private const val KEEP_HEAD_FRAMES = 6

    fun build(record: CrashRecord, maxChars: Int = MAX_CHARS): CrashTelemetryPayload {
        val cap = maxChars.coerceAtLeast(256)
        val meta = readMeta(record.metaJson)
        val dump = stackRaw(record)
        val res = clip(resourceSnippet(record), RES_MAX)
        val exception = clip(
            firstNonBlank(meta["exception"].orEmpty(), firstLine(dump), record.type.wireName()),
            EXCEPTION_MAX,
        )
        val history = clip(recentHistory(meta["history"].orEmpty()), HISTORY_MAX)
        val ext = clip(compactExt(meta["ext"].orEmpty()), EXT_MAX)

        var stackBudget = (cap - HEADER_RESERVE - exception.length - history.length - ext.length - res.length)
            .coerceAtLeast(cap / 4)
        var last = payloadOf(
            record = record,
            meta = meta,
            exception = exception,
            history = history,
            ext = ext,
            res = res,
            stack = "",
            cut = false,
        )
        repeat(8) {
            val stack = compactStackFor(record, dump, stackBudget)
            last = payloadOf(
                record = record,
                meta = meta,
                exception = exception,
                history = history,
                ext = ext,
                res = res,
                stack = stack,
                cut = dump.length > stack.length || stackBudget < dump.length,
            )
            if (last.wireText.length <= cap) {
                return last
            }
            val overflow = last.wireText.length - cap
            stackBudget = (stackBudget - overflow - 48).coerceAtLeast(160)
        }
        return last.copy(wireText = hardCut(last.wireText, cap), truncated = true)
    }

    private fun compactStackFor(record: CrashRecord, dump: String, budget: Int): String {
        if (record.type != CrashType.ANR_CRASH) {
            return compactStack(dump, budget)
        }
        val tracesRaw = readBounded(findNamed(record, "traces.txt"), DUMP_READ_MAX)
        val mainRaw = lastAnrSample(readBounded(findNamed(record, "main_stack.txt"), DUMP_READ_MAX))
        if (tracesRaw.isEmpty() && mainRaw.isEmpty()) {
            return compactStack(dump, budget)
        }
        val tracesBudget = if (mainRaw.isEmpty()) budget else (budget * 6 / 10).coerceAtLeast(budget / 3)
        val traces = compactStack(tracesRaw, tracesBudget)
        val remain = (budget - traces.length - 16).coerceAtLeast(
            if (traces.isEmpty()) budget else budget / 4,
        )
        val main = compactStack(mainRaw, remain)
        val sb = StringBuilder(traces.length + main.length + 16)
        if (traces.isNotEmpty()) {
            sb.append("traces:\n").append(traces)
        }
        if (main.isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append("main:\n").append(main)
        }
        return if (sb.length <= budget) sb.toString() else sb.substring(0, budget)
    }

    internal fun compactStack(raw: String, maxChars: Int): String {
        if (raw.isEmpty() || maxChars <= 0) {
            return ""
        }
        val out = StringBuilder(maxChars.coerceAtMost(raw.length + 32))
        var skipped = 0
        var frames = 0
        var seenCause = false

        fun flushSkip() {
            if (skipped <= 0) {
                return
            }
            appendLine(out, "... ${skipped}fw", maxChars)
            skipped = 0
        }

        for (line in raw.split('\n')) {
            if (out.length >= maxChars) {
                break
            }
            val trimmed = line.trimEnd()
            if (trimmed.startsWith("maps:")) {
                break
            }
            if (trimmed.startsWith("CURRENT_LOGCAT:")) {
                break
            }
            if (trimmed.startsWith("Suppressed:")) {
                continue
            }
            if (isFrame(trimmed)) {
                val framework = isFrameworkFrame(trimmed)
                if (framework && frames >= KEEP_HEAD_FRAMES && !seenCause) {
                    skipped++
                    continue
                }
                if (frames >= MAX_FRAMES) {
                    skipped++
                    continue
                }
                flushSkip()
                if (!appendLine(out, clip(trimmed.trim(), 180), maxChars)) {
                    break
                }
                frames++
                continue
            }
            if (trimmed.startsWith("Caused by:")) {
                flushSkip()
                seenCause = true
                if (!appendLine(out, clip(trimmed, 220), maxChars)) {
                    break
                }
                continue
            }
            if (trimmed.isEmpty()) {
                continue
            }
            flushSkip()
            if (!appendLine(out, clip(trimmed, 220), maxChars)) {
                break
            }
        }
        flushSkip()
        if (out.length <= maxChars) {
            return out.toString().trimEnd()
        }
        return out.substring(0, maxChars)
    }

    private fun payloadOf(
        record: CrashRecord,
        meta: Map<String, String>,
        exception: String,
        history: String,
        ext: String,
        res: String,
        stack: String,
        cut: Boolean,
    ): CrashTelemetryPayload {
        val appVersion = meta["app_ver"].orEmpty()
        val osVersion = meta["os_ver"].orEmpty()
        val model = clip(meta["model"].orEmpty(), 40)
        val process = meta["pkg"].orEmpty().ifEmpty { meta["process"].orEmpty() }
        val threadId = meta["thread_id"].orEmpty()
        val uid = meta["uid"].orEmpty()
        val wire = encode(
            id = record.crashId,
            type = record.type,
            sdkVersion = CrashKit.VERSION,
            appVersion = appVersion,
            osVersion = osVersion,
            model = model,
            process = process,
            threadId = threadId,
            uid = uid,
            exception = exception,
            history = history,
            ext = ext,
            res = res,
            stack = stack,
            cut = cut,
        )
        return CrashTelemetryPayload(
            crashId = record.crashId,
            type = record.type,
            sdkVersion = CrashKit.VERSION,
            appVersion = appVersion,
            osVersion = osVersion,
            model = model,
            process = process,
            threadId = threadId,
            uid = uid,
            exception = exception,
            stack = stack,
            activityHistory = history,
            ext = ext,
            resource = res,
            truncated = cut,
            wireText = wire,
        )
    }

    private fun encode(
        id: String,
        type: CrashType,
        sdkVersion: String,
        appVersion: String,
        osVersion: String,
        model: String,
        process: String,
        threadId: String,
        uid: String,
        exception: String,
        history: String,
        ext: String,
        res: String,
        stack: String,
        cut: Boolean,
    ): String {
        val sb = StringBuilder(stack.length + 512)
        sb.append('{')
        fun field(key: String, value: String) {
            if (value.isEmpty()) {
                return
            }
            if (sb.length > 1) {
                sb.append(',')
            }
            sb.append('"').append(key).append("\":\"").append(escape(value)).append('"')
        }
        field("id", id)
        field("t", type.wireName())
        field("sv", sdkVersion)
        field("av", appVersion)
        field("os", osVersion)
        field("m", model)
        field("p", process)
        field("tid", threadId)
        field("uid", uid)
        field("e", exception)
        field("h", history)
        field("x", ext)
        field("r", res)
        field("s", stack)
        if (cut) {
            field("cut", "1")
        }
        sb.append('}')
        return sb.toString()
    }

    private fun readMeta(json: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val o = JSONObject(json)
            copy(o, out, "app_ver", "os_ver", "model", "pkg", "process", "thread_id", "uid", "guid", "history", "exception")
            val ext = o.optJSONObject("ext")
            if (ext != null) {
                out["ext"] = ext.toString()
            }
        } catch (_: Throwable) {
            fallbackScan(json, out)
        }
        return out
    }

    private fun fallbackScan(json: String, out: MutableMap<String, String>) {
        val keys = arrayOf(
            "app_ver", "os_ver", "model", "pkg", "process",
            "thread_id", "uid", "guid", "history", "exception",
        )
        for (key in keys) {
            val value = scanJsonString(json, key)
            if (value.isNotEmpty()) {
                out[key] = value
            }
        }
        val extStart = json.indexOf("\"ext\":{")
        if (extStart >= 0) {
            val from = json.indexOf('{', extStart)
            val end = json.indexOf('}', from)
            if (from >= 0 && end > from) {
                out["ext"] = json.substring(from, end + 1)
            }
        }
    }

    private fun scanJsonString(json: String, key: String): String {
        val needle = "\"$key\":\""
        val i = json.indexOf(needle)
        if (i < 0) {
            return ""
        }
        val start = i + needle.length
        val sb = StringBuilder()
        var j = start
        while (j < json.length) {
            val c = json[j]
            if (c == '\\' && j + 1 < json.length) {
                sb.append(json[j + 1])
                j += 2
                continue
            }
            if (c == '"') {
                break
            }
            sb.append(c)
            j++
        }
        return sb.toString()
    }

    private fun copy(o: JSONObject, out: MutableMap<String, String>, vararg keys: String) {
        for (key in keys) {
            val v = o.optString(key, "")
            if (v.isNotEmpty()) {
                out[key] = v
            }
        }
    }

    private fun stackRaw(record: CrashRecord): String {
        if (record.type == CrashType.ANR_CRASH) {
            val traces = readBounded(findNamed(record, "traces.txt"), DUMP_READ_MAX)
            val main = lastAnrSample(readBounded(findNamed(record, "main_stack.txt"), DUMP_READ_MAX))
            if (traces.isEmpty() && main.isEmpty()) {
                return readBounded(firstDump(record), DUMP_READ_MAX)
            }
            val sb = StringBuilder(traces.length + main.length + 16)
            if (traces.isNotEmpty()) {
                sb.append(traces)
            }
            if (main.isNotEmpty()) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                sb.append(main)
            }
            return sb.toString()
        }
        return readBounded(firstDump(record), DUMP_READ_MAX)
    }

    private fun lastAnrSample(raw: String): String {
        if (raw.isEmpty()) {
            return ""
        }
        val marker = "----- pid "
        val last = raw.lastIndexOf(marker)
        return if (last < 0) raw else raw.substring(last)
    }

    private fun firstDump(record: CrashRecord): File? {
        val traces = findNamed(record, "traces.txt")
        if (traces != null) {
            return traces
        }
        for (f in record.dumpFiles) {
            if (f.exists() && f.isFile && f.length() > 0L) {
                return f
            }
        }
        return null
    }

    private fun findNamed(record: CrashRecord, name: String): File? {
        return named(record.dumpFiles, name) ?: named(record.logFiles, name)
    }

    private fun resourceSnippet(record: CrashRecord): String {
        val oom = named(record.logFiles, "oom_lite.txt")
        if (oom != null) {
            return readBounded(oom, RES_MAX * 2).replace('\n', ' ').trim()
        }
        if (record.type != CrashType.JAVA_OOM) {
            return ""
        }
        val dump = firstDump(record) ?: return ""
        val text = readBounded(dump, RES_MAX * 2)
        return if (text.contains("heap_used=")) {
            text.replace('\n', ' ').trim()
        } else {
            ""
        }
    }

    private fun named(files: List<File>, name: String): File? {
        for (f in files) {
            if (f.name == name && f.exists()) {
                return f
            }
        }
        return null
    }

    private fun readBounded(file: File?, maxBytes: Int): String {
        if (file == null || !file.exists() || !file.isFile) {
            return ""
        }
        return try {
            val len = file.length().coerceAtMost(maxBytes.toLong()).toInt()
            val bytes = ByteArray(len)
            file.inputStream().use { input ->
                var off = 0
                while (off < len) {
                    val n = input.read(bytes, off, len - off)
                    if (n <= 0) {
                        break
                    }
                    off += n
                }
            }
            String(bytes, Charset.forName("UTF-8"))
        } catch (_: Throwable) {
            ""
        }
    }

    private fun compactExt(raw: String): String {
        if (raw.isEmpty() || raw == "{}") {
            return ""
        }
        val parts = ArrayList<String>()
        var used = 0
        try {
            val o = JSONObject(raw)
            val keys = o.keys()
            var n = 0
            while (keys.hasNext() && n < 8) {
                val key = keys.next()
                val value = o.optString(key, "")
                if (key.isEmpty() || value.isEmpty()) {
                    continue
                }
                val piece = clip(key, 24) + "=" + clip(value, 64)
                if (used + piece.length + 1 > EXT_MAX) {
                    break
                }
                parts.add(piece)
                used += piece.length + 1
                n++
            }
        } catch (_: Throwable) {
            return clip(raw, EXT_MAX)
        }
        return parts.joinToString(";")
    }

    private fun recentHistory(raw: String): String {
        if (raw.isEmpty()) {
            return ""
        }
        val parts = raw.split(" -> ")
        if (parts.size <= 4) {
            return raw
        }
        return parts.takeLast(4).joinToString(">")
    }

    private fun isFrame(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("at ") || t.startsWith("#") || t.startsWith("pc ")
    }

    private fun isFrameworkFrame(line: String): Boolean {
        val t = line.trim()
        val body = when {
            t.startsWith("at ") -> t.substring(3)
            else -> t
        }
        return body.startsWith("android.") ||
            body.startsWith("java.") ||
            body.startsWith("javax.") ||
            body.startsWith("jdk.") ||
            body.startsWith("kotlin.") ||
            body.startsWith("kotlinx.coroutines.internal.") ||
            body.startsWith("dalvik.") ||
            body.startsWith("com.android.") ||
            body.startsWith("libcore.") ||
            body.startsWith("sun.") ||
            body.contains("/system/") ||
            body.contains("/apex/") ||
            body.contains("/vendor/")
    }

    private fun appendLine(out: StringBuilder, line: String, maxChars: Int): Boolean {
        val need = line.length + 1
        if (out.length + need > maxChars) {
            return false
        }
        if (out.isNotEmpty()) {
            out.append('\n')
        }
        out.append(line)
        return true
    }

    private fun firstLine(text: String): String {
        val nl = text.indexOf('\n')
        return if (nl < 0) text else text.substring(0, nl)
    }

    private fun firstNonBlank(vararg values: String): String {
        for (v in values) {
            if (v.isNotEmpty()) {
                return v
            }
        }
        return ""
    }

    private fun clip(text: String, max: Int): String {
        if (text.length <= max) {
            return text
        }
        if (max <= 3) {
            return text.substring(0, max)
        }
        return text.substring(0, max - 1) + "…"
    }

    private fun hardCut(text: String, max: Int): String {
        if (text.length <= max) {
            return text
        }
        if (max <= 8) {
            return text.substring(0, max)
        }
        return text.substring(0, max - 6) + "…\"}"
    }

    private fun escape(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 32) {
                    sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        return sb.toString()
    }
}
