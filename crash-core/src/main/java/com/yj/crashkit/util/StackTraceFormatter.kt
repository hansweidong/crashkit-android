package com.yj.crashkit.util

import java.io.PrintWriter
import java.io.StringWriter
import java.util.UUID

object StackTraceFormatter {
    fun fromThrowable(t: Throwable?): String {
        if (t == null) return ""
        val sw = StringWriter(256)
        PrintWriter(sw).use { pw ->
            t.printStackTrace(pw)
            pw.flush()
        }
        return sw.toString()
    }

    fun newCrashId(): String = UUID.randomUUID().toString()
}
