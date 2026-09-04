package com.yj.crashkit.anr

import android.os.Looper
import com.yj.crashkit.util.KitLog
import java.util.concurrent.atomic.AtomicReference

/**
 * ANR 当场抓主线程 Java 栈。不依赖采样器，也不在检测线程上阻塞等待 SIGQUIT。
 */
internal object AnrJavaDump {
    private const val TAG = "AnrJavaDump"
    private const val DUMP_TIMEOUT_MS = 200L

    fun capture(): String {
        val main = try {
            Looper.getMainLooper().thread
        } catch (t: Throwable) {
            KitLog.e(TAG, "main looper", t)
            return ""
        }
        val sb = StringBuilder(1024)
        sb.append("----- main \"").append(main.name)
            .append("\" state=").append(main.state).append('\n')
        sb.append(dumpStackTimed(main, DUMP_TIMEOUT_MS))
        return sb.toString()
    }

    private fun dumpStackTimed(thread: Thread, timeoutMs: Long): String {
        val result = AtomicReference<String?>(null)
        val worker = Thread({
            try {
                val frames = StringBuilder()
                appendFrames(frames, thread.stackTrace, Int.MAX_VALUE)
                result.set(frames.toString())
            } catch (t: Throwable) {
                KitLog.e(TAG, "stackTrace", t)
                result.set("  (dump failed)\n")
            }
        }, "CrashKit-AnrJavaDump")
        worker.isDaemon = true
        worker.start()
        try {
            worker.join(timeoutMs)
        } catch (_: InterruptedException) {
        }
        return result.get() ?: "  (stack dump timed out)\n"
    }

    private fun appendFrames(sb: StringBuilder, stack: Array<StackTraceElement>?, max: Int) {
        if (stack == null || stack.isEmpty()) {
            sb.append("  (no java stack)\n")
            return
        }
        val n = minOf(max, stack.size)
        for (i in 0 until n) {
            sb.append("  at ").append(stack[i].toString()).append('\n')
        }
    }
}
