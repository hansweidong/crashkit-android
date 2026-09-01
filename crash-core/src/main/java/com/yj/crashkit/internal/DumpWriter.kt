package com.yj.crashkit.internal

import com.yj.crashkit.util.KitLog
import com.yj.crashkit.util.StackTraceFormatter
import java.io.File
import java.nio.charset.Charset

object DumpWriter {
    private const val TAG = "DumpWriter"

    @JvmStatic
    fun writeText(dir: File, name: String, content: String?): File {
        val file = File(dir, name)
        return try {
            val parent = file.parentFile
            if (parent != null && !parent.exists()) {
                parent.mkdirs()
            }
            file.outputStream().use { fos ->
                fos.write((content ?: "").toByteArray(Charset.forName("UTF-8")))
                fos.flush()
            }
            file
        } catch (t: Throwable) {
            KitLog.e(TAG, "write $name", t)
            file
        }
    }

    @JvmStatic
    fun writeStack(dir: File, crashId: String, t: Throwable?): File {
        return writeText(dir, "$crashId.dmp", StackTraceFormatter.fromThrowable(t))
    }

    @JvmStatic
    fun writeStack(dir: File, crashId: String, stack: String?): File {
        return writeText(dir, "$crashId.dmp", stack ?: "")
    }
}
