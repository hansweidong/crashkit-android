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
    const val HPROF_MAX_AGE_MS = 24L * 60 * 60 * 1000
    const val TELEMETRY_MAX_CHARS = 9000

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

    fun allowHprofDump(dumpHprof: Boolean, labEnabled: Boolean): Boolean {
        return dumpHprof && labEnabled
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
        val full = if (Build.VERSION.SDK_INT < 22) 3000 else 4000
        if (type == CrashType.ANR_CRASH || type == CrashType.JAVA_ERROR || type == CrashType.JAVA_OOM) {
            return full.coerceAtMost(2000)
        }
        if (onMainThread) {
            return MAIN_THREAD_BLOCK_MS
        }
        return full
    }

    fun heavyProcDump(labEnabled: Boolean): Boolean = labEnabled
}
