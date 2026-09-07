package com.yj.crashkit.internal

import java.io.FileInputStream

/**
 * 读一次 `/proc/self/status` 取出 VSS / RSS / 线程数。
 *
 * VSS 用于 32 位进程的地址空间耗尽型 OOM（`mmap` / `pthread_create` 失败），
 * 这类 OOM 在 Java 堆占比上完全看不出来，对应 KOOM 的 `VssOOMTracker`。
 */
internal object ProcStatus {
    private const val BUF_CAP = 4 * 1024

    class Snapshot(
        val vssKb: Long,
        val rssKb: Long,
        val threads: Int,
    )

    fun read(): Snapshot {
        val buf = ByteArray(BUF_CAP)
        val n = try {
            FileInputStream("/proc/self/status").use { it.read(buf) }
        } catch (_: Throwable) {
            -1
        }
        if (n <= 0) {
            return Snapshot(-1L, -1L, -1)
        }
        var vss = -1L
        var rss = -1L
        var threads = -1
        var i = 0
        while (i < n) {
            var end = i
            while (end < n && buf[end] != NEWLINE) {
                end++
            }
            when {
                startsWith(buf, i, end, "VmSize:") -> vss = firstNumber(buf, i, end)
                startsWith(buf, i, end, "VmRSS:") -> rss = firstNumber(buf, i, end)
                startsWith(buf, i, end, "Threads:") -> threads = firstNumber(buf, i, end).toInt()
            }
            i = end + 1
        }
        return Snapshot(vss, rss, threads)
    }

    private fun startsWith(buf: ByteArray, from: Int, to: Int, literal: String): Boolean {
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

    private fun firstNumber(buf: ByteArray, from: Int, to: Int): Long {
        var i = from
        while (i < to && (buf[i] < ZERO || buf[i] > NINE)) {
            i++
        }
        if (i >= to) {
            return -1L
        }
        var value = 0L
        while (i < to && buf[i] >= ZERO && buf[i] <= NINE) {
            value = value * 10 + (buf[i] - ZERO)
            i++
        }
        return value
    }

    private const val NEWLINE = '\n'.code.toByte()
    private const val ZERO = '0'.code.toByte()
    private const val NINE = '9'.code.toByte()
}
