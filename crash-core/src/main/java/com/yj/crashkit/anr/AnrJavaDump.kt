package com.yj.crashkit.anr

import android.os.Looper
import android.os.Process
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.resource.RecordInfo
import com.yj.crashkit.util.KitLog
import java.io.File

/**
 * ANR dump 线程上的现场：主线程 Java 栈（不在 signal handler 里抓）+ `/proc` kernel stack
 * + 全线程 Java 栈。ANR 的根因经常不在主线程（等锁、等 Binder、等 Future），只抓 main 不够定位。
 */
internal object AnrJavaDump {
    private const val TAG = "AnrJavaDump"
    private const val PROC_MAX = 4 * 1024
    private const val MAX_THREADS = 128
    private const val MAX_FRAMES = 24
    private const val TELEMETRY_THREADS = 8
    private const val TELEMETRY_FRAMES = 10

    /**
     * 抓主线程 Java 栈。跑在 dump 线程上，不在 signal handler 里。
     *
     * 别再加「跳过 Java 栈」的开关：`1.4.1` 之前两个调用点都传了 skip=true，结果落盘的
     * `main_stack.txt` 只有一行表头（设备实测 38 字节），ANR 现场等于没采到。
     * 主线程栈就是这条链路唯一有价值的产物。
     */
    fun capture(): String {
        val sb = StringBuilder(2048)
        val main = try {
            Looper.getMainLooper().thread
        } catch (t: Throwable) {
            KitLog.e(TAG, "main looper", t)
            return ""
        }
        sb.append("----- main \"").append(main.name)
            .append("\" state=").append(main.state).append('\n')
        appendJavaStack(sb, main)
        val kernel = dumpKernelStack()
        if (kernel.isNotEmpty()) {
            sb.append("----- proc -----\n").append(kernel)
        }
        return sb.toString()
    }

    /**
     * 主线程正在执行业务栈，而不是停在 `Looper` 空转。
     *
     * 空闲主线程的 Java 状态经常是 `WAITING`（卡在 `nativePollOnce`），不能拿
     * `Thread.state != RUNNABLE` 当 ANR。看第一帧：空闲是 `MessageQueue` / `Looper.loop`，
     * 真正卡住时第一帧是 `sleep` / binder / 业务方法。
     *
     * debug 页往往会在主线程卡住之前就先发 SIGQUIT，队头超期门槛（前台 2s）那时还没到；
     * 若不认这一条，确认会空转去等 `processesInErrorState`，用户关掉 ANR 弹窗后进程被
     * SIGKILL，现场还没落盘。
     */
    fun isExecutingWork(snapshot: String): Boolean {
        for (line in snapshot.lineSequence()) {
            val text = line.trim()
            if (!text.startsWith("at ")) {
                continue
            }
            if (text.contains("MessageQueue.nativePollOnce") ||
                text.contains("MessageQueue.next(") ||
                text.contains("Looper.loop(")
            ) {
                return false
            }
            return true
        }
        return false
    }

    /**
     * 全线程 Java 栈 + 计数头。跑在 dump 线程上，给 [threads.txt] 全文和埋点 `threads:` 压缩用。
     *
     * `Thread.getAllStackTraces()` 没有 monitor owner 行；能拿到 [Thread.State.BLOCKED]
     * 和双方的同步方法帧，对「主线程在等谁」已经够用。Android 上 ThreadMXBean 经常没有，
     * 有就附 lock/owner。
     */
    fun captureAllThreads(): String {
        val sb = StringBuilder(16 * 1024)
        val map = try {
            Thread.getAllStackTraces()
        } catch (t: Throwable) {
            KitLog.e(TAG, "allStackTraces", t)
            emptyMap()
        }
        val locks = mxLocks()
        var blocked = 0
        var waiting = 0
        var timed = 0
        var runnable = 0
        for ((t, _) in map) {
            when (t.state) {
                Thread.State.BLOCKED -> blocked++
                Thread.State.WAITING -> waiting++
                Thread.State.TIMED_WAITING -> timed++
                Thread.State.RUNNABLE -> runnable++
                else -> Unit
            }
        }
        val heap = Runtime.getRuntime()
        val used = heap.totalMemory() - heap.freeMemory()
        sb.append("java_threads=").append(map.size)
            .append(" blocked=").append(blocked)
            .append(" waiting=").append(waiting)
            .append(" timed_waiting=").append(timed)
            .append(" runnable=").append(runnable)
            .append(" fd=").append(RecordInfo.fdCount())
            .append(" task=").append(RecordInfo.taskCount())
            .append('\n')
        sb.append("heap_used=").append(used)
            .append(" heap_max=").append(heap.maxMemory())
            .append('\n')
        val load = readProcLine("/proc/loadavg")
        if (load.isNotEmpty()) {
            sb.append("loadavg=").append(load).append('\n')
        }
        sb.append("index:\n")
        val ranked = map.entries.sortedWith(
            compareBy<Map.Entry<Thread, Array<StackTraceElement>>> { interestRank(it.key, it.value) }
                .thenBy { it.key.name },
        )
        var dumped = 0
        for ((t, _) in ranked) {
            sb.append('"').append(t.name).append("\" state=").append(t.state)
            val extra = locks[t.name]
            if (!extra.isNullOrEmpty()) {
                sb.append(' ').append(extra)
            }
            sb.append('\n')
        }
        sb.append('\n')
        for ((t, stack) in ranked) {
            if (dumped >= MAX_THREADS) {
                sb.append("... truncated threads ").append(map.size - dumped).append('\n')
                break
            }
            dumped++
            sb.append("----- \"").append(t.name).append("\" tid=").append(t.id)
                .append(" state=").append(t.state)
            val extra = locks[t.name]
            if (!extra.isNullOrEmpty()) {
                sb.append(' ').append(extra)
            }
            sb.append('\n')
            appendFrames(sb, stack, MAX_FRAMES)
        }
        return sb.toString()
    }

    /**
     * 埋点里塞不下全线程。留下头（计数 + index）和「可能是根因」的线程栈：
     * BLOCKED、在等 Binder/Future、RUNNABLE 跑业务、以及非 park 空转的 WAITING。
     * 线程池 `LockSupport.park`、`nativePollOnce`、ART 守护线程丢掉。
     */
    fun compactInterestingThreads(raw: String, budget: Int): String {
        if (raw.isEmpty() || budget <= 0) {
            return ""
        }
        val lines = raw.split('\n')
        val header = StringBuilder()
        val blocks = ArrayList<String>()
        val current = StringBuilder()
        var inBlocks = false
        for (line in lines) {
            if (line.startsWith("----- \"")) {
                inBlocks = true
                if (current.isNotEmpty()) {
                    blocks.add(current.toString())
                    current.setLength(0)
                }
                current.append(line).append('\n')
                continue
            }
            if (!inBlocks) {
                header.append(line).append('\n')
                continue
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) {
            blocks.add(current.toString())
        }
        val out = StringBuilder(budget.coerceAtMost(raw.length + 16))
        val headerText = header.toString()
        if (headerText.isNotBlank()) {
            val clipped = if (headerText.length > budget / 3) {
                headerText.substring(0, budget / 3)
            } else {
                headerText
            }
            out.append(clipped.trimEnd()).append('\n')
        }
        var kept = 0
        for (block in blocks) {
            if (out.length >= budget) {
                break
            }
            if (!isInterestingBlock(block)) {
                continue
            }
            if (kept >= TELEMETRY_THREADS) {
                break
            }
            kept++
            appendClippedBlock(out, block, TELEMETRY_FRAMES, budget)
        }
        if (kept == 0) {
            for (block in blocks.take(3)) {
                if (out.length >= budget) {
                    break
                }
                appendClippedBlock(out, block, TELEMETRY_FRAMES, budget)
            }
        }
        val text = out.toString().trimEnd()
        return if (text.length <= budget) text else text.substring(0, budget)
    }

    internal fun isInterestingThread(name: String, state: String, frames: List<String>): Boolean {
        if (isDumpNoise(name)) {
            return false
        }
        if (state == "BLOCKED") {
            return true
        }
        val top = frames.take(6).joinToString("\n")
        if (top.contains("BinderProxy.transact") ||
            top.contains("transactNative") ||
            top.contains("CountDownLatch") ||
            top.contains("FutureTask") ||
            top.contains("CompletableFuture") ||
            top.contains("LinkedBlockingQueue") ||
            top.contains("SynchronousQueue")
        ) {
            return true
        }
        val first = frames.firstOrNull().orEmpty()
        if (isIdleParkFrame(first)) {
            return false
        }
        if (state == "RUNNABLE") {
            return frames.take(4).any { isAppFrame(it) }
        }
        return frames.isNotEmpty() && !isIdleParkFrame(first)
    }

    private fun isInterestingBlock(block: String): Boolean {
        val lines = block.split('\n')
        val head = lines.firstOrNull().orEmpty()
        val name = head.substringAfter('"').substringBefore('"')
        val state = head.substringAfter("state=", "").substringBefore(' ').trim()
        val frames = lines.map { it.trim() }.filter { it.startsWith("at ") }
        return isInterestingThread(name, state, frames)
    }

    private fun interestRank(thread: Thread, stack: Array<StackTraceElement>): Int {
        val frames = stack.map { "at $it" }
        return if (isInterestingThread(thread.name, thread.state.name, frames)) 0 else 1
    }

    private fun isDumpNoise(name: String): Boolean {
        return name.startsWith("CrashKit") ||
            name == "Signal Catcher" ||
            name == "JDWP" ||
            name == "Profile Saver" ||
            name.startsWith("HeapTaskDaemon") ||
            name == "ReferenceQueueDaemon" ||
            name == "FinalizerDaemon" ||
            name == "FinalizerWatchdogDaemon"
    }

    private fun isIdleParkFrame(frame: String): Boolean {
        return frame.contains("LockSupport.park") ||
            frame.contains("Unsafe.park") ||
            frame.contains("MessageQueue.nativePollOnce") ||
            frame.contains("MessageQueue.next(") ||
            frame.contains("nativePollOnce") ||
            frame.contains("epollWait") ||
            frame.contains("ReferenceQueue.remove")
    }

    private fun isAppFrame(frame: String): Boolean {
        val pkg = CrashKitRuntime.get()?.packageName.orEmpty()
        if (pkg.isNotEmpty() && frame.contains(pkg)) {
            return true
        }
        return !frame.contains("at java.") &&
            !frame.contains("at javax.") &&
            !frame.contains("at android.") &&
            !frame.contains("at androidx.") &&
            !frame.contains("at dalvik.") &&
            !frame.contains("at libcore.") &&
            !frame.contains("at sun.") &&
            !frame.contains("at kotlin.") &&
            !frame.contains("at kotlinx.coroutines.scheduling") &&
            !frame.contains("at com.android.")
    }

    private fun appendClippedBlock(out: StringBuilder, block: String, maxFrames: Int, budget: Int) {
        var frames = 0
        for (line in block.split('\n')) {
            if (out.length >= budget) {
                return
            }
            val trimmed = line.trimEnd()
            if (trimmed.trim().startsWith("at ")) {
                if (frames >= maxFrames) {
                    continue
                }
                frames++
            }
            out.append(trimmed).append('\n')
        }
    }

    private fun appendFrames(sb: StringBuilder, stack: Array<StackTraceElement>, max: Int) {
        if (stack.isEmpty()) {
            sb.append("  (no java stack)\n")
            return
        }
        val n = stack.size.coerceAtMost(max)
        for (i in 0 until n) {
            sb.append("  at ").append(stack[i].toString()).append('\n')
        }
        if (stack.size > n) {
            sb.append("  ... ").append(stack.size - n).append(" more\n")
        }
    }

    private fun mxLocks(): Map<String, String> {
        return try {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val bean = factory.getMethod("getThreadMXBean").invoke(null) ?: return emptyMap()
            val dump = bean.javaClass.methods.firstOrNull {
                it.name == "dumpAllThreads" && it.parameterTypes.size == 2
            } ?: return emptyMap()
            val infos = dump.invoke(bean, java.lang.Boolean.TRUE, java.lang.Boolean.TRUE) as? Array<*>
                ?: return emptyMap()
            val out = HashMap<String, String>()
            for (info in infos) {
                if (info == null) {
                    continue
                }
                val c = info.javaClass
                val name = c.getMethod("getThreadName").invoke(info) as? String ?: continue
                val lockName = c.getMethod("getLockName").invoke(info) as? String
                val owner = c.getMethod("getLockOwnerName").invoke(info) as? String
                if (lockName.isNullOrEmpty() && owner.isNullOrEmpty()) {
                    continue
                }
                out[name] = "lock=${lockName ?: "-"} owner=${owner ?: "-"}"
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private fun readProcLine(path: String): String {
        return try {
            File(path).readText().trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun appendJavaStack(sb: StringBuilder, main: Thread) {
        try {
            val stack = main.stackTrace
            if (stack.isNullOrEmpty()) {
                sb.append("  (no java stack)\n")
                return
            }
            for (el in stack) {
                sb.append("  at ").append(el.toString()).append('\n')
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "stackTrace", t)
            sb.append("  (stack dump failed)\n")
        }
    }

    private fun dumpKernelStack(): String {
        val pid = Process.myPid()
        val f = File("/proc/self/task/$pid/stack")
        if (!f.exists()) {
            return ""
        }
        return try {
            val text = f.readText()
            if (text.length <= PROC_MAX) text else text.substring(0, PROC_MAX)
        } catch (_: Throwable) {
            ""
        }
    }
}
