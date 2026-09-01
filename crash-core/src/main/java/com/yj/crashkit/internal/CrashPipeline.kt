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

/**
 * 崩溃主路径：pre → 落盘 → crashCallback → 三阶段 reporter → after → Blocker。
 */
class CrashPipeline(private val runtime: CrashKitRuntime) {
    private val blocker = CrashBlocker()
    private val once = AtomicBoolean(false)

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
        val dump = DumpWriter.writeStack(runtime.dumpDir, crashId, throwable)
        val type = if (throwable is OutOfMemoryError) {
            CrashType.JAVA_OOM
        } else {
            CrashType.JAVA_CRASH
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

    fun handleAnr(shortMsg: String?, mainStack: File?, errorLog: File?, traces: File?, syslog: File?) {
        if (!runtime.gate.isEnabled()) {
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
    ) {
        val dumpPath = path(dump)
        val symbolPath = path(symbol)
        val logPath = path(syslog)
        val cb: CrashCallback? = runtime.getCrashCallback()
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
        for (extra in RecordInfo.dumpForCrash(runtime.dumpDir, type)) {
            addIfExists(logs, extra)
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
        emit(record, ReportStage.META)
        emit(record, ReportStage.DUMP)
        emit(record, ReportStage.LOGS)

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

    private fun emit(record: CrashRecord, stage: ReportStage) {
        val reporter: CrashReporter = runtime.getReporter()
        try {
            reporter.report(record, stage) { success ->
                KitLog.i(TAG, "${record.crashId} $stage success=$success")
                blocker.unblock()
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "reporter $stage", t)
            blocker.unblock()
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
