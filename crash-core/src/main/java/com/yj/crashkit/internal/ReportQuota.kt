package com.yj.crashkit.internal

import com.yj.crashkit.util.KitLog
import java.io.File
import java.nio.charset.Charset

/**
 * 按版本落盘的上报限次，对应 KOOM 的 `analysisMaxTimesPerVersion` / `analysisPeriodPerVersion`。
 *
 * 只放在内存里的计数进程重启就清零，一台持续高内存的设备会每次冷启都采一次。
 * 这里把「版本 + 已用次数 + 计次起点」写进一行文本，换版本或过期后自动重置。
 */
internal class ReportQuota(
    private val file: File,
    private val maxCount: Int,
    private val periodMs: Long,
) {
    private val lock = Any()

    fun tryAcquire(version: String, nowMs: Long): Boolean {
        if (maxCount <= 0) {
            return false
        }
        val key = sanitize(version)
        synchronized(lock) {
            var count = 0
            var since = nowMs
            val state = read()
            if (state != null && state.version == key && nowMs - state.since <= periodMs) {
                count = state.count
                since = state.since
            }
            if (count >= maxCount) {
                return false
            }
            write(key, count + 1, since)
            return true
        }
    }

    internal fun usedCount(version: String, nowMs: Long): Int {
        val state = read() ?: return 0
        if (state.version != sanitize(version) || nowMs - state.since > periodMs) {
            return 0
        }
        return state.count
    }

    private class State(val version: String, val count: Int, val since: Long)

    private fun read(): State? {
        if (!file.isFile) {
            return null
        }
        return try {
            val parts = file.readText(UTF8).trim().split('|')
            if (parts.size < 3) {
                null
            } else {
                State(parts[0], parts[1].toInt(), parts[2].toLong())
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun write(version: String, count: Int, since: Long) {
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.writeText("$version|$count|$since", UTF8)
        } catch (t: Throwable) {
            KitLog.e(TAG, "write ${file.name}", t)
        }
    }

    private fun sanitize(version: String): String {
        if (version.isEmpty()) {
            return "unknown"
        }
        return version.replace('|', '_').replace('\n', '_')
    }

    companion object {
        private const val TAG = "ReportQuota"
        private val UTF8: Charset = Charset.forName("UTF-8")
    }
}
