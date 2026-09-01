package com.yj.crashkit

/**
 * 仅 [CrashReporter] 三阶段使用。埋点走 [CrashTelemetrySink]，不会按 stage 拆三次。
 *
 * META：精简信息在 [CrashRecord.telemetry]；DUMP / LOGS：本地文件路径在 [CrashRecord.filesFor]。
 */
enum class ReportStage {
    META,
    DUMP,
    LOGS
}
