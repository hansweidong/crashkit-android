package com.yj.crashkit.internal

import android.os.Looper
import com.yj.crashkit.CrashCallback
import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashType
import com.yj.crashkit.ReportStage
import com.yj.crashkit.nativecrash.NativeCrashBridge
import com.yj.crashkit.reporter.CrashReporter
import com.yj.crashkit.resource.RecordInfo
import com.yj.crashkit.util.KitLog
import com.yj.crashkit.util.StackTraceFormatter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 崩溃主路径：pre → 落盘 → crashCallback → 三阶段 reporter → after → Blocker。
 */
class CrashPipeline(private val runtime: CrashKitRuntime) {
    private val blocker = CrashBlocker()
    private val once = AtomicBoolean(false)
    private val anrOnce = AtomicBoolean(false)
    private val exitInfoOnce = AtomicBoolean(false)
    private val resendOnce = AtomicBoolean(false)

    fun handleJava(@Suppress("UNUSED_PARAMETER") thread: Thread?, throwable: Throwable?) {
        if (!runtime.gate.isEnabled()) {
            return
        }
        if (!once.compareAndSet(false, true)) {
            return
        }
        NativeCrashBridge.markHandled()
        val tid = CrashKitRuntime.myTid()
        runtime.setCrashThreadId(tid)
        val crashId = StackTraceFormatter.newCrashId()
        runtime.setCurrentCrashId(crashId)
        val type = if (throwable is OutOfMemoryError) {
            CrashType.JAVA_OOM
        } else {
            CrashType.JAVA_CRASH
        }
        // OOM 现场堆已耗尽，走 [OomLite] 的预分配缓冲，别再申请 StringBuilder / String / ByteArray
        val dump = if (type == CrashType.JAVA_OOM) {
            OomLite.writeStack(runtime.dumpDir, crashId, throwable)
        } else {
            DumpWriter.writeStack(runtime.dumpDir, crashId, throwable)
        }
        val syslog = captureSyslog(type, crashId)
        val shortMsg = if (throwable == null) {
            "java crash"
        } else {
            throwable.javaClass.name + ": " + throwable.message
        }
        runPipeline(type, crashId, dump, null, syslog, shortMsg, tid, false)
    }

    fun handleNative(dumpPath: String?) {
        if (!runtime.gate.isEnabled()) {
            NativeCrashBridge.notifyJavaDone()
            return
        }
        if (!once.compareAndSet(false, true)) {
            NativeCrashBridge.notifyJavaDone()
            return
        }
        val crashId = StackTraceFormatter.newCrashId()
        runtime.setCurrentCrashId(crashId)
        runtime.setCrashThreadId(CrashKitRuntime.myTid())
        val dump = renameDump(dumpPath, "$crashId.dmp")
        val syslog = captureSyslog(CrashType.NATIVE_CRASH, crashId)
        try {
            runPipeline(
                CrashType.NATIVE_CRASH, crashId, dump, null, syslog,
                "native crash", CrashKitRuntime.myTid(), true,
            )
        } finally {
            NativeCrashBridge.notifyJavaDone()
        }
    }

    fun handleCustom(type: CrashType?, throwable: Throwable?) {
        CrashKitExceptionDeduper.mark(throwable)
        handleCustom(type, StackTraceFormatter.fromThrowable(throwable), CrashKitRuntime.myTid())
    }

    fun handleCustom(type: CrashType?, stack: String?, threadId: Int) {
        if (!runtime.gate.isEnabled() || pipelineBusy()) {
            return
        }
        val resolved = type ?: CrashType.JAVA_ERROR
        val crashId = StackTraceFormatter.newCrashId()
        runtime.setCurrentCrashId(crashId)
        runtime.setCrashThreadId(threadId)
        val dump = DumpWriter.writeStack(runtime.dumpDir, crashId, stack)
        val syslog = captureSyslog(resolved, crashId)
        var shortMsg = stack ?: "custom"
        if (shortMsg.length > 180) {
            shortMsg = shortMsg.substring(0, 180)
        }
        runPipeline(resolved, crashId, dump, null, syslog, shortMsg, threadId, false)
    }

    /** [traces] 现场上报时是全线程 Java 栈 [CrashFiles.ANR_THREADS]；历史补报才是系统 traces。 */
    fun handleAnr(shortMsg: String?, mainStack: File?, errorLog: File?, traces: File?, syslog: File?) {
        if (!runtime.gate.isEnabled()) {
            return
        }
        if (!anrOnce.compareAndSet(false, true)) {
            KitLog.i(TAG, "ANR already reported, skip")
            return
        }
        val crashId = StackTraceFormatter.newCrashId()
        runtime.setCurrentCrashId(crashId)
        runtime.setCrashThreadId(1)
        runPipeline(
            CrashType.ANR_CRASH, crashId, mainStack, traces, syslog,
            shortMsg ?: "ANR", 1, false, errorLog,
        )
    }

    /**
     * `ApplicationExitInfo(REASON_ANR)` 的历史补报。
     *
     * 和 [handleAnr] 各用一把闸：一次进程生命周期里允许「补报上次的 + 现场这次的」各一条，
     * 但各自都只有一条。
     *
     * **不触发宿主的 [CrashCallback] 三钩子。** 那三个钩子的语义是「此刻正在崩溃」，
     * 宿主实现里可能有收尾、结束页面甚至自杀进程的动作；这里是上一次进程的历史记录，
     * 在冷启动阶段重放会把宿主的启动流程搞坏。历史补报只走 reporter / telemetrySink。
     */
    fun handleHistoricalAnr(shortMsg: String?, traces: File?, errorLog: File?): Boolean {
        if (!runtime.gate.isEnabled()) {
            return false
        }
        if (!exitInfoOnce.compareAndSet(false, true)) {
            KitLog.i(TAG, "historical ANR already reported, skip")
            return false
        }
        val crashId = StackTraceFormatter.newCrashId()
        runtime.setCurrentCrashId(crashId)
        runtime.setCrashThreadId(1)
        runPipeline(
            CrashType.ANR_CRASH, crashId, traces, null, null,
            shortMsg ?: "ANR", 1, false, errorLog,
            invokeHostCallbacks = false,
        )
        return true
    }

    fun handleOom(dump: File?, reason: String?) {
        if (!runtime.gate.isEnabled() || dump == null) {
            return
        }
        val crashId = StackTraceFormatter.newCrashId()
        runtime.setCurrentCrashId(crashId)
        runPipeline(
            CrashType.JAVA_OOM, crashId, dump, null, null,
            reason ?: "JAVA_OOM", CrashKitRuntime.myTid(), false,
        )
    }

    private fun pipelineBusy(): Boolean = false

    private fun runPipeline(
        type: CrashType,
        crashId: String,
        dump: File?,
        symbol: File?,
        syslog: File?,
        shortMsg: String,
        tid: Int,
        nativeCrash: Boolean,
        extraLog: File? = null,
        invokeHostCallbacks: Boolean = true,
    ) {
        val dumpPath = path(dump)
        val symbolPath = path(symbol)
        val logPath = path(syslog)
        val cb: CrashCallback? = if (invokeHostCallbacks) runtime.getCrashCallback() else null
        try {
            cb?.preCrashCallback(nativeCrash, dumpPath, symbolPath, logPath)
        } catch (t: Throwable) {
            KitLog.e(TAG, "preCrashCallback", t)
        }

        val json = MetaJson.build(runtime, type, crashId, shortMsg, tid)
        val dumps = ArrayList<File>()
        addIfExists(dumps, dump)
        addIfExists(dumps, symbol)
        val logs = ArrayList<File>()
        addIfExists(logs, syslog)
        addIfExists(logs, extraLog)
        if (type != CrashType.ANR_CRASH) {
            for (extra in RecordInfo.dumpForCrash(runtime.dumpDir, type)) {
                addIfExists(logs, extra)
            }
        }
        for (p in runtime.snapshotUserLogs()) {
            addIfExists(logs, File(p))
        }

        try {
            cb?.crashCallback(crashId, nativeCrash, dumpPath, symbolPath, logPath)
        } catch (t: Throwable) {
            KitLog.e(TAG, "crashCallback", t)
        }
        for (p in runtime.snapshotUserLogs()) {
            addIfExists(logs, File(p))
        }

        val record = CrashRecord(crashId, type, json, dumps, unique(logs, dumps))
        PendingStore.save(runtime.dumpDir, record)

        blocker.preBlock(3)
        emitAll(record, unblock = true)

        try {
            cb?.afterCrashCallback(crashId, nativeCrash, dumpPath, symbolPath, logPath)
        } catch (t: Throwable) {
            KitLog.e(TAG, "afterCrashCallback", t)
        }

        val waitMs = CrashKitOnlinePolicy.blockerWaitMs(type, onMainThread())
        blocker.waitForUnblock(waitMs)
    }

    private fun captureSyslog(type: CrashType, crashId: String): File? {
        val lines = CrashKitOnlinePolicy.logcatLines(type, CrashKitLab.isEnabled())
        return CrashLogcat.capture(runtime.dumpDir, crashId, lines)
    }

    private fun onMainThread(): Boolean {
        return try {
            Looper.getMainLooper().thread === Thread.currentThread()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 三阶段投递。**三段全成功才删 pending 记录**；只要有一段失败、或者 reporter 压根
     * 没回调，记录就留在盘上，下次启动由 [resendPending] 重投。
     *
     * `1.4.1` 之前 [PendingStore] 只有写没有删也没有重投，`pending/` 是个只写不读的坟场
     * （设备实测积了 11 条），成功的删不掉、失败的也补不回来。
     */
    private fun emitAll(record: CrashRecord, unblock: Boolean) {
        // 把生效的 reporter 打出来。宿主排查「没收到通知」时，第一件要确认的就是这里
        // 是不是 NoOpCrashReporter —— 那说明 setTelemetrySink / setReporter 没装上或装晚了。
        KitLog.i(
            TAG,
            "report ${record.crashId} type=${record.type}" +
                " reporter=${runtime.getReporter().javaClass.simpleName} resend=${!unblock}",
        )
        val remaining = AtomicInteger(3)
        val allOk = AtomicBoolean(true)
        val onStage = { success: Boolean ->
            if (!success) {
                allOk.set(false)
            }
            if (remaining.decrementAndGet() == 0 && allOk.get()) {
                PendingStore.remove(runtime.dumpDir, record.crashId)
            }
            if (unblock) {
                blocker.unblock()
            }
        }
        emit(record, ReportStage.META, onStage)
        emit(record, ReportStage.DUMP, onStage)
        emit(record, ReportStage.LOGS, onStage)
    }

    private fun emit(record: CrashRecord, stage: ReportStage, onStage: (Boolean) -> Unit) {
        val reporter: CrashReporter = runtime.getReporter()
        try {
            reporter.report(record, stage) { success ->
                KitLog.i(TAG, "${record.crashId} $stage success=$success")
                onStage(success)
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "reporter $stage", t)
            onStage(false)
        }
    }

    /**
     * 把上次进程没投递成功的记录重投一遍。由 `CrashKit.init` 在宿主装好真实 reporter
     * 之后在后台线程触发。
     *
     * 只走 reporter / telemetrySink：宿主 [CrashCallback] 三钩子的语义是「此刻正在崩溃」，
     * 冷启动阶段重放会干扰启动流程。也不碰 [blocker]——那是崩溃现场用来给上报争取时间的，
     * 重投没有现场要保。
     */
    fun resendPending() {
        if (!runtime.gate.isEnabled()) {
            return
        }
        if (!resendOnce.compareAndSet(false, true)) {
            return
        }
        val records = PendingStore.loadAll(runtime.dumpDir)
        if (records.isEmpty()) {
            return
        }
        KitLog.i(TAG, "resend pending count=${records.size}")
        for (r in records) {
            emitAll(r, unblock = false)
        }
    }

    private fun renameDump(dumpPath: String?, newName: String): File {
        if (dumpPath == null) {
            return File(runtime.dumpDir, newName)
        }
        val src = File(dumpPath)
        val dest = File(runtime.dumpDir, newName)
        if (src.exists() && src.absolutePath != dest.absolutePath) {
            if (!src.renameTo(dest)) {
                return src
            }
        }
        return if (dest.exists()) dest else src
    }

    companion object {
        private const val TAG = "CrashPipeline"

        private fun addIfExists(list: MutableList<File>, file: File?) {
            if (file != null && file.exists() && !list.contains(file)) {
                list.add(file)
            }
        }

        private fun unique(logs: List<File>, dumps: List<File>): List<File> {
            val out = ArrayList<File>()
            for (f in logs) {
                if (!dumps.contains(f) && !out.contains(f)) {
                    out.add(f)
                }
            }
            return out
        }

        private fun path(file: File?): String = file?.absolutePath ?: ""
    }
}
