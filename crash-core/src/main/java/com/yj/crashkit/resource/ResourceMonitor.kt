package com.yj.crashkit.resource

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.util.KitLog

/**
 * 运行时 FD / 内存 / 线程监控开关。
 * 线上只在崩溃时读 `/proc`；inline 轮询必须先 [CrashKitLab.enable]。
 */
object ResourceMonitor {
    private const val TAG = "ResourceMonitor"
    private val lock = Any()
    private var handler: Handler? = null
    @Volatile private var polling = false

    @JvmStatic
    fun openFdInfo(
        type: Int,
        inline: Boolean,
        javaStack: Boolean,
        @Suppress("UNUSED_PARAMETER") context: Context?,
    ) {
        CrashKitRuntime.get()?.setFdMonitor(true)
        maybeStartPolling(inline)
        KitLog.i(TAG, "openFdInfo type=$type inline=$inline javaStack=$javaStack")
    }

    @JvmStatic
    fun closeFdInfo() {
        CrashKitRuntime.get()?.setFdMonitor(false)
    }

    @JvmStatic
    fun openMemInfo(@Suppress("UNUSED_PARAMETER") context: Context?, inline: Boolean) {
        CrashKitRuntime.get()?.setMemMonitor(true)
        maybeStartPolling(inline)
        KitLog.i(TAG, "openMemInfo inline=$inline")
    }

    @JvmStatic
    fun closeMemInfo() {
        CrashKitRuntime.get()?.setMemMonitor(false)
    }

    @JvmStatic
    fun openThreadInfo(inline: Boolean) {
        CrashKitRuntime.get()?.setThreadMonitor(true)
        maybeStartPolling(inline)
        KitLog.i(TAG, "openThreadInfo inline=$inline")
    }

    @JvmStatic
    fun closeThreadInfo() {
        CrashKitRuntime.get()?.setThreadMonitor(false)
    }

    private fun maybeStartPolling(inline: Boolean) {
        if (!CrashKitOnlinePolicy.allowInlinePolling(inline, CrashKitLab.isEnabled())) {
            if (inline) {
                KitLog.i(TAG, "skip inline poll: CrashKitLab.enable() was not called")
            }
            return
        }
        startPolling()
    }

    private fun pollingHandler(): Handler {
        val existing = handler
        if (existing != null) {
            return existing
        }
        synchronized(lock) {
            var created = handler
            if (created == null) {
                val thread = HandlerThread("CrashKit-Res")
                thread.start()
                created = Handler(thread.looper)
                handler = created
            }
            return created
        }
    }

    private fun startPolling() {
        if (polling) {
            return
        }
        polling = true
        val h = pollingHandler()
        h.post(object : Runnable {
            override fun run() {
                try {
                    val rt = CrashKitRuntime.get() ?: return
                    if (rt.isFdMonitor() || rt.isMemMonitor() || rt.isThreadMonitor()) {
                        RecordInfo.dumpEnabled(rt.dumpDir, false)
                        h.postDelayed(this, 15_000L)
                    }
                } catch (t: Throwable) {
                    KitLog.e(TAG, "poll", t)
                }
            }
        })
    }
}
