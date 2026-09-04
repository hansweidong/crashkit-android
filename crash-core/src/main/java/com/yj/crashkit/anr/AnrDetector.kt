package com.yj.crashkit.anr

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.text.TextUtils
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.internal.DumpWriter
import com.yj.crashkit.nativecrash.NativeCrashBridge
import com.yj.crashkit.util.KitLog
import java.io.File
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 1s poll [ActivityManager.getProcessesInErrorState] + SIGQUIT traces。
 * 不使用 FileObserver(/data/anr)。
 */
class AnrDetector(
    private val context: Context,
    private val pipeline: CrashPipeline,
) : NativeCrashBridge.AnrTraceReceiver {
    private val reported = AtomicBoolean(false)
    private var timer: Timer? = null
    @Volatile private var listener: AnrListener? = pendingListener

    init {
        NativeCrashBridge.setAnrTraceReceiver(this)
        val rt = CrashKitRuntime.get()
        if (rt != null) {
            MainThreadSampler.get().setProcessName(rt.packageName)
        }
    }

    fun setListener(listener: AnrListener?) {
        this.listener = listener
    }

    fun start() {
        timer?.cancel()
        val t = Timer("CrashKit-ANR", true)
        timer = t
        t.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                poll()
            }
        }, 0, 1000)
    }

    fun stop() {
        timer?.cancel()
        timer = null
    }

    override fun onTraces(path: String?) {
        if (!reported.compareAndSet(false, true)) {
            return
        }
        stop()
        val info = synthetic("sigquit traces")
        dispatch(info, tracesHint = if (path == null) null else File(path))
    }

    private fun poll() {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            val states = am.processesInErrorState ?: return
            val pid = Process.myPid()
            for (state in states) {
                if (state.pid == pid &&
                    state.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
                ) {
                    if (!reported.compareAndSet(false, true)) {
                        return
                    }
                    stop()
                    dispatch(state, tracesHint = tracesFile())
                    return
                }
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "poll", t)
        }
    }

    private fun dispatch(state: ActivityManager.ProcessErrorStateInfo, tracesHint: File?) {
        val l = listener
        if (l != null) {
            try {
                l.onANRDetected(state)
            } catch (t: Throwable) {
                KitLog.e(TAG, "listener", t)
            }
        }
        val shortMsg = if (TextUtils.isEmpty(state.shortMsg)) "ANR" else state.shortMsg
        val dumpDir = CrashKitRuntime.get()?.dumpDir ?: context.cacheDir
        var mainStack: File? = null
        var errorLog: File? = null
        val traces = tracesHint?.takeIf { it.exists() && it.length() > 64L }
        try {
            errorLog = DumpWriter.writeText(dumpDir, "anr_error.log", state.longMsg ?: "")
            val javaStacks = AnrJavaDump.capture()
            val sampled = MainThreadSampler.get()
                .getThreadStackEntries(System.currentTimeMillis() - 10_000L, System.currentTimeMillis())
            val sb = StringBuilder(javaStacks.length + 256)
            sb.append(javaStacks)
            if (sampled.isNotEmpty()) {
                sb.append("\n----- sampled -----\n")
                for (block in sampled) {
                    sb.append(block).append('\n')
                }
            }
            mainStack = DumpWriter.writeText(dumpDir, "main_stack.txt", sb.toString())
        } catch (t: Throwable) {
            KitLog.e(TAG, "dump", t)
        }
        try {
            pipeline.handleAnr(shortMsg, mainStack, errorLog, traces, null)
        } catch (t: Throwable) {
            KitLog.e(TAG, "handleAnr", t)
        }
    }

    private fun tracesFile(): File? {
        val rt = CrashKitRuntime.get() ?: return null
        val f = File(rt.dumpDir, "traces.txt")
        return if (f.exists()) f else null
    }

    companion object {
        private const val TAG = "AnrDetector"

        @Volatile
        private var pendingListener: AnrListener? = null

        @JvmStatic
        fun setPendingListener(listener: AnrListener?) {
            pendingListener = listener
        }

        private fun synthetic(msg: String): ActivityManager.ProcessErrorStateInfo {
            val info = ActivityManager.ProcessErrorStateInfo()
            info.pid = Process.myPid()
            info.condition = ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
            info.shortMsg = msg
            info.longMsg = msg
            return info
        }
    }
}
