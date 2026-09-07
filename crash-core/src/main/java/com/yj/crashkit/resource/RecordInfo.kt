package com.yj.crashkit.resource

import android.os.Debug
import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.CrashType
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.DumpWriter
import com.yj.crashkit.internal.OomLite
import com.yj.crashkit.nativecrash.NativeCrashBridge
import com.yj.crashkit.util.KitLog
import java.io.BufferedReader
import java.io.File
import java.io.FileReader

/**
 * 崩溃时 dump FD / 内存 / 线程 / maps（读 `/proc`，无第三方 hook）。
 * 线上 OOM 只写计数；全线程栈 / getPss / 逐个 canonicalPath 仅 Lab。
 */
object RecordInfo {
    @JvmStatic
    fun dumpFdInfo() {
        val rt = CrashKitRuntime.get() ?: return
        DumpWriter.writeText(rt.dumpDir, "fd_info.txt", fdSnapshot())
    }

    @JvmStatic
    fun dumpMemInfo() {
        val rt = CrashKitRuntime.get() ?: return
        DumpWriter.writeText(rt.dumpDir, "mem_info.txt", memSnapshot())
    }

    @JvmStatic
    fun dumpThreadInfo() {
        val rt = CrashKitRuntime.get() ?: return
        DumpWriter.writeText(rt.dumpDir, "thread_info.txt", threadSnapshot())
    }

    @JvmStatic
    fun dumpMapInfo() {
        val rt = CrashKitRuntime.get() ?: return
        DumpWriter.writeText(rt.dumpDir, "maps.txt", readProc("maps", 80))
    }

    @JvmStatic
    fun dumpNativeThreadStack(): String {
        return NativeCrashBridge.dumpNativeThreadStack() + "\n" + threadSnapshot()
    }

    @JvmStatic
    fun dumpNativeMemory() {
        dumpMemInfo()
    }

    @JvmStatic
    fun dumpFd() {
        dumpFdInfo()
    }

    @JvmStatic
    fun javaStack(allThreads: Boolean) {
        val rt = CrashKitRuntime.get() ?: return
        DumpWriter.writeText(
            rt.dumpDir,
            "java_stack.txt",
            if (allThreads) threadSnapshot() else stackOf(Thread.currentThread()),
        )
    }

    @JvmStatic
    fun dumpEnabled(dumpDir: File, oom: Boolean): List<File> {
        val type = if (oom) CrashType.JAVA_OOM else CrashType.JAVA_CRASH
        return dumpForCrash(dumpDir, type)
    }

    @JvmStatic
    fun dumpForCrash(dumpDir: File, type: CrashType): List<File> {
        val files = ArrayList<File>()
        if (type == CrashType.JAVA_OOM) {
            // 走预分配缓冲，OOM 现场不再申请内存
            files.add(OomLite.writeCounters(dumpDir))
            return files
        }
        val rt = CrashKitRuntime.get()
        val fd = rt != null && rt.isFdMonitor()
        val mem = rt != null && rt.isMemMonitor()
        val thread = rt != null && rt.isThreadMonitor()
        if (!fd && !mem && !thread) {
            return files
        }
        val heavy = CrashKitOnlinePolicy.heavyProcDump(CrashKitLab.isEnabled())
        if (fd) {
            files.add(DumpWriter.writeText(dumpDir, "fd_info.txt", fdSnapshot(heavy)))
        }
        if (mem) {
            files.add(DumpWriter.writeText(dumpDir, "mem_info.txt", memSnapshot(heavy)))
        }
        if (thread) {
            files.add(DumpWriter.writeText(dumpDir, "thread_info.txt", threadSnapshot(heavy)))
        }
        if (heavy) {
            files.add(DumpWriter.writeText(dumpDir, "maps.txt", readProc("maps", 80)))
        }
        return files
    }

    @JvmStatic
    @JvmOverloads
    fun fdSnapshot(heavy: Boolean = CrashKitLab.isEnabled()): String {
        val hooked = NativeCrashBridge.dumpHookedFd()
        val sb = StringBuilder()
        sb.append("fd_count=").append(countFd()).append('\n')
        if (heavy) {
            sb.append(readDirLinks("/proc/self/fd", 128, resolveCanonical = true))
        } else {
            sb.append(readDirLinks("/proc/self/fd", 64, resolveCanonical = false))
        }
        if (hooked.isNotEmpty()) {
            sb.append("\nhooked_fd:\n").append(hooked)
        }
        return sb.toString()
    }

    @JvmStatic
    @JvmOverloads
    fun memSnapshot(heavy: Boolean = CrashKitLab.isEnabled()): String {
        val sb = StringBuilder()
        appendHeap(sb)
        if (heavy) {
            sb.append("pss=").append(Debug.getPss()).append('\n')
            sb.append("\n/proc/self/status:\n").append(readProc("status", 80))
            sb.append("\n/proc/self/statm: ").append(readProc("statm", 5))
            sb.append("\n/proc/meminfo:\n").append(readAbsolute("/proc/meminfo", 80))
        } else {
            sb.append("\n/proc/self/status:\n").append(readProc("status", 20))
        }
        val hooked = NativeCrashBridge.dumpHookedMem()
        if (hooked.isNotEmpty()) {
            sb.append("\nhooked_mem:\n").append(hooked)
        }
        return sb.toString()
    }

    @JvmStatic
    @JvmOverloads
    fun threadSnapshot(heavy: Boolean = CrashKitLab.isEnabled()): String {
        return if (heavy) {
            fullThreadSnapshot()
        } else {
            liteThreadSnapshot()
        }
    }

    internal fun liteOomSnapshot(): String = OomLite.countersText()

    private fun appendHeap(sb: StringBuilder) {
        val rt = Runtime.getRuntime()
        val used = rt.totalMemory() - rt.freeMemory()
        sb.append("java_used=").append(used).append('\n')
        sb.append("java_total=").append(rt.totalMemory()).append('\n')
        sb.append("java_max=").append(rt.maxMemory()).append('\n')
        sb.append("java_free=").append(rt.freeMemory()).append('\n')
        sb.append("native_heap=").append(Debug.getNativeHeapAllocatedSize()).append('\n')
        sb.append("native_heap_size=").append(Debug.getNativeHeapSize()).append('\n')
        sb.append("native_heap_free=").append(Debug.getNativeHeapFreeSize()).append('\n')
    }

    private fun fullThreadSnapshot(): String {
        val sb = StringBuilder()
        val map = Thread.getAllStackTraces()
        sb.append("java_threads=").append(map.size).append('\n')
        for ((t, stack) in map) {
            sb.append("\n\"").append(t.name).append("\" tid=")
                .append(t.id).append(" state=").append(t.state).append('\n')
            for (el in stack) {
                sb.append("  at ").append(el.toString()).append('\n')
            }
        }
        sb.append("\n/proc/self/task:\n").append(listTask())
        return sb.toString()
    }

    private fun liteThreadSnapshot(): String {
        val sb = StringBuilder()
        val estimate = Thread.activeCount()
        val threads = arrayOfNulls<Thread>(estimate + 8)
        val n = Thread.enumerate(threads)
        sb.append("java_threads=").append(n).append('\n')
        for (i in 0 until n) {
            val t = threads[i] ?: continue
            sb.append('"').append(t.name).append("\" tid=")
                .append(t.id).append(" state=").append(t.state).append('\n')
        }
        sb.append("task_count=").append(taskCount()).append('\n')
        return sb.toString()
    }

    private fun stackOf(thread: Thread): String {
        val sb = StringBuilder()
        sb.append(thread.name).append('\n')
        for (el in thread.stackTrace) {
            sb.append("  at ").append(el.toString()).append('\n')
        }
        return sb.toString()
    }

    internal fun fdCount(): Int = countFd()

    internal fun taskCount(): Int {
        val names = File("/proc/self/task").list()
        return names?.size ?: -1
    }

    private fun countFd(): Int {
        val names = File("/proc/self/fd").list()
        return names?.size ?: -1
    }

    private fun listTask(): String {
        val tasks = File("/proc/self/task").listFiles() ?: return ""
        val sb = StringBuilder()
        var n = 0
        for (t in tasks) {
            if (n++ > 256) {
                break
            }
            sb.append(t.name).append(' ')
                .append(readAbsolute(File(t, "comm").path, 1).trim())
                .append('\n')
        }
        return sb.toString()
    }

    private fun readDirLinks(dir: String, max: Int, resolveCanonical: Boolean): String {
        val root = File(dir)
        val names = root.list() ?: return ""
        val sb = StringBuilder()
        var n = 0
        for (name in names) {
            if (n++ >= max) {
                sb.append("... truncated\n")
                break
            }
            if (!resolveCanonical) {
                sb.append(name).append('\n')
                continue
            }
            try {
                val f = File(root, name)
                sb.append(name).append(" -> ").append(f.canonicalPath).append('\n')
            } catch (_: Throwable) {
                sb.append(name).append('\n')
            }
        }
        return sb.toString()
    }

    private fun readProc(name: String, maxLines: Int): String {
        return readAbsolute("/proc/self/$name", maxLines)
    }

    internal fun readAbsolute(path: String, maxLines: Int): String {
        val sb = StringBuilder()
        try {
            BufferedReader(FileReader(path), 1024).use { reader ->
                var n = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (n++ >= maxLines) {
                        break
                    }
                    sb.append(line).append('\n')
                }
            }
        } catch (t: Throwable) {
            KitLog.e("RecordInfo", "read $path", t)
        }
        return sb.toString()
    }
}
