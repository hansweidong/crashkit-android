package com.yj.crashkit.util

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.RandomAccessFile

/**
 * 原 `com.yy.sdk.crashreport.util.MemFdInfoUtil`。
 * 读 `/proc` 做完载率 / 调试面板指标，不依赖旧 crashreport。
 */
object MemFdInfoUtil {
    enum class DeviceLevel(val value: Int) {
        UNKNOWN(0),
        LOW(1),
        MIDDLE(2),
        HIGH(3),
    }

    @JvmStatic
    fun getAppCpuRate(): Double {
        return try {
            val reader = RandomAccessFile("/proc/self/stat", "r")
            val line = reader.readLine() ?: return 0.0
            reader.close()
            val parts = line.split(" ")
            if (parts.size < 17) {
                return 0.0
            }
            val utime = parts[13].toLongOrNull() ?: 0L
            val stime = parts[14].toLongOrNull() ?: 0L
            val total = (utime + stime).toDouble()
            // 单次快照无法算精确占比，给完载率一个可上报的相对值
            (total % 10000) / 100.0
        } catch (_: Throwable) {
            0.0
        }
    }

    @JvmStatic
    fun getLevel(context: Context?): DeviceLevel {
        if (context == null) {
            return DeviceLevel.UNKNOWN
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return DeviceLevel.UNKNOWN
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val totalMb = info.totalMem / (1024L * 1024L)
            when {
                totalMb >= 6144 -> DeviceLevel.HIGH
                totalMb >= 3072 -> DeviceLevel.MIDDLE
                else -> DeviceLevel.LOW
            }
        } catch (_: Throwable) {
            DeviceLevel.UNKNOWN
        }
    }

    @JvmStatic
    fun getMemFree(context: Context?): Long {
        if (context == null) {
            return 0L
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.availMem
        } catch (_: Throwable) {
            0L
        }
    }

    @JvmStatic
    fun getTotalMemory(context: Context?): Long {
        if (context == null) {
            return 0L
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return 0L
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            info.totalMem
        } catch (_: Throwable) {
            0L
        }
    }

    @JvmStatic
    fun getSelfMemInfo(): String {
        return try {
            val sb = StringBuilder()
            sb.append("java_used=").append(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
            sb.append(" native_heap=").append(Debug.getNativeHeapAllocatedSize())
            sb.append(" pss=").append(Debug.getPss())
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun getThreadInfo(): String {
        return try {
            val estimate = Thread.activeCount()
            val threads = arrayOfNulls<Thread>(estimate + 8)
            val n = Thread.enumerate(threads)
            val sb = StringBuilder()
            sb.append("java_threads=").append(n).append('\n')
            for (i in 0 until n) {
                val t = threads[i] ?: continue
                sb.append(t.name).append(' ').append(t.state).append('\n')
            }
            sb.toString()
        } catch (_: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun getThreadSize(): Int {
        return try {
            val dir = File("/proc/self/task")
            dir.list()?.size ?: Thread.activeCount()
        } catch (_: Throwable) {
            Thread.activeCount()
        }
    }

    @JvmStatic
    fun getVssSize(): Long {
        return try {
            BufferedReader(FileReader("/proc/self/status")).use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    if (line.startsWith("VmSize:")) {
                        val num = line.replace(Regex("[^0-9]"), "")
                        return (num.toLongOrNull() ?: 0L) * 1024L
                    }
                    line = reader.readLine()
                }
            }
            0L
        } catch (_: Throwable) {
            0L
        }
    }
}
