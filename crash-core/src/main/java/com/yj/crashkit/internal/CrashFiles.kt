package com.yj.crashkit.internal

/**
 * dump 目录里的固定文件名。
 *
 * [TelemetryCompact] 按名字取文件决定压缩策略，native 侧也硬编了 [NATIVE_CRASH_DUMP]，
 * 所以这些名字是跨语言约定，改名要同时改 `crashkit_native.cpp`。
 * 多进程之间靠 [CrashKitRuntime.dumpDir] 分目录隔离，不靠文件名带 pid。
 */
internal object CrashFiles {
    /** native signal handler 直接写的固定名，随后由 [CrashPipeline] 改成 `{crashId}.dmp`。 */
    const val NATIVE_CRASH_DUMP = "native_crash.dmp"

    /** ANR 的系统侧信息：`ProcessErrorStateInfo.longMsg` 或 `ApplicationExitInfo.description`。 */
    const val ANR_ERROR_LOG = "anr_error.log"

    /** ANR 的主线程栈快照（含可选采样历史）。 */
    const val ANR_MAIN_STACK = "main_stack.txt"

    /**
     * ANR 全线程 Java 栈。现场上报写入；埋点只抽 BLOCKED / Binder / 业务 RUNNABLE。
     * 系统 traces 仍用 [ANR_TRACES]，只在 `ApplicationExitInfo` 补报时出现。
     */
    const val ANR_THREADS = "threads.txt"

    /** 系统 traces。本 SDK 不再自己写，只在 `ApplicationExitInfo` 补报时落这一份。 */
    const val ANR_TRACES = "traces.txt"

    /** OOM 计数快照，进 telemetry 的 `res` 字段。 */
    const val OOM_LITE = "oom_lite.txt"

    const val PENDING_DIR = "pending"
}
