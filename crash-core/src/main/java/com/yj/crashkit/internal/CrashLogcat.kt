package com.yj.crashkit.internal

import android.os.Build
import com.yj.crashkit.util.KitLog
import java.io.File
import java.io.InputStreamReader

object CrashLogcat {
    private const val TAG = "CrashLogcat"
    private const val TIMEOUT_MS = 300L

    @JvmStatic
    @JvmOverloads
    fun capture(
        dumpDir: File,
        crashId: String,
        maxLines: Int = 0,
    ): File? {
        if (maxLines <= 0) {
            return null
        }
        val sb = StringBuilder(4 * 1024)
        sb.append("CURRENT_LOGCAT:\n")
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", "-t", maxLines.toString(), "-d", "*:I"),
            )
            val running = process ?: return DumpWriter.writeText(
                dumpDir, "$crashId.syslog", sb.toString(),
            )
            val chunk = StringBuilder()
            val reader = Thread({
                try {
                    InputStreamReader(running.inputStream).buffered(1024).use { input ->
                        var n = 0
                        while (n < maxLines) {
                            val line = input.readLine() ?: break
                            chunk.append(line).append('\n')
                            n++
                        }
                    }
                } catch (_: Throwable) {
                }
            }, "crashkit-logcat")
            reader.isDaemon = true
            reader.start()
            reader.join(TIMEOUT_MS)
            if (reader.isAlive) {
                KitLog.e(TAG, "logcat timeout")
                destroyQuietly(running)
                reader.join(50)
                sb.append("logcat timeout\n")
            } else {
                sb.append(chunk)
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "logcat failed", t)
            sb.append("logcat failed: ").append(t.message).append('\n')
        } finally {
            destroyQuietly(process)
        }
        return DumpWriter.writeText(dumpDir, "$crashId.syslog", sb.toString())
    }

    private fun destroyQuietly(process: Process?) {
        if (process == null) {
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                process.destroyForcibly()
            } else {
                process.destroy()
            }
        } catch (_: Throwable) {
        }
    }
}
