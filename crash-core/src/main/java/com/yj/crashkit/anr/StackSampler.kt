package com.yj.crashkit.anr

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Locale

/** 环形主线程栈样本。默认 200 条，可 [setMaxEntryCount] 收到 50。size 为 O(1)。 */
internal class StackSampler(thread: Thread, sampleIntervalMs: Long) : AbstractSampler(sampleIntervalMs) {
    private val lock = Any()
    private val queue = ArrayDeque<StackNode>(DEFAULT_MAX)
    private val target = thread
    @Volatile private var maxEntryCount = DEFAULT_MAX
    @Volatile private var processName = ""

    fun setProcessName(name: String?) {
        processName = name ?: ""
    }

    fun setMaxEntryCount(max: Int) {
        if (max > 0) {
            maxEntryCount = max
            synchronized(lock) {
                while (queue.size > maxEntryCount) {
                    queue.removeFirst()
                }
            }
        }
    }

    fun getThreadStackEntries(startMs: Long, endMs: Long): ArrayList<String> {
        val snapshot: List<StackNode>
        synchronized(lock) {
            snapshot = ArrayList(queue)
        }
        val result = ArrayList<String>()
        val pid = android.os.Process.myPid()
        for (node in snapshot) {
            if (node.time !in (startMs + 1)..<endMs || node.stack == null) {
                continue
            }
            val sb = StringBuilder()
            sb.append("----- pid ").append(pid).append(' ')
                .append(TIME_FORMATTER.format(node.time)).append('\n')
            sb.append("Cmd line: ").append(processName).append('\n')
            sb.append(" tid=1 \n sysTid=").append(pid).append(" \n")
            for (el in node.stack!!) {
                sb.append("at ").append(el.toString()).append('\n')
            }
            sb.append("----- end ").append(pid).append('\n')
            result.add(sb.toString())
        }
        return result
    }

    override fun doSample() {
        try {
            val node = StackNode(
                time = System.currentTimeMillis(),
                stack = target.stackTrace,
            )
            synchronized(lock) {
                while (queue.size >= maxEntryCount) {
                    queue.removeFirst()
                }
                queue.addLast(node)
            }
        } catch (_: Throwable) {
        }
    }

    class StackNode(
        var time: Long = 0,
        var stack: Array<StackTraceElement>? = null,
    )

    companion object {
        val TIME_FORMATTER: SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
        private const val DEFAULT_MAX = 200
    }
}
