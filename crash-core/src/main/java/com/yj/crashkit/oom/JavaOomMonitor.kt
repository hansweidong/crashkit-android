package com.yj.crashkit.oom

import android.app.Application
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.internal.DumpWriter
import com.yj.crashkit.resource.RecordInfo
import com.yj.crashkit.util.KitLog
import java.io.File

/**
 * 自研 OOM 预检：周期看 Java 堆占比、FD、线程数，连续超阈值后落盘并进管线。
 * 不依赖 KOOM。hprof 用 [Debug.dumpHprofData]（会暂停 VM，不在 UEH 里调用）。
 */
object JavaOomMonitor {
    private const val TAG = "JavaOomMonitor"
    private const val LOOP_MS = 15_000L
    private const val FIRST_DELAY_MS = 5_000L
    private const val HEAP_RATIO = 0.85f
    private const val FD_LIMIT = 800
    private const val THREAD_LIMIT = 800
    private const val OVER_NEEDED = 3
    private const val MAX_DUMPS = 3

    private val lock = Any()
    private var handler: Handler? = null

    @Volatile private var started = false
    @Volatile private var dumpHprof = false
    private var pipeline: CrashPipeline? = null
    private var overCount = 0
    private var dumpCount = 0

    @JvmStatic
    @Synchronized
    fun open(application: Application?, dumpHprof: Boolean, pipeline: CrashPipeline?) {
        if (application == null || pipeline == null) {
            return
        }
        this.pipeline = pipeline
        val allowHprof = CrashKitOnlinePolicy.allowHprofDump(dumpHprof, CrashKitLab.isEnabled())
        if (dumpHprof && !allowHprof) {
            KitLog.i(TAG, "skip dumpHprof: CrashKitLab.enable() was not called")
        }
        this.dumpHprof = allowHprof
        if (started) {
            return
        }
        started = true
        overCount = 0
        oomHandler().postDelayed({ tick() }, FIRST_DELAY_MS)
        KitLog.i(TAG, "OOM watch started dumpHprof=$allowHprof")
    }

    @JvmStatic
    @Synchronized
    fun close() {
        started = false
        handler?.removeCallbacksAndMessages(null)
        pipeline = null
        overCount = 0
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
                KitLog.i(TAG, "over $overCount/$OVER_NEEDED $reason")
                if (overCount >= OVER_NEEDED && dumpCount < MAX_DUMPS) {
                    overCount = 0
                    dumpCount += 1
                    report(reason)
                }
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "tick", t)
        }
        if (started) {
            oomHandler().postDelayed({ tick() }, LOOP_MS)
        }
    }

    private fun overReason(): String? {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        val max = rt.maxMemory().coerceAtLeast(1L)
        val ratio = used.toFloat() / max.toFloat()
        val fd = RecordInfo.fdCount()
        val threads = RecordInfo.taskCount()
        val parts = ArrayList<String>()
        if (ratio >= HEAP_RATIO) {
            parts.add("heap=${"%.2f".format(ratio)} used=$used max=$max")
        }
        if (fd >= FD_LIMIT) {
            parts.add("fd=$fd")
        }
        if (threads >= THREAD_LIMIT) {
            parts.add("threads=$threads")
        }
        return if (parts.isEmpty()) null else parts.joinToString("; ")
    }

    private fun report(reason: String) {
        val p = pipeline ?: return
        val rt = CrashKitRuntime.get() ?: return
        val stamp = System.currentTimeMillis()
        val summary = DumpWriter.writeText(rt.dumpDir, "oom-watch-$stamp.txt", reason)
        var hprof: File? = null
        if (dumpHprof) {
            val file = File(rt.dumpDir, "oom-watch-$stamp.hprof")
            try {
                val parent = file.parentFile
                if (parent != null && !parent.exists()) {
                    parent.mkdirs()
                }
                Debug.dumpHprofData(file.absolutePath)
                if (file.exists() && file.length() > 0) {
                    hprof = file
                }
            } catch (t: Throwable) {
                KitLog.e(TAG, "dumpHprofData failed", t)
            }
        }
        p.handleOom(hprof ?: summary, reason)
    }
}
