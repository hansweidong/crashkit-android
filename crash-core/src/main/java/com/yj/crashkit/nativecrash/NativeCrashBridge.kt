package com.yj.crashkit.nativecrash

import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.util.KitLog

/**
 * JNI 桥。signal 里只写 dump / 通知 pipe；Kotlin 管线在 watchdog 线程跑。
 *
 * JNI 符号必须保持 `Java_com_yj_crashkit_nativecrash_NativeCrashBridge_*`，
 * 回调 `onNativeDumpFinished` / `onAnrTraces` 必须是该类上的 @JvmStatic 方法。
 */
object NativeCrashBridge {
    private const val TAG = "NativeCrashBridge"

    @Volatile private var loaded = false
    @Volatile private var ready = false
    @Volatile private var pipeline: CrashPipeline? = null
    @Volatile private var anrReceiver: AnrTraceReceiver? = null

    fun interface AnrTraceReceiver {
        fun onTraces(path: String?)
    }

    @JvmStatic
    fun install(dumpDir: String?, pipeline: CrashPipeline?): Boolean {
        this.pipeline = pipeline
        if (!load()) {
            return false
        }
        return try {
            val rc = nativeInit(dumpDir)
            ready = rc == 0
            KitLog.i(TAG, "nativeInit rc=$rc")
            ready
        } catch (t: Throwable) {
            KitLog.e(TAG, "nativeInit failed", t)
            false
        }
    }

    @JvmStatic
    fun markHandled() {
        if (ready) {
            try {
                nativeMarkHandled()
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun notifyJavaDone() {
        if (ready) {
            try {
                nativeNotifyJavaDone()
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun testNativeCrash() {
        if (!CrashKitLab.requireLab("testNativeCrash")) {
            return
        }
        check(ready) { "native crash handler not ready" }
        nativeTestCrash()
    }

    @JvmStatic
    fun installFdHook(type: Int, inlineMode: Boolean, javaStack: Boolean) {
        if (ready) {
            nativeInstallFdHook(type, inlineMode, javaStack)
        }
    }

    @JvmStatic
    fun installMemHook(enable: Boolean) {
        if (ready) {
            nativeInstallMemHook(enable)
        }
    }

    @JvmStatic
    fun installThreadHook(enable: Boolean) {
        if (ready) {
            nativeInstallThreadHook(enable)
        }
    }

    @JvmStatic
    fun dumpHookedFd(): String {
        if (!ready) {
            return ""
        }
        return try {
            nativeDumpHookedFd() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun dumpHookedMem(): String {
        if (!ready) {
            return ""
        }
        return try {
            nativeDumpHookedMem() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    @JvmStatic
    fun dumpNativeThreadStack(): String {
        if (!ready) {
            return ""
        }
        return try {
            nativeDumpNativeThreadStack() ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    /** JNI watchdog 回调：Native 崩溃 dump 已写完。 */
    @JvmStatic
    fun onNativeDumpFinished(dumpPath: String?) {
        val p = pipeline
        if (p != null) {
            p.handleNative(dumpPath)
        } else {
            notifyJavaDone()
        }
    }

    /** JNI watchdog 回调：SIGQUIT traces 已写完。 */
    @JvmStatic
    fun onAnrTraces(tracesPath: String?) {
        anrReceiver?.onTraces(tracesPath)
    }

    @JvmStatic
    fun setAnrTraceReceiver(receiver: AnrTraceReceiver?) {
        anrReceiver = receiver
    }

    private fun load(): Boolean {
        if (loaded) {
            return true
        }
        return try {
            System.loadLibrary("crashkit")
            loaded = true
            true
        } catch (t: Throwable) {
            KitLog.e(TAG, "loadLibrary crashkit failed", t)
            false
        }
    }

    @JvmStatic
    private external fun nativeInit(dumpDir: String?): Int

    @JvmStatic
    private external fun nativeMarkHandled()

    @JvmStatic
    private external fun nativeNotifyJavaDone()

    @JvmStatic
    private external fun nativeTestCrash()

    @JvmStatic
    private external fun nativeInstallFdHook(type: Int, inlineMode: Boolean, javaStack: Boolean)

    @JvmStatic
    private external fun nativeInstallMemHook(enable: Boolean)

    @JvmStatic
    private external fun nativeInstallThreadHook(enable: Boolean)

    @JvmStatic
    private external fun nativeDumpHookedFd(): String?

    @JvmStatic
    private external fun nativeDumpHookedMem(): String?

    @JvmStatic
    private external fun nativeDumpNativeThreadStack(): String?
}
