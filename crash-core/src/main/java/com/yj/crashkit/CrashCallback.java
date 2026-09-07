package com.yj.crashkit;

/**
 * 崩溃三钩子。顺序不可调：pre → 落盘 → crash → 三阶段 reporter → after。
 * 未 override 的方法默认空实现，和原 crashreport 一致。
 */
public interface CrashCallback {
    default void preCrashCallback(
            boolean nativeCrash,
            String dumpFile,
            String dumpSymbolFile,
            String logFile
    ) {
    }

    default void crashCallback(
            String crashId,
            boolean nativeCrash,
            String dumpFile,
            String dumpSymbolFile,
            String logFile
    ) {
    }

    default void afterCrashCallback(
            String crashId,
            boolean nativeCrash,
            String dumpFile,
            String dumpSymbolFile,
            String logFile
    ) {
    }
}
