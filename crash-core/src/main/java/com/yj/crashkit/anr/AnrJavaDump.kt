package com.yj.crashkit.anr

import android.os.Looper
import android.os.Process
import com.yj.crashkit.util.KitLog
import java.io.File

/**
 * ANR 当场抓栈。不依赖主线程采样是否已 start。
 */
internal object AnrJavaDump {
    private const val TAG = "AnrJavaDump"
    private const val MAX_OTHER_THREADS = 24
    private const val MAX_OTHER_FRAMES = 8

    fun capture(): String {
        val sb = StringBuilder(4096)
        try {
            val main = Looper.getMainLooper().thread
            sb.append("----- main \"").append(main.name)
                .append("\" state=").append(main.state).append('\n')
            appendFrames(sb, main.stackTrace, Int.MAX_VALUE)
            val all = Thread.getAllStackTraces()
            var n = 0
            for ((thread, stack) in all) {
                if (thread === main) {
                    continue
                }
                if (n++ >= MAX_OTHER_THREADS) {
                    sb.append("\n----- ... ").append(all.size - 1 - MAX_OTHER_THREADS)
                        .append(" more threads -----\n")
                    break
                }
                sb.append("\n----- \"").append(thread.name).append("\" state=")
                    .append(thread.state).append('\n')
                appendFrames(sb, stack, MAX_OTHER_FRAMES)
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "capture", t)
        }
        return sb.toString()
    }

    fun requestTraces(dumpDir: File, timeoutMs: Long = 800L): File? {
        val f = File(dumpDir, "traces.txt")
        if (f.exists() && f.length() > 64L) {
            return f
        }
        try {
            Process.sendSignal(Process.myPid(), Process.SIGNAL_QUIT)
        } catch (t: Throwable) {
            KitLog.e(TAG, "sendSignal SIGQUIT", t)
            return if (f.exists() && f.length() > 0L) f else null
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (f.exists() && f.length() > 64L) {
                return f
            }
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                break
            }
        }
        return if (f.exists() && f.length() > 0L) f else null
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
