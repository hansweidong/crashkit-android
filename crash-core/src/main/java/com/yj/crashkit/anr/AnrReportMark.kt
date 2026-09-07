package com.yj.crashkit.anr

import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.util.KitLog
import java.io.File
import java.nio.charset.Charset

/**
 * 记录 SIGQUIT 路径已经上报过的 ANR 时刻。
 *
 * [ExitInfoAnrCollector] 下次启动读 `ApplicationExitInfo` 时，落在
 * [CrashKitOnlinePolicy.ANR_EXIT_INFO_DEDUPE_MS] 窗口内的条目视为同一次 ANR，直接跳过，
 * 免得一次 ANR 被现场上报和历史补报各打一遍。
 */
internal object AnrReportMark {
    private const val TAG = "AnrReportMark"
    private const val FILE = "anr_marks.txt"
    private const val KEEP = 8
    private val UTF8: Charset = Charset.forName("UTF-8")

    fun mark(dir: File, timestampMs: Long) {
        try {
            val kept = ArrayList<Long>(KEEP + 1)
            kept.addAll(read(dir))
            kept.add(timestampMs)
            while (kept.size > KEEP) {
                kept.removeAt(0)
            }
            val file = File(dir, FILE)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.writeText(kept.joinToString("\n"), UTF8)
        } catch (t: Throwable) {
            KitLog.e(TAG, "mark", t)
        }
    }

    fun read(dir: File): List<Long> {
        val file = File(dir, FILE)
        if (!file.isFile) {
            return emptyList()
        }
        return try {
            file.readText(UTF8).lineSequence()
                .mapNotNull { it.trim().toLongOrNull() }
                .toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** [timestampMs] 是否已经被现场上报覆盖。 */
    fun coveredBy(marks: List<Long>, timestampMs: Long): Boolean {
        for (mark in marks) {
            if (kotlin.math.abs(mark - timestampMs) <= CrashKitOnlinePolicy.ANR_EXIT_INFO_DEDUPE_MS) {
                return true
            }
        }
        return false
    }
}
