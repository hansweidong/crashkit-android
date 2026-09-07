package com.yj.crashkit.anr

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.yj.crashkit.internal.CrashFiles
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.internal.DumpWriter
import com.yj.crashkit.internal.ProcessName
import com.yj.crashkit.util.KitLog
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ANR 的历史补报（API 30+）。
 *
 * SIGQUIT 旁路只能覆盖「进程还活着、handler 跑得完」的 ANR。进程被系统直接杀掉的 fatal ANR
 * 走不到那条路径，是纯漏报；`ApplicationExitInfo(REASON_ANR)` 是唯一能补齐、也是唯一能和
 * Play Console ANR 口径对齐的途径。
 *
 * 每次启动只补报最新一条，且要同时满足：
 * - 时间戳新于本地游标（同一条不重复报）
 * - 不落在 [AnrReportMark] 的窗口内（不和现场上报撞车）
 */
internal object ExitInfoAnrCollector {
    private const val TAG = "ExitInfoAnrCollector"
    private const val CURSOR_FILE = "exitinfo_cursor.txt"
    private val UTF8: Charset = Charset.forName("UTF-8")
    private val started = AtomicBoolean(false)

    fun start(context: Context, pipeline: CrashPipeline) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        if (!started.compareAndSet(false, true)) {
            return
        }
        val worker = Thread({
            try {
                collect(context, pipeline)
            } catch (t: Throwable) {
                KitLog.e(TAG, "collect", t)
            }
        }, "CrashKit-ExitInfo")
        worker.isDaemon = true
        worker.start()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun collect(context: Context, pipeline: CrashPipeline) {
        val dumpDir = CrashKitRuntime.get()?.dumpDir ?: context.cacheDir ?: return
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
        val reasons = try {
            am.getHistoricalProcessExitReasons(
                context.packageName,
                0,
                CrashKitOnlinePolicy.ANR_EXIT_INFO_MAX_ENTRIES,
            )
        } catch (t: Throwable) {
            KitLog.w(TAG, "getHistoricalProcessExitReasons", t)
            return
        }
        if (reasons.isNullOrEmpty()) {
            return
        }
        val processName = ProcessName.current(context)
        val cursor = readCursor(dumpDir)
        val marks = AnrReportMark.read(dumpDir)
        var newest: ApplicationExitInfo? = null
        var maxTs = cursor
        for (info in reasons) {
            if (info.reason != ApplicationExitInfo.REASON_ANR) {
                continue
            }
            if (processName.isNotEmpty() && info.processName != processName) {
                continue
            }
            val ts = info.timestamp
            if (ts > maxTs) {
                maxTs = ts
            }
            if (ts <= cursor || AnrReportMark.coveredBy(marks, ts)) {
                continue
            }
            if (newest == null || ts > newest.timestamp) {
                newest = info
            }
        }
        val target = newest
        if (target == null) {
            // 没有要报的，游标可以直接推过去
            if (maxTs > cursor) {
                writeCursor(dumpDir, maxTs)
            }
            return
        }
        KitLog.i(TAG, "historical ANR ts=${target.timestamp} process=${target.processName}")
        if (!report(dumpDir, pipeline, target)) {
            KitLog.i(TAG, "report dropped, keep cursor at $cursor for next launch")
            return
        }
        // 必须报完才推游标。先推的话，这条被丢掉后下次启动也不会再报，直接永久丢数据
        if (maxTs > cursor) {
            writeCursor(dumpDir, maxTs)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun report(dumpDir: File, pipeline: CrashPipeline, info: ApplicationExitInfo): Boolean {
        val description = buildString {
            append("ApplicationExitInfo REASON_ANR\n")
            append("timestamp=").append(info.timestamp).append('\n')
            append("process=").append(info.processName).append('\n')
            append("importance=").append(info.importance).append('\n')
            append("pss_kb=").append(info.pss).append('\n')
            append("rss_kb=").append(info.rss).append('\n')
            val desc = info.description
            if (!desc.isNullOrEmpty()) {
                append("description=").append(desc).append('\n')
            }
        }
        val errorLog = DumpWriter.writeText(dumpDir, CrashFiles.ANR_ERROR_LOG, description)
        val traces = readTraces(info)?.let {
            DumpWriter.writeText(dumpDir, CrashFiles.ANR_TRACES, it)
        }
        val shortMsg = "ANR (exit info): " + (info.description ?: "no description")
        return pipeline.handleHistoricalAnr(shortMsg, traces, errorLog)
    }

    /** 系统在 `REASON_ANR` 上会带一份完整 traces，比我们自己抓的主线程栈更权威。 */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun readTraces(info: ApplicationExitInfo): String? {
        return try {
            info.traceInputStream?.use { input ->
                val cap = CrashKitOnlinePolicy.ANR_EXIT_INFO_TRACE_MAX_BYTES
                val buf = ByteArray(cap)
                var off = 0
                while (off < cap) {
                    val n = input.read(buf, off, cap - off)
                    if (n <= 0) {
                        break
                    }
                    off += n
                }
                if (off <= 0) null else String(buf, 0, off, UTF8)
            }
        } catch (t: Throwable) {
            KitLog.w(TAG, "traceInputStream", t)
            null
        }
    }

    private fun readCursor(dir: File): Long {
        val file = File(dir, CURSOR_FILE)
        if (!file.isFile) {
            return 0L
        }
        return try {
            file.readText(UTF8).trim().toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        }
    }

    private fun writeCursor(dir: File, value: Long) {
        try {
            val file = File(dir, CURSOR_FILE)
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.writeText(value.toString(), UTF8)
        } catch (t: Throwable) {
            KitLog.e(TAG, "writeCursor", t)
        }
    }

    internal fun resetForTest() {
        started.set(false)
    }
}
