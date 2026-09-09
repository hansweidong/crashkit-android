package com.yj.crashkit.internal

import com.yj.crashkit.CrashKit
import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetryPayload
import com.yj.crashkit.CrashType
import com.yj.crashkit.anr.AnrJavaDump
import com.yj.crashkit.history.ActivityHistoryFormat
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

/**
 * 把一次崩溃压成埋点扩展字段。默认硬顶 [MAX_CHARS] 个 Java 字符（UTF-16 code unit）。
 *
 * 丢：logcat、userLog、maps 全文、FD 列表、hprof。
 * 留：压缩栈；ANR 带 anr_error.log（sys）、主线程（main）、全线程里有定位价值的部分（threads）。
 */
internal object TelemetryCompact {
    const val MAX_CHARS = 9000
    private const val DUMP_READ_MAX = 48 * 1024
    private const val EXCEPTION_MAX = 180
    /** ANR 的 `e` 要能放下主线程头 + 前几帧 + `... Nfw`，180 会把现场截成一句 ANR。 */
    private const val ANR_EXCEPTION_MAX = 1200
    /** 按整页计长；超了丢栈底，不截断栈顶类名。6 个常见 Activity 名大约 160 字。 */
    private const val HISTORY_MAX = 192
    private const val RECENT_PAGES = 6
    private const val EXT_MAX = 280
    private const val RES_MAX = 220
    private const val HEADER_RESERVE = 860
    private const val MAX_FRAMES = 36
    private const val KEEP_HEAD_FRAMES = 6

    fun build(record: CrashRecord, maxChars: Int = MAX_CHARS): CrashTelemetryPayload {
        val cap = maxChars.coerceAtLeast(256)
        val meta = readMeta(record.metaJson)
        val dump = stackRaw(record)
        val res = clip(resourceSnippet(record), RES_MAX)
        val exception = exceptionOf(record, meta, dump)
        val history = ActivityHistoryFormat.fromTop(
            meta["history"].orEmpty(),
            maxPages = RECENT_PAGES,
            maxChars = HISTORY_MAX,
        )
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
        val sysRaw = usableAnrText(readBounded(findNamed(record, CrashFiles.ANR_ERROR_LOG), DUMP_READ_MAX))
        val mainFile = readBounded(findNamed(record, CrashFiles.ANR_MAIN_STACK), DUMP_READ_MAX)
        val tracesRaw = usableTraces(
            readBounded(findNamed(record, CrashFiles.ANR_TRACES), DUMP_READ_MAX),
            hasBetterJava = usableAnrText(mainFile).isNotEmpty() || sysRaw.isNotEmpty(),
        )
        val threadsRaw = readBounded(findNamed(record, CrashFiles.ANR_THREADS), DUMP_READ_MAX)
        val mainRaw = lastAnrSample(mainFile, keepAllJava = tracesRaw.isEmpty() && threadsRaw.isEmpty())
        if (sysRaw.isEmpty() && tracesRaw.isEmpty() && mainRaw.isEmpty() && threadsRaw.isEmpty()) {
            return compactStack(usableAnrText(dump), budget)
        }
        val sysBudget = if (sysRaw.isEmpty()) 0 else (budget / 5).coerceAtLeast(280)
        val tracesBudget = if (tracesRaw.isEmpty()) 0 else (budget / 5).coerceAtLeast(280)
        val threadsBudget = if (threadsRaw.isEmpty()) {
            0
        } else {
            ((budget - sysBudget - tracesBudget) / 2).coerceAtLeast(budget / 4)
        }
        val sys = compactStack(sysRaw, sysBudget)
        val traces = compactStack(tracesRaw, tracesBudget)
        val threads = AnrJavaDump.compactInterestingThreads(threadsRaw, threadsBudget)
        val remain = (budget - sys.length - traces.length - threads.length - 32).coerceAtLeast(budget / 5)
        val main = compactStack(mainRaw, remain)
        val sb = StringBuilder(sys.length + traces.length + threads.length + main.length + 32)
        if (sys.isNotEmpty()) {
            sb.append("sys:\n").append(sys)
        }
        if (main.isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append("main:\n").append(main)
        }
        if (threads.isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append("threads:\n").append(threads)
        }
        if (traces.isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append("traces:\n").append(traces)
        }
        return if (sb.length <= budget) sb.toString() else sb.substring(0, budget)
    }

    /**
     * ANR 的 `exception` / 埋点 `e` 用压缩后的主线程栈，方便宿主把 ANR 当一条「异常」展示。
     * 没有 `----- main` 时退回 shortMsg（历史补报只有系统 traces 的情况）。
     */
    private fun exceptionOf(record: CrashRecord, meta: Map<String, String>, dump: String): String {
        if (record.type == CrashType.ANR_CRASH) {
            val mainFile = readBounded(findNamed(record, CrashFiles.ANR_MAIN_STACK), DUMP_READ_MAX)
            val mainRaw = lastAnrSample(mainFile, keepAllJava = true)
            val compact = compactStack(mainRaw, ANR_EXCEPTION_MAX)
            if (compact.contains("----- main")) {
                return compact
            }
        }
        return clip(
            firstNonBlank(meta["exception"].orEmpty(), firstLine(dump), record.type.wireName()),
            EXCEPTION_MAX,
        )
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
        val mem = MemSnapshot.fromMeta(meta, res)
        val processName = meta["process"].orEmpty()
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
            mem = mem,
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
            crashTimeMs = mem.crashTimeMs,
            memoryTotal = mem.totalMb,
            memoryAllocate = mem.javaAllocMb,
            memoryUsage = MemSnapshot.usageMb(mem),
            heapUsedMb = mem.javaUsedMb,
            heapMaxMb = mem.javaMaxMb,
            heapPct = mem.heapPct,
            pssMb = mem.pssMb,
            pssKb = mem.pssKb,
            nativeHeapKb = mem.nativeHeapKb,
            vmRssKb = mem.vmRssKb,
            vmSizeKb = mem.vmSizeKb,
            fdCount = mem.fd,
            threadCount = mem.threads,
            processName = processName,
            isInBg = mem.inBg,
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
        mem: MemSnapshot.Snapshot,
    ): String {
        val sb = StringBuilder(stack.length + 640)
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
        if (mem.crashTimeMs > 0L) {
            field("ct", mem.crashTimeMs.toString())
        }
        field("mt", mem.totalMb)
        field("ma", mem.javaAllocMb)
        field("mu", MemSnapshot.usageMb(mem))
        field("hp", mem.heapPct)
        field("pss", mem.pssMb)
        field("rss", mem.vmRssKb)
        sb.append('}')
        return sb.toString()
    }

    private fun readMeta(json: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val o = JSONObject(json)
            copy(
                o,
                out,
                "app_ver", "os_ver", "model", "pkg", "process", "thread_id", "uid", "guid",
                "history", "exception", "crash_time_ms", "launch_time_ms", "is_in_bg",
                "mem_total_mb", "mem_java_used_mb", "mem_java_alloc_mb", "mem_java_max_mb",
                "heap_pct", "mem_pss_mb", "mem_pss_kb", "mem_native_kb",
                "vm_rss_kb", "vm_size_kb", "fd_count", "thread_count",
            )
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
            "crash_time_ms", "launch_time_ms", "is_in_bg",
            "mem_total_mb", "mem_java_used_mb", "mem_java_alloc_mb", "mem_java_max_mb",
            "heap_pct", "mem_pss_mb", "mem_pss_kb", "mem_native_kb",
            "vm_rss_kb", "vm_size_kb", "fd_count", "thread_count",
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
            val sys = usableAnrText(readBounded(findNamed(record, CrashFiles.ANR_ERROR_LOG), DUMP_READ_MAX))
            val mainFile = readBounded(findNamed(record, CrashFiles.ANR_MAIN_STACK), DUMP_READ_MAX)
            val traces = usableTraces(
                readBounded(findNamed(record, CrashFiles.ANR_TRACES), DUMP_READ_MAX),
                hasBetterJava = usableAnrText(mainFile).isNotEmpty() || sys.isNotEmpty(),
            )
            val threads = readBounded(findNamed(record, CrashFiles.ANR_THREADS), DUMP_READ_MAX)
            val main = lastAnrSample(mainFile, keepAllJava = traces.isEmpty() && threads.isEmpty())
            if (sys.isEmpty() && traces.isEmpty() && main.isEmpty() && threads.isEmpty()) {
                return usableAnrText(readBounded(firstDump(record), DUMP_READ_MAX))
            }
            val sb = StringBuilder(sys.length + traces.length + threads.length + main.length + 16)
            if (sys.isNotEmpty()) {
                sb.append(sys)
            }
            if (main.isNotEmpty()) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                sb.append(main)
            }
            if (threads.isNotEmpty()) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                sb.append(threads)
            }
            if (traces.isNotEmpty()) {
                if (sb.isNotEmpty()) {
                    sb.append('\n')
                }
                sb.append(traces)
            }
            return sb.toString()
        }
        return readBounded(firstDump(record), DUMP_READ_MAX)
    }

    private fun lastAnrSample(raw: String, keepAllJava: Boolean = false): String {
        val text = usableAnrText(raw)
        if (text.isEmpty()) {
            return ""
        }
        val mainMark = "----- main "
        val mainIdx = text.indexOf(mainMark)
        if (mainIdx >= 0) {
            val sampled = text.indexOf("\n----- sampled", mainIdx)
            return if (sampled < 0) text.substring(mainIdx) else text.substring(mainIdx, sampled)
        }
        if (keepAllJava) {
            return text
        }
        val marker = "----- pid "
        val last = text.lastIndexOf(marker)
        return if (last < 0) text else text.substring(last)
    }

    private fun usableAnrText(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty() || t == "[]" || t == "main:\n[]") {
            return ""
        }
        return raw
    }

    /** 本 SDK 的 native traces 是 watchdog unwind，不能定位主线程卡顿。有 Java/系统信息时丢掉。 */
    private fun usableTraces(raw: String, hasBetterJava: Boolean): String {
        val text = usableAnrText(raw)
        if (text.isEmpty()) {
            return ""
        }
        val kitNative = text.contains("*** CrashKit ANR traces ***")
        val hasJavaFrames = text.contains(" at ") || text.contains("\tat ")
        if (kitNative && !hasJavaFrames && hasBetterJava) {
            return ""
        }
        return text
    }

    private fun firstDump(record: CrashRecord): File? {
        val traces = findNamed(record, CrashFiles.ANR_TRACES)
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
        val oom = named(record.logFiles, CrashFiles.OOM_LITE)
        if (oom != null) {
            return readBounded(oom, RES_MAX * 2).replace('\n', ' ').trim()
        }
        if (record.type == CrashType.ANR_CRASH) {
            val threads = readBounded(findNamed(record, CrashFiles.ANR_THREADS), 512)
            if (threads.isNotEmpty()) {
                val head = threads.lineSequence().take(3).joinToString(" ").trim()
                if (head.isNotEmpty()) {
                    return clip(head, RES_MAX)
                }
            }
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
