package com.yj.crashkit.internal

import android.os.Debug
import com.yj.crashkit.resource.RecordInfo
import java.io.FileInputStream

/**
 * 崩溃 / ANR 现场内存快照。数字写进 [MetaJson]，上报只回放，不再用上传时的 [Runtime]。
 *
 * OOM 路径不调 [Debug.getPss] / native heap / `File.list()`（会再申请内存）。
 */
internal object MemSnapshot {
    private const val MEMINFO = "/proc/meminfo"
    private const val BUF = 512
    @Volatile
    private var cachedTotalMb: String = ""

    class Snapshot(
        val crashTimeMs: Long,
        val totalMb: String,
        val javaUsedMb: String,
        val javaAllocMb: String,
        val javaMaxMb: String,
        val heapPct: String,
        val pssMb: String,
        val pssKb: String,
        val nativeHeapKb: String,
        val vmRssKb: String,
        val vmSizeKb: String,
        val fd: String,
        val threads: String,
        val inBg: Boolean?,
    )

    fun capture(oom: Boolean, nowMs: Long, inBg: Boolean?): Snapshot {
        val heap = Runtime.getRuntime()
        val used = heap.totalMemory() - heap.freeMemory()
        val alloc = heap.totalMemory()
        val max = heap.maxMemory().coerceAtLeast(1L)
        val proc = ProcStatus.read()
        var pssKb = -1L
        var nativeBytes = -1L
        var fd = -1
        if (!oom) {
            pssKb = readPssKb()
            nativeBytes = readNativeHeapBytes()
            fd = RecordInfo.fdCount()
        }
        val pssMb = kbToMb(pssKb)
        return Snapshot(
            crashTimeMs = nowMs,
            totalMb = deviceTotalMb(),
            javaUsedMb = bytesToMb(used),
            javaAllocMb = bytesToMb(alloc),
            javaMaxMb = bytesToMb(max),
            heapPct = (used * 100L / max).toString(),
            pssMb = pssMb,
            pssKb = positive(pssKb),
            nativeHeapKb = positive(if (nativeBytes > 0L) nativeBytes / 1024L else -1L),
            vmRssKb = positive(proc.rssKb),
            vmSizeKb = positive(proc.vssKb),
            fd = if (fd >= 0) fd.toString() else "",
            threads = if (proc.threads >= 0) proc.threads.toString() else "",
            inBg = inBg,
        )
    }

    fun fromMeta(meta: Map<String, String>, resource: String): Snapshot {
        val parsed = parseCounters(resource)
        return Snapshot(
            crashTimeMs = meta["crash_time_ms"]?.toLongOrNull() ?: 0L,
            totalMb = meta["mem_total_mb"].orEmpty(),
            javaUsedMb = first(meta["mem_java_used_mb"], parsed.javaUsedMb),
            javaAllocMb = first(meta["mem_java_alloc_mb"], parsed.javaAllocMb),
            javaMaxMb = first(meta["mem_java_max_mb"], parsed.javaMaxMb),
            heapPct = first(meta["heap_pct"], parsed.heapPct),
            pssMb = first(meta["mem_pss_mb"], parsed.pssMb),
            pssKb = first(meta["mem_pss_kb"], parsed.pssKb),
            nativeHeapKb = first(meta["mem_native_kb"], parsed.nativeHeapKb),
            vmRssKb = first(meta["vm_rss_kb"], parsed.vmRssKb),
            vmSizeKb = first(meta["vm_size_kb"], parsed.vmSizeKb),
            fd = first(meta["fd_count"], parsed.fd),
            threads = first(meta["thread_count"], parsed.threads),
            inBg = parseBg(meta["is_in_bg"]),
        )
    }

    fun deviceTotalMb(): String {
        val hit = cachedTotalMb
        if (hit.isNotEmpty()) {
            return hit
        }
        val mb = readMemTotalMb()
        if (mb.isNotEmpty()) {
            cachedTotalMb = mb
        }
        return mb
    }

    fun usageMb(snap: Snapshot): String {
        return first(snap.pssMb, snap.javaUsedMb)
    }

    fun headerLine(snap: Snapshot): String {
        val sb = StringBuilder(96)
        sb.append("mem:")
        appendPart(sb, "ram", snap.totalMb)
        appendPart(sb, "pss", snap.pssMb.ifEmpty { null }?.let { "${it}MB" } ?: snap.pssKb.takeIf { it.isNotEmpty() }?.let { "${it}kB" })
        val heap = if (snap.javaUsedMb.isNotEmpty() && snap.javaMaxMb.isNotEmpty()) {
            val pct = if (snap.heapPct.isNotEmpty()) "(${snap.heapPct}%)" else ""
            "${snap.javaUsedMb}/${snap.javaMaxMb}MB$pct"
        } else {
            null
        }
        appendPart(sb, "heap", heap)
        appendPart(sb, "rss", snap.vmRssKb.takeIf { it.isNotEmpty() }?.let { "${it}kB" })
        appendPart(sb, "vss", snap.vmSizeKb.takeIf { it.isNotEmpty() }?.let { "${it}kB" })
        appendPart(sb, "fd", snap.fd)
        appendPart(sb, "th", snap.threads)
        appendPart(sb, "native", snap.nativeHeapKb.takeIf { it.isNotEmpty() }?.let { "${it}kB" })
        return if (sb.length > 4) sb.toString() else ""
    }

    internal fun bytesToMb(bytes: Long): String {
        if (bytes <= 0L) {
            return "0"
        }
        return kbToMb(bytes / 1024L)
    }

    internal fun kbToMb(kb: Long): String {
        if (kb <= 0L) {
            return ""
        }
        val whole = kb / 1024L
        val frac = (kb % 1024L) * 10L / 1024L
        return if (frac == 0L) whole.toString() else "$whole.$frac"
    }

    internal fun parseCounters(text: String): Snapshot {
        if (text.isEmpty()) {
            return empty(0L)
        }
        val usedBytes = numberAfter(text, "heap_used=")
        val maxBytes = numberAfter(text, "heap_max=")
        val javaUsed = numberAfter(text, "java_used=")
        val javaMax = numberAfter(text, "java_max=")
        val used = if (usedBytes > 0L) usedBytes else javaUsed
        val max = if (maxBytes > 0L) maxBytes else javaMax
        val pct = numberAfter(text, "heap_pct=")
        val pss = numberAfter(text, "pss_kb=").takeIf { it > 0L }
            ?: numberAfter(text, "pss=")
        val rss = numberAfter(text, "VmRSS=").takeIf { it > 0L }
            ?: numberAfter(text, "rss_kb=")
        val vss = numberAfter(text, "VmSize=")
        val fd = numberAfter(text, "fd=")
        val threads = numberAfter(text, "Threads=").takeIf { it > 0L }
            ?: numberAfter(text, "java_threads=")
        val native = numberAfter(text, "native_heap=")
        return Snapshot(
            crashTimeMs = 0L,
            totalMb = "",
            javaUsedMb = if (used > 0L) bytesToMb(used) else "",
            javaAllocMb = "",
            javaMaxMb = if (max > 0L) bytesToMb(max) else "",
            heapPct = when {
                pct > 0L -> pct.toString()
                used > 0L && max > 0L -> (used * 100L / max).toString()
                else -> ""
            },
            pssMb = kbToMb(pss),
            pssKb = positive(pss),
            nativeHeapKb = if (native > 0L) (native / 1024L).toString() else "",
            vmRssKb = positive(rss),
            vmSizeKb = positive(vss),
            fd = positive(fd),
            threads = positive(threads),
            inBg = null,
        )
    }

    internal fun numberAfter(text: String, key: String): Long {
        val i = text.indexOf(key)
        if (i < 0) {
            return -1L
        }
        var j = i + key.length
        while (j < text.length) {
            val c = text[j]
            if (c == ' ' || c == '\t' || c == ':' || c == '=') {
                j++
            } else {
                break
            }
        }
        if (j >= text.length || text[j] < '0' || text[j] > '9') {
            return -1L
        }
        var value = 0L
        while (j < text.length) {
            val c = text[j]
            if (c < '0' || c > '9') {
                break
            }
            value = value * 10 + (c - '0')
            j++
        }
        return value
    }

    private fun empty(now: Long) = Snapshot(
        crashTimeMs = now,
        totalMb = "",
        javaUsedMb = "",
        javaAllocMb = "",
        javaMaxMb = "",
        heapPct = "",
        pssMb = "",
        pssKb = "",
        nativeHeapKb = "",
        vmRssKb = "",
        vmSizeKb = "",
        fd = "",
        threads = "",
        inBg = null,
    )

    private fun readPssKb(): Long {
        return try {
            Debug.getPss()
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readNativeHeapBytes(): Long {
        return try {
            Debug.getNativeHeapAllocatedSize()
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readMemTotalMb(): String {
        val buf = ByteArray(BUF)
        val n = try {
            FileInputStream(MEMINFO).use { it.read(buf) }
        } catch (_: Throwable) {
            -1
        }
        if (n <= 0) {
            return ""
        }
        val text = String(buf, 0, n)
        val kb = numberAfter(text, "MemTotal:")
        return kbToMb(kb)
    }

    private fun parseBg(raw: String?): Boolean? {
        val v = raw?.trim().orEmpty()
        return when {
            v == "1" || v.equals("true", ignoreCase = true) -> true
            v == "0" || v.equals("false", ignoreCase = true) -> false
            else -> null
        }
    }

    private fun first(vararg values: String?): String {
        for (v in values) {
            if (!v.isNullOrEmpty()) {
                return v
            }
        }
        return ""
    }

    private fun positive(n: Long): String {
        return if (n > 0L) n.toString() else ""
    }

    private fun appendPart(sb: StringBuilder, key: String, value: String?) {
        if (value.isNullOrEmpty()) {
            return
        }
        sb.append(' ').append(key).append('=').append(value)
    }
}
