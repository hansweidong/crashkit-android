package com.yj.crashkit.anr

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.text.TextUtils
import com.yj.crashkit.history.ActivityTracker
import com.yj.crashkit.internal.CrashFiles
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.internal.DumpWriter
import com.yj.crashkit.nativecrash.NativeCrashBridge
import com.yj.crashkit.util.KitLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对齐 Matrix `SignalAnrTracer`：**只采不杀**。
 *
 * 1. SIGQUIT 旁路：在**主线程**上解除 SIGQUIT 屏蔽（内核派发进程定向信号时主线程优先，
 *    不这么做信号会被 ART 的 Signal Catcher 抢走），handler 里**先** tgkill 交回
 *    Signal Catcher，再 sem_post 唤醒工作线程，所以系统 traces / 弹窗链路完全不等我们
 * 2. [onAnrSignal] 在监听线程上被调用，起 dump 线程抓主线程栈后 [confirmRealAnr]：
 *    - MessageQueue 队头超期 / 主线程第一帧不是 Looper 空转 / AM NOT_RESPONDING → 上报
 *    - 现场抓**全线程** Java 栈：根因经常是后台线程持锁、Binder、Future，不只是 main
 * 3. 同一进程生命周期内只上报一次；不 kill、不拆任务、不管关闭后黑屏
 *
 * 进程被系统直接杀掉、走不到这条路径的 fatal ANR 由 [ExitInfoAnrCollector] 在下次启动补报。
 */
class AnrDetector(
    private val context: Context,
    private val pipeline: CrashPipeline,
) {
    private val reported = AtomicBoolean(false)
    private val dumping = AtomicBoolean(false)
    @Volatile private var listener: AnrListener? = pendingListener
    private val myUid = Process.myUid()

    init {
        val rt = CrashKitRuntime.get()
        if (rt != null) {
            MainThreadSampler.get().setProcessName(rt.packageName)
        }
    }

    fun setListener(listener: AnrListener?) {
        this.listener = listener
    }

    /**
     * 装旁路。**必须落在主线程上执行**：native 那边要在主线程解除 SIGQUIT 屏蔽，
     * 否则信号会被 ART 的 Signal Catcher 抢走（细节见 `install_anr_signal` 的注释）。
     * 宿主若不在主线程 init，这里帮它 post 回去。
     */
    fun start() {
        NativeCrashBridge.setAnrSignalSink { onAnrSignal() }
        val main = try {
            Looper.getMainLooper()
        } catch (_: Throwable) {
            null
        }
        if (main != null && main.thread !== Thread.currentThread()) {
            Handler(main).post { installSignal() }
        } else {
            installSignal()
        }
    }

    private fun installSignal() {
        val ok = NativeCrashBridge.installAnrSignal()
        KitLog.i(TAG, "signal ANR install ok=$ok tid=${Process.myTid()} pid=${Process.myPid()}")
    }

    fun stop() {
        NativeCrashBridge.setAnrSignalSink(null)
    }

    /**
     * Native 监听线程回调。SIGQUIT 已经在 signal handler 里交回 Signal Catcher，
     * 这里不需要再让 native 等我们，直接把快照与确认丢给 dump 线程后立刻返回
     * （对齐 Matrix onANRDumped 的独立 ANR-Dump 线程）。
     */
    internal fun onAnrSignal() {
        KitLog.i(TAG, "SIGQUIT received pid=${Process.myPid()} reported=${reported.get()}")
        if (reported.get()) {
            return
        }
        if (!dumping.compareAndSet(false, true)) {
            KitLog.i(TAG, "dump thread busy, skip this SIGQUIT")
            return
        }
        val worker = Thread({
            try {
                val head = AnrMainQueue.peekHead()
                val snapshot = AnrJavaDump.capture()
                confirmRealAnr(head, snapshot)
            } catch (t: Throwable) {
                KitLog.e(TAG, "snapshot", t)
            } finally {
                dumping.set(false)
            }
        }, DUMP_THREAD)
        worker.isDaemon = true
        worker.start()
    }

    /**
     * 对齐 Matrix confirmRealAnr，但多认一帧主线程业务栈。
     *
     * debug 页常在 AMS 5s 超时之前就自己 `kill -3`。那时队头还没超期、华为上
     * `processesInErrorState` 也经常是空的，旧逻辑会空转最多 20s。用户关掉 ANR
     * 弹窗后进程被 SIGKILL，确认还没结束，pending 也还没写，下次启动就什么都没有。
     */
    private fun confirmRealAnr(head: AnrMainQueue.Head?, snapshot: String) {
        var currentHead = head
        var currentSnapshot = snapshot
        val foreground = ActivityTracker.get().isForeground

        fun queueOverdue(): Boolean {
            val h = currentHead
            return h != null && AnrMainQueue.isOverdue(h.delayMs, foreground)
        }

        fun mainBusy(): Boolean = AnrJavaDump.isExecutingWork(currentSnapshot)

        KitLog.i(
            TAG,
            "snapshot delayMs=${currentHead?.delayMs} fg=$foreground" +
                " overdue=${queueOverdue()} busy=${mainBusy()}" +
                " ${currentSnapshot.lineSequence().firstOrNull()}",
        )
        if (queueOverdue()) {
            fire(currentErrorState(), currentHead, currentSnapshot, "queue")
            return
        }
        if (mainBusy()) {
            fire(currentErrorState(), currentHead, currentSnapshot, "main")
            return
        }
        val counts = CrashKitOnlinePolicy.ANR_CHECK_ERROR_COUNT
        val interval = CrashKitOnlinePolicy.ANR_CHECK_ERROR_INTERVAL_MS
        for (i in 0 until counts) {
            if (reported.get()) {
                return
            }
            val state = currentErrorState()
            if (state != null) {
                KitLog.i(TAG, "AM NOT_RESPONDING at round=$i")
                fire(state, currentHead, currentSnapshot, "am")
                return
            }
            try {
                Thread.sleep(interval)
            } catch (_: InterruptedException) {
                return
            }
            currentHead = AnrMainQueue.peekHead()
            currentSnapshot = AnrJavaDump.capture()
            if (queueOverdue()) {
                KitLog.i(TAG, "main queue overdue at round=$i delay=${currentHead?.delayMs}")
                fire(currentErrorState(), currentHead, currentSnapshot, "queue")
                return
            }
            if (mainBusy()) {
                KitLog.i(TAG, "main executing at round=$i")
                fire(currentErrorState(), currentHead, currentSnapshot, "main")
                return
            }
        }
        KitLog.i(TAG, "SIGQUIT without blocked queue, executing main, or AM state, skip")
    }

    private fun fire(
        state: ActivityManager.ProcessErrorStateInfo?,
        head: AnrMainQueue.Head?,
        snapshot: String,
        source: String,
    ) {
        if (!reported.compareAndSet(false, true)) {
            return
        }
        KitLog.i(TAG, "ANR source=$source shortMsg=${state?.shortMsg}")
        val shortMsg = if (state != null && !TextUtils.isEmpty(state.shortMsg)) {
            state.shortMsg
        } else {
            "ANR"
        }
        val dumpDir = CrashKitRuntime.get()?.dumpDir ?: context.cacheDir
        val longMsg = buildString {
            if (!state?.longMsg.isNullOrEmpty()) {
                append(state!!.longMsg)
            }
            if (head != null) {
                if (isNotEmpty()) {
                    append('\n')
                }
                append("queue delayMs=").append(head.delayMs).append('\n')
                append(head.text)
            }
        }
        var mainStack: File? = null
        var errorLog: File? = null
        var threadsDump: File? = null
        try {
            val threadsText = AnrJavaDump.captureAllThreads()
            errorLog = DumpWriter.writeText(
                dumpDir,
                CrashFiles.ANR_ERROR_LOG,
                buildString {
                    if (longMsg.isNotEmpty()) {
                        append(longMsg).append('\n')
                    }
                    val header = threadsText.lineSequence().take(4).joinToString("\n")
                    if (header.isNotEmpty()) {
                        append(header)
                    }
                },
            )
            val sb = StringBuilder(1024)
            // snapshot 是 SIGQUIT 刚落地时抓的，比此刻（已经走完 AM 确认轮询）更贴近现场
            sb.append(if (snapshot.isNotEmpty()) snapshot else AnrJavaDump.capture())
            val sampled = MainThreadSampler.get()
                .getThreadStackEntries(System.currentTimeMillis() - 10_000L, System.currentTimeMillis())
            if (sampled.isNotEmpty()) {
                sb.append("\n----- sampled -----\n")
                for (block in sampled) {
                    sb.append(block).append('\n')
                }
            }
            mainStack = DumpWriter.writeText(dumpDir, CrashFiles.ANR_MAIN_STACK, sb.toString())
            threadsDump = DumpWriter.writeText(dumpDir, CrashFiles.ANR_THREADS, threadsText)
        } catch (t: Throwable) {
            KitLog.e(TAG, "dump", t)
        }
        // 记一笔时间戳，下次启动读 ApplicationExitInfo 时用它把同一次 ANR 去重
        AnrReportMark.mark(dumpDir, System.currentTimeMillis())
        try {
            pipeline.handleAnr(shortMsg, mainStack, errorLog, threadsDump, null)
        } catch (t: Throwable) {
            KitLog.e(TAG, "handleAnr", t)
        }
        val l = listener
        if (l == null) {
            // 宿主没调 setAnrListener。上报本身不受影响，但这条通知路径是空的
            KitLog.i(TAG, "no AnrListener registered")
            return
        }
        try {
            KitLog.i(TAG, "-> AnrListener ${l.javaClass.simpleName}")
            l.onANRDetected(state)
        } catch (t: Throwable) {
            KitLog.e(TAG, "listener", t)
        }
    }

    /**
     * 对齐 Matrix checkErrorState：
     * - 其他 uid 已是 NOT_RESPONDING → 视为别人的 ANR 信号，本进程不报
     * - 本 pid 且 NOT_RESPONDING → 确认为本进程 ANR
     */
    private fun currentErrorState(): ActivityManager.ProcessErrorStateInfo? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val states = try {
            am.processesInErrorState
        } catch (t: Throwable) {
            warnOnce("processesInErrorState", t)
            return null
        } ?: return null
        val pid = Process.myPid()
        for (state in states) {
            if (state.uid != myUid &&
                state.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
            ) {
                KitLog.i(TAG, "other app NOT_RESPONDING, ignore this SIGQUIT")
                return null
            }
        }
        for (state in states) {
            if (isAnr(state, pid)) {
                return state
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AnrDetector"
        private const val DUMP_THREAD = "CrashKit-ANR-Dump"

        @Volatile
        private var pendingListener: AnrListener? = null

        @JvmStatic
        fun setPendingListener(listener: AnrListener?) {
            pendingListener = listener
        }

        internal fun isAnr(
            state: ActivityManager.ProcessErrorStateInfo,
            pid: Int,
            @Suppress("UNUSED_PARAMETER") packageName: String = "",
        ): Boolean {
            if (state.pid != pid) {
                return false
            }
            return state.condition == ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
        }

        private val warned = ConcurrentHashMap.newKeySet<String>()

        private fun warnOnce(what: String, t: Throwable) {
            if (warned.add(what)) {
                KitLog.w(TAG, what, t)
            }
        }
    }
}
