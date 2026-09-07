package com.yj.crashkit.internal

import android.os.Build
import com.yj.crashkit.CrashType

/**
 * 线上默认策略。加重诊断必须先 [com.yj.crashkit.CrashKitLab.enable]。
 */
internal object CrashKitOnlinePolicy {
    const val MIN_SAMPLE_INTERVAL_MS = 200L
    const val ONLINE_LOGCAT_LINES = 500
    const val LAB_LOGCAT_LINES = 2000
    const val CUSTOM_LOGCAT_LINES = 200
    const val MAIN_THREAD_BLOCK_MS = 2000
    const val MAX_PENDING_FILES = 20
    const val PENDING_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val DUMP_DIR_MAX_BYTES = 64L * 1024 * 1024

    /** hprof 已下线，这个只用来清理旧版本 SDK 在 dump 目录里留下的 `.hprof`。 */
    const val HPROF_MAX_AGE_MS = 24L * 60 * 60 * 1000
    const val TELEMETRY_MAX_CHARS = 9000

    const val ANR_FOREGROUND_MSG_THRESHOLD_MS = -2000L
    const val ANR_BACKGROUND_MSG_THRESHOLD_MS = -10000L
    const val ANR_CHECK_ERROR_INTERVAL_MS = 500L
    const val ANR_CHECK_ERROR_COUNT = 40

    /**
     * `ApplicationExitInfo` 补报与 SIGQUIT 现场上报的互斥窗口。
     * 同一次 ANR 落在这个窗口内视为重复，只保留先上报的那一份。
     */
    const val ANR_EXIT_INFO_DEDUPE_MS = 60_000L
    const val ANR_EXIT_INFO_MAX_ENTRIES = 8
    const val ANR_EXIT_INFO_TRACE_MAX_BYTES = 48 * 1024

    // OOM 预检阈值，对齐 KOOM OOMMonitorConfig 的默认量级
    const val OOM_LOOP_INTERVAL_MS = 5_000L
    const val OOM_HEAP_RATIO = 0.85f

    /** 占比比上一轮跌超过这个值就认为不在上涨阶段，计数清零。KOOM `HEAP_RATIO_THRESHOLD_GAP`。 */
    const val OOM_HEAP_RATIO_GAP = 0.05f
    const val OOM_FD_LIMIT = 800
    const val OOM_THREAD_LIMIT = 800

    /** 32 位进程 4GB 地址空间，留出余量。64 位进程不检查。 */
    const val OOM_VSS_LIMIT_KB = 3_650_000L
    const val OOM_OVER_NEEDED = 3
    const val OOM_MAX_REPORTS_PER_VERSION = 3
    const val OOM_QUOTA_PERIOD_MS = 15L * 24 * 60 * 60 * 1000
    const val OOM_MIN_FREE_BYTES = 16L * 1024 * 1024

    fun sampleInterval(requestedMs: Long, labEnabled: Boolean): Long {
        val requested = if (requestedMs < 10) 1000L else requestedMs
        if (labEnabled) {
            return requested
        }
        if (requested != Long.MAX_VALUE && requested < MIN_SAMPLE_INTERVAL_MS) {
            return MIN_SAMPLE_INTERVAL_MS
        }
        return requested
    }

    fun shouldSampleMainThread(intervalMs: Long): Boolean {
        return intervalMs != Long.MAX_VALUE && intervalMs >= 10L
    }

    fun allowInlinePolling(inline: Boolean, labEnabled: Boolean): Boolean {
        return inline && labEnabled
    }

    fun logcatLines(type: CrashType, labEnabled: Boolean): Int {
        if (type == CrashType.JAVA_OOM || type == CrashType.ANR_CRASH) {
            return 0
        }
        if (labEnabled) {
            return LAB_LOGCAT_LINES
        }
        if (type == CrashType.JAVA_ERROR) {
            return CUSTOM_LOGCAT_LINES
        }
        return ONLINE_LOGCAT_LINES
    }

    fun blockerWaitMs(type: CrashType, onMainThread: Boolean): Int {
        val full = 4000
        if (type == CrashType.ANR_CRASH) {
            return 0
        }
        if (type == CrashType.JAVA_ERROR || type == CrashType.JAVA_OOM) {
            return full.coerceAtMost(2000)
        }
        if (onMainThread) {
            return MAIN_THREAD_BLOCK_MS
        }
        return full
    }

    fun heavyProcDump(labEnabled: Boolean): Boolean = labEnabled
}
