package com.yj.crashkit.oom

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.internal.OomLite
import com.yj.crashkit.internal.ProcStatus
import com.yj.crashkit.internal.ReportQuota
import com.yj.crashkit.resource.RecordInfo
import com.yj.crashkit.util.KitLog
import java.io.File

/**
 * OOM 预检，触发侧对齐 KOOM `OOMMonitor`，采集侧只写计数快照。
 *
 * 1. 每 [CrashKitOnlinePolicy.OOM_LOOP_INTERVAL_MS] 看 Java 堆占比 / FD / 线程 / VSS
 * 2. 堆判据要求**处于上涨阶段**（`ratio >= 上次 - gap`），回落即清零，避免把稳定高位误判成泄漏
 * 3. 连续 [CrashKitOnlinePolicy.OOM_OVER_NEEDED] 次命中才上报
 * 4. 上报前查磁盘余量；次数按版本落盘限次（[ReportQuota]）
 *
 * **不做 hprof。** `Debug.dumpHprofData` 会 suspend 整个 VM 约 20s，几乎必然自己触发一次真 ANR；
 * KOOM 的 `suspend/fork/resume` 子进程 dump 需要非平凡 native 与 ART 版本适配，本 SDK 不实现。
 */
object JavaOomMonitor {
    private const val TAG = "JavaOomMonitor"
    private const val FIRST_DELAY_MS = 5_000L
    private const val QUOTA_FILE = "oom_quota.txt"

    private val lock = Any()
    private var handler: Handler? = null

    @Volatile private var started = false
    private var pipeline: CrashPipeline? = null
    private var overCount = 0
    private var lastHeapRatio = 0f
    private var quota: ReportQuota? = null

    @JvmStatic
    @Synchronized
    fun open(application: Application?, pipeline: CrashPipeline?) {
        if (application == null || pipeline == null) {
            return
        }
        this.pipeline = pipeline
        if (started) {
            return
        }
        started = true
        overCount = 0
        lastHeapRatio = 0f
        oomHandler().postDelayed({ tick() }, FIRST_DELAY_MS)
        KitLog.i(TAG, "OOM watch started interval=${CrashKitOnlinePolicy.OOM_LOOP_INTERVAL_MS}ms (no hprof)")
    }

    /** 兼容旧签名。hprof 已下线，[dumpHprof] 一律忽略。 */
    @JvmStatic
    fun open(application: Application?, dumpHprof: Boolean, pipeline: CrashPipeline?) {
        if (dumpHprof) {
            KitLog.i(TAG, "dumpHprof is retired: Debug.dumpHprofData freezes the VM ~20s, ignoring")
        }
        open(application, pipeline)
    }

    @JvmStatic
    @Synchronized
    fun close() {
        started = false
        handler?.removeCallbacksAndMessages(null)
        pipeline = null
        overCount = 0
        lastHeapRatio = 0f
    }

    private fun oomHandler(): Handler {
        val existing = handler
        if (existing != null) {
            return existing
        }
        synchronized(lock) {
            var created = handler
            if (created == null) {
                val thread = HandlerThread("CrashKit-OOM")
                thread.start()
                created = Handler(thread.looper)
                handler = created
            }
            return created
        }
    }

    private fun tick() {
        if (!started) {
            return
        }
        try {
            val reason = overReason()
            if (reason == null) {
                overCount = 0
            } else {
                overCount += 1
                KitLog.i(TAG, "over $overCount/${CrashKitOnlinePolicy.OOM_OVER_NEEDED} $reason")
                if (overCount >= CrashKitOnlinePolicy.OOM_OVER_NEEDED) {
                    overCount = 0
                    report(reason)
                }
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "tick", t)
        }
        if (started) {
            oomHandler().postDelayed({ tick() }, CrashKitOnlinePolicy.OOM_LOOP_INTERVAL_MS)
        }
    }

    private fun overReason(): String? {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val max = rt.maxMemory().coerceAtLeast(1L)
        val ratio = used.toFloat() / max.toFloat()
        val rising = heapRising(ratio, lastHeapRatio)
        lastHeapRatio = ratio

        val status = ProcStatus.read()
        val fd = RecordInfo.fdCount()
        val threads = if (status.threads > 0) status.threads else RecordInfo.taskCount()

        val parts = ArrayList<String>(4)
        if (ratio >= CrashKitOnlinePolicy.OOM_HEAP_RATIO && rising) {
            parts.add("heap=${(ratio * 100).toInt()}% used=$used max=$max")
        }
        if (fd >= CrashKitOnlinePolicy.OOM_FD_LIMIT) {
            parts.add("fd=$fd")
        }
        if (threads >= CrashKitOnlinePolicy.OOM_THREAD_LIMIT) {
            parts.add("threads=$threads")
        }
        if (vssOverLimit(status.vssKb)) {
            parts.add("vss_kb=${status.vssKb}")
        }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    private fun report(reason: String) {
        val p = pipeline ?: return
        val rt = CrashKitRuntime.get() ?: return
        val free = usableSpace(rt.dumpDir)
        if (free in 0 until CrashKitOnlinePolicy.OOM_MIN_FREE_BYTES) {
            KitLog.i(TAG, "skip OOM report: usable space $free < ${CrashKitOnlinePolicy.OOM_MIN_FREE_BYTES}")
            return
        }
        if (!quotaOf(rt).tryAcquire(rt.appVersion, System.currentTimeMillis())) {
            KitLog.i(TAG, "skip OOM report: quota used up for version ${rt.appVersion}")
            return
        }
        val snapshot = OomLite.writeWatchSnapshot(
            rt.dumpDir,
            "oom-watch-${System.currentTimeMillis()}.txt",
            reason,
        )
        p.handleOom(snapshot, reason)
    }

    private fun quotaOf(rt: CrashKitRuntime): ReportQuota {
        val existing = quota
        if (existing != null) {
            return existing
        }
        synchronized(lock) {
            var created = quota
            if (created == null) {
                created = ReportQuota(
                    File(rt.dumpDir, QUOTA_FILE),
                    CrashKitOnlinePolicy.OOM_MAX_REPORTS_PER_VERSION,
                    CrashKitOnlinePolicy.OOM_QUOTA_PERIOD_MS,
                )
                quota = created
            }
            return created
        }
    }

    private fun usableSpace(dir: File): Long {
        return try {
            dir.usableSpace
        } catch (_: Throwable) {
            -1L
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            started = false
            pipeline = null
            overCount = 0
            lastHeapRatio = 0f
            quota = null
        }
    }

    /**
     * 对齐 KOOM `HeapOOMTracker`：占比只要没比上一轮跌超过
     * [CrashKitOnlinePolicy.OOM_HEAP_RATIO_GAP]，就算还在涨。首轮 [last] 为 0，直接算上涨。
     */
    internal fun heapRising(ratio: Float, last: Float): Boolean {
        if (last <= 0f) {
            return true
        }
        return ratio >= last - CrashKitOnlinePolicy.OOM_HEAP_RATIO_GAP
    }

    /** 64 位进程地址空间用不完，不看 VSS。 */
    internal fun vssOverLimit(vssKb: Long): Boolean {
        if (vssKb <= 0L || is64Bit()) {
            return false
        }
        return vssKb >= CrashKitOnlinePolicy.OOM_VSS_LIMIT_KB
    }

    private fun is64Bit(): Boolean {
        return try {
            android.os.Process.is64Bit()
        } catch (_: Throwable) {
            false
        }
    }
}
