package com.yj.crashkit.internal

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * 当前进程名。用于把 dump 目录按进程隔离。
 *
 * 多进程共用一个 dump 目录时，`native_crash.dmp` / `anr_error.log` / `main_stack.txt`
 * 这些固定名会互相覆盖，`pending/` 也会串。分目录之后固定名就安全了。
 */
internal object ProcessName {
    @Volatile private var cached: String? = null

    fun current(context: Context?): String {
        val hit = cached
        if (hit != null) {
            return hit
        }
        val name = resolve(context)
        cached = name
        return name
    }

    /**
     * dump 目录里的进程段。主进程返回空串，沿用原来的目录，避免升级后读不到旧的 `pending/`。
     * 子进程取 `:` 后面那截，例如 `com.foo.app:push` → `push`。
     */
    fun dirSegment(context: Context?, packageName: String): String {
        val name = current(context)
        if (name.isEmpty() || name == packageName) {
            return ""
        }
        val colon = name.lastIndexOf(':')
        val tail = if (colon >= 0 && colon < name.length - 1) {
            name.substring(colon + 1)
        } else {
            name
        }
        return sanitize(tail)
    }

    internal fun sanitize(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c.isLetterOrDigit() || c == '_' || c == '-' || c == '.') {
                sb.append(c)
            } else {
                sb.append('_')
            }
        }
        val out = sb.toString().trim('.')
        return if (out.isEmpty()) "p" else out
    }

    private fun resolve(context: Context?): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val fromApi = try {
                Application.getProcessName()
            } catch (_: Throwable) {
                null
            }
            if (!fromApi.isNullOrEmpty()) {
                return fromApi
            }
        }
        val fromCmdline = readCmdline()
        if (fromCmdline.isNotEmpty()) {
            return fromCmdline
        }
        return fromActivityManager(context)
    }

    private fun readCmdline(): String {
        return try {
            val raw = File("/proc/self/cmdline").readText()
            raw.substringBefore('\u0000').trim()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun fromActivityManager(context: Context?): String {
        val am = context?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return ""
        return try {
            val pid = Process.myPid()
            am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
