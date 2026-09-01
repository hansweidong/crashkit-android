package com.yj.crashkit.internal

import com.yj.crashkit.util.KitLog
import java.io.File
import java.io.InputStreamReader

object CrashLogcat {
    private const val TAG = "CrashLogcat"

    @JvmStatic
    @JvmOverloads
    fun capture(
        dumpDir: File,
        crashId: String,
        maxLines: Int = CrashKitOnlinePolicy.ONLINE_LOGCAT_LINES,
    ): File? {
        if (maxLines <= 0) {
            return null
        }
        val sb = StringBuilder(4 * 1024)
        sb.append("CURRENT_LOGCAT:\n")
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", "-t", maxLines.toString(), "-d", "*:I"),
            )
            InputStreamReader(process.inputStream).buffered(1024).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    sb.append(line).append('\n')
                }
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "logcat failed", t)
            sb.append("logcat failed: ").append(t.message).append('\n')
        }
        return DumpWriter.writeText(dumpDir, "$crashId.syslog", sb.toString())
    }
}
