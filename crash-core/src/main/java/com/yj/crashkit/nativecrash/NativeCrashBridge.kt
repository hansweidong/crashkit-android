package com.yj.crashkit.nativecrash

import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.util.KitLog

/**
 * JNI 桥。signal 里只写 dump / 通知 pipe；Kotlin 管线在 native watchdog 线程跑。
 *
 * JNI 符号必须保持 `Java_com_yj_crashkit_nativecrash_NativeCrashBridge_*`。
 * Native 崩溃：`onNativeDumpFinished`。ANR：`onAnrSignal`（SIGQUIT 旁路，随后转给 Signal Catcher）。
 */
object NativeCrashBridge {
    private const val TAG = "NativeCrashBridge"

    @Volatile private var loaded = false
    @Volatile private var ready = false
    @Volatile private var pipeline: CrashPipeline? = null
    @Volatile private var anrSignalSink: (() -> Unit)? = null

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

    @JvmStatic
    fun installAnrSignal(): Boolean {
        if (!ready) {
            return false
        }
        return try {
            nativeInstallAnrSignal() == 0
        } catch (t: Throwable) {
            KitLog.e(TAG, "nativeInstallAnrSignal", t)
            false
        }
    }

    @JvmStatic
    fun setAnrSignalSink(sink: (() -> Unit)?) {
        anrSignalSink = sink
    }

    /** 旧 so 可能仍回调。 */
    @JvmStatic
    fun onAnrTraces(tracesPath: String?) {
        KitLog.i(TAG, "ignore leftover onAnrTraces path=$tracesPath")
    }

    /**
     * Native ANR 线程同步调用：抓完队头/栈后必须返回，native 才会把 SIGQUIT 转给 Signal Catcher。
     */
    @JvmStatic
    fun onAnrSignal() {
        try {
            anrSignalSink?.invoke()
        } catch (t: Throwable) {
            KitLog.e(TAG, "onAnrSignal", t)
        }
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

    @JvmStatic
    private external fun nativeInstallAnrSignal(): Int
}
