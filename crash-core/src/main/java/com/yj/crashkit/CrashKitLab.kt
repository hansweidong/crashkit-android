package com.yj.crashkit

import android.app.Application
import android.content.Context
import com.yj.crashkit.anr.MainThreadSampler
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.nativecrash.NativeCrashBridge
import com.yj.crashkit.util.KitLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 不适合默认带上线的诊断能力。正式包不要调用 [enable]。
 *
 * 隔离内容：主动制造崩溃、低于 200ms 的主线程采样、inline `/proc` 轮询。
 * hprof 采集已整体下线，Lab 也拿不到（`Debug.dumpHprofData` 会 suspend 整个 VM 约 20s）。
 */
object CrashKitLab {
    const val HIGH_FREQ_ANR_INTERVAL_MS = MainThreadSampler.HIGH_FREQ_INTERVAL_MS

    private const val TAG = "CrashKitLab"
    private val enabled = AtomicBoolean(false)

    @JvmStatic
    fun enable() {
        if (enabled.compareAndSet(false, true)) {
            KitLog.i(TAG, "enabled; do not ship this on release")
        }
    }

    @JvmStatic
    fun isEnabled(): Boolean = enabled.get()

    @JvmStatic
    fun testJavaCrash() {
        if (!requireLab("testJavaCrash")) {
            return
        }
        throw RuntimeException("CrashKit test java crash")
    }

    @JvmStatic
    fun testNativeCrash() {
        if (!requireLab("testNativeCrash")) {
            return
        }
        NativeCrashBridge.testNativeCrash()
    }

    /**
     * 打开 lab 后，按 [intervalMs] 采主线程栈（可低于 200ms，例如 [HIGH_FREQ_ANR_INTERVAL_MS]）。
     * ANR 的 SIGQUIT 旁路已在 [CrashKit.init] 启动；若尚未 init，init 之后采样仍有效。
     */
    @JvmStatic
    @JvmOverloads
    fun startAggressiveAnrSampling(intervalMs: Long = HIGH_FREQ_ANR_INTERVAL_MS) {
        if (!requireLab("startAggressiveAnrSampling")) {
            return
        }
        MainThreadSampler.get().start(intervalMs)
    }

    @JvmStatic
    fun openFdInline(type: Int, javaStack: Boolean, context: Context?) {
        if (!requireLab("openFdInline")) {
            return
        }
        CrashKit.openFdInfo(type, true, javaStack, context)
    }

    @JvmStatic
    fun openMemInline(context: Context?) {
        if (!requireLab("openMemInline")) {
            return
        }
        CrashKit.openMemInfo(context, true)
    }

    @JvmStatic
    fun openThreadInline() {
        if (!requireLab("openThreadInline")) {
            return
        }
        CrashKit.openThreadInfo(true)
    }

    /**
     * hprof 已下线：`Debug.dumpHprofData` 会 suspend 整个 VM 约 20s，几乎必然自己触发一次真 ANR。
     * 这里只启动 OOM 预检的计数快照，等价于 [CrashKit.openJavaOom]。
     */
    @Deprecated(
        message = "hprof 采集已下线，改用 CrashKit.openJavaOom(app, false)",
        replaceWith = ReplaceWith("CrashKit.openJavaOom(application, false)"),
    )
    @JvmStatic
    fun openJavaOomDumpHprof(application: Application?) {
        KitLog.i(TAG, "hprof is retired; starting counter-only OOM watch instead")
        CrashKit.openJavaOom(application, false)
    }

    internal fun requireLab(feature: String): Boolean {
        if (enabled.get()) {
            return true
        }
        KitLog.i(TAG, "skip $feature: CrashKitLab.enable() was not called")
        return false
    }

    internal fun logClamp(feature: String, from: Long, to: Long) {
        KitLog.i(TAG, "$feature clamped $from -> $to (lab off, min=${CrashKitOnlinePolicy.MIN_SAMPLE_INTERVAL_MS}ms)")
    }
}
