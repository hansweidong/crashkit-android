package com.yj.crashkit.reporter

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.ReportStage

/**
 * 高级上报口：META → DUMP → LOGS 各回调一次，每次必须 [ReportCallback.onResult]。
 *
 * 埋点不要实现本接口去拼文件。用 [com.yj.crashkit.CrashTelemetrySink]，
 * SDK 会通过 [TelemetryCrashReporter] 只把 [com.yj.crashkit.CrashTelemetryPayload] 交一次。
 */
fun interface CrashReporter {
    fun report(record: CrashRecord, stage: ReportStage, callback: ReportCallback)

    fun interface ReportCallback {
        fun onResult(success: Boolean)
    }
}
