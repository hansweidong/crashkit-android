package com.yj.crashkit

/**
 * 崩溃三钩子。顺序不可调：pre →（写 dump/syslog、合并 userLogList）→ crash → 三阶段 reporter → after。
 */
interface CrashCallback {
    fun preCrashCallback(
        nativeCrash: Boolean,
        dumpFile: String,
        dumpSymbolFile: String,
        logFile: String,
    )

    fun crashCallback(
        crashId: String,
        nativeCrash: Boolean,
        dumpFile: String,
        dumpSymbolFile: String,
        logFile: String,
    )

    fun afterCrashCallback(
        crashId: String,
        nativeCrash: Boolean,
        dumpFile: String,
        dumpSymbolFile: String,
        logFile: String,
    )
}
