package com.yj.crashkit.anr

import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.util.KitLog

/**
 * 原 `com.yy.sdk.crashreport.anr.CatonChecker`。
 * 未 [CrashKitLab.enable] 时 `start(53)` 不会真的按 53ms 采样。
 */
object CatonChecker {
    private const val TAG = "CatonChecker"

    @JvmStatic
    fun getIns(): CatonChecker = this

    fun start(intervalMs: Long) {
        if (!CrashKitLab.isEnabled()) {
            KitLog.i(TAG, "start($intervalMs) skipped; call CrashKitLab.enable() first")
            return
        }
        MainThreadSampler.get().start(intervalMs)
    }

    fun setMaxEntryCount(max: Int) {
        MainThreadSampler.get().setMaxEntryCount(max)
    }
}
