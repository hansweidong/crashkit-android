package com.yj.crashkit.internal

import com.yj.crashkit.util.KitLog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * OOM 现场专用的落盘路径。所有缓冲在 [preallocate] 时一次吃下来。
 *
 * 堆已经耗尽时再走 [DumpWriter] + [com.yj.crashkit.util.StackTraceFormatter] 会继续申请
 * StringBuilder / String / ByteArray，很容易二次 OOM 或只拿到半截数据。这里复用固定缓冲，
 * 把现场分配压到只剩 [Throwable.getStackTrace] 的数组拷贝（JDK 内部拷贝，绕不掉）。
 *
 * 顺带避开 `File.list()`：改成读一次 `/proc/self/status`，线程数和 VSS 都在里面。
 */
internal object OomLite {
    private const val TAG = "OomLite"
    private const val TEXT_CAP = 12 * 1024
    private const val BYTE_CAP = TEXT_CAP * 3
    private const val PROC_CAP = 4 * 1024
    private const val TEXT_SOFT_LIMIT = TEXT_CAP - 512
    private const val MAX_FRAMES = 64
    private const val MAX_CAUSES = 4

    private val lock = Any()
    private var text: StringBuilder? = null
    private var out: ByteArray? = null
    private var proc: ByteArray? = null

    /** 在 [com.yj.crashkit.CrashKit.init] 阶段调用，OOM 现场就不用再申请缓冲。 */
    fun preallocate() {
        synchronized(lock) {
            allocateLocked()
        }
    }

    /** `{crashId}.dmp`：异常链 + 计数快照。 */
    fun writeStack(dir: File, crashId: String, throwable: Throwable?): File {
        synchronized(lock) {
            val sb = allocateLocked() ?: return DumpWriter.writeStack(dir, crashId, throwable)
            sb.setLength(0)
            appendThrowable(sb, throwable)
            sb.append('\n')
            appendCounters(sb)
            return flush(File(dir, "$crashId.dmp"), sb)
        }
    }

    /** `oom_lite.txt`：只有计数，进 telemetry 的 `res`。 */
    fun writeCounters(dir: File): File {
        synchronized(lock) {
            val sb = allocateLocked() ?: return DumpWriter.writeText(dir, CrashFiles.OOM_LITE, "")
            sb.setLength(0)
            appendCounters(sb)
            return flush(File(dir, CrashFiles.OOM_LITE), sb)
        }
    }

    /** [com.yj.crashkit.oom.JavaOomMonitor] 的预检快照，带触发原因。 */
    fun writeWatchSnapshot(dir: File, name: String, reason: String): File {
        synchronized(lock) {
            val sb = allocateLocked() ?: return DumpWriter.writeText(dir, name, reason)
            sb.setLength(0)
            sb.append("reason=").append(reason).append('\n')
            appendCounters(sb)
            return flush(File(dir, name), sb)
        }
    }

    internal fun countersText(): String {
        synchronized(lock) {
            val sb = allocateLocked() ?: return ""
            sb.setLength(0)
            appendCounters(sb)
            return sb.toString()
        }
    }

    private fun allocateLocked(): StringBuilder? {
        return try {
            var sb = text
            if (sb == null) {
                sb = StringBuilder(TEXT_CAP)
                text = sb
            }
            if (out == null) {
                out = ByteArray(BYTE_CAP)
            }
            if (proc == null) {
                proc = ByteArray(PROC_CAP)
            }
            sb
        } catch (t: Throwable) {
            KitLog.e(TAG, "preallocate", t)
            null
        }
    }

    private fun appendThrowable(sb: StringBuilder, throwable: Throwable?) {
        if (throwable == null) {
            sb.append("java.lang.OutOfMemoryError\n")
            return
        }
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth <= MAX_CAUSES && sb.length < TEXT_SOFT_LIMIT) {
            if (depth > 0) {
                sb.append("Caused by: ")
            }
            sb.append(t.javaClass.name)
            val msg = try {
                t.message
            } catch (_: Throwable) {
                null
            }
            if (msg != null) {
                sb.append(": ").append(msg)
            }
            sb.append('\n')
            appendFrames(sb, t)
            val cause = try {
                t.cause
            } catch (_: Throwable) {
                null
            }
            t = if (cause === t) null else cause
            depth++
        }
    }

    private fun appendFrames(sb: StringBuilder, t: Throwable) {
        val frames = try {
            t.stackTrace
        } catch (_: Throwable) {
            null
        } ?: return
        val keep = frames.size.coerceAtMost(MAX_FRAMES)
        for (i in 0 until keep) {
            if (sb.length >= TEXT_SOFT_LIMIT) {
                break
            }
            val f = frames[i] ?: continue
            sb.append("  at ").append(f.className).append('.').append(f.methodName)
            val file = f.fileName
            if (file != null) {
                sb.append('(').append(file).append(':').append(f.lineNumber).append(')')
            }
            sb.append('\n')
        }
        if (frames.size > keep) {
            sb.append("  ... ").append(frames.size - keep).append(" more\n")
        }
    }

    private fun appendCounters(sb: StringBuilder) {
        val rt = Runtime.getRuntime()
        val max = rt.maxMemory().coerceAtLeast(1L)
        val used = rt.totalMemory() - rt.freeMemory()
        // StringBuilder.append(Long) 直接写数字，不会像 String.format 那样再造对象
        sb.append("heap_used=").append(used).append('\n')
        sb.append("heap_max=").append(max).append('\n')
        sb.append("heap_pct=").append(used * 100L / max).append('\n')
        appendProcStatus(sb)
    }

    private fun appendProcStatus(sb: StringBuilder) {
        val buf = proc ?: return
        val n = try {
            FileInputStream("/proc/self/status").use { it.read(buf) }
        } catch (_: Throwable) {
            -1
        }
        if (n <= 0) {
            return
        }
        var i = 0
        while (i < n) {
            var end = i
            while (end < n && buf[end] != NEWLINE) {
                end++
            }
            if (wanted(buf, i, end)) {
                appendStatusLine(sb, buf, i, end)
            }
            i = end + 1
        }
    }

    private fun wanted(buf: ByteArray, from: Int, to: Int): Boolean {
        for (label in STATUS_LABELS) {
            if (matches(buf, from, to, label)) {
                return true
            }
        }
        return false
    }

    private fun matches(buf: ByteArray, from: Int, to: Int, literal: String): Boolean {
        if (from + literal.length > to) {
            return false
        }
        for (k in literal.indices) {
            if (buf[from + k].toInt().toChar() != literal[k]) {
                return false
            }
        }
        return true
    }

    /** `VmSize:\t  123456 kB` → `VmSize=123456 kB`。 */
    private fun appendStatusLine(sb: StringBuilder, buf: ByteArray, from: Int, to: Int) {
        var colon = from
        while (colon < to && buf[colon] != COLON) {
            colon++
        }
        if (colon >= to) {
            return
        }
        var k = from
        while (k < colon) {
            sb.append(buf[k].toInt().toChar())
            k++
        }
        sb.append('=')
        var j = colon + 1
        var gap = false
        var wrote = false
        while (j < to) {
            val c = buf[j].toInt().toChar()
            if (c == ' ' || c == '\t' || c == '\r') {
                gap = wrote
            } else {
                if (gap) {
                    sb.append(' ')
                    gap = false
                }
                sb.append(c)
                wrote = true
            }
            j++
        }
        sb.append('\n')
    }

    private fun flush(file: File, sb: StringBuilder): File {
        val buf = out ?: return file
        val n = encodeUtf8(sb, buf)
        try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            FileOutputStream(file).use { fos ->
                fos.write(buf, 0, n)
                fos.flush()
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "write ${file.name}", t)
        }
        return file
    }

    /** 手写 UTF-8，避开 `String.getBytes` 的中间数组。代理对写 `?`，dump 文本不需要 emoji。 */
    private fun encodeUtf8(sb: StringBuilder, buf: ByteArray): Int {
        var n = 0
        var i = 0
        val len = sb.length
        while (i < len && n + 3 <= buf.size) {
            val c = sb[i].code
            when {
                c in 0xD800..0xDFFF -> buf[n++] = QUESTION
                c < 0x80 -> buf[n++] = c.toByte()
                c < 0x800 -> {
                    buf[n++] = (0xC0 or (c shr 6)).toByte()
                    buf[n++] = (0x80 or (c and 0x3F)).toByte()
                }
                else -> {
                    buf[n++] = (0xE0 or (c shr 12)).toByte()
                    buf[n++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    buf[n++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
            i++
        }
        return n
    }

    private const val NEWLINE = '\n'.code.toByte()
    private const val COLON = ':'.code.toByte()
    private const val QUESTION = '?'.code.toByte()

    private val STATUS_LABELS = arrayOf("VmSize:", "VmRSS:", "VmSwap:", "Threads:", "FDSize:")
}
