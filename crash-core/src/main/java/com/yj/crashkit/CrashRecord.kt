package com.yj.crashkit

import java.io.File

/**
 * 一次崩溃 / ANR 的落盘结果。上报实现只消费这个对象，不要再去采栈。
 */
class CrashRecord(
    val crashId: String,
    val type: CrashType,
    metaJson: String?,
    dumpFiles: List<File>?,
    logFiles: List<File>?,
) {
    val metaJson: String = metaJson ?: "{}"
    val dumpFiles: List<File> = dumpFiles.orEmpty().toList()
    val logFiles: List<File> = logFiles.orEmpty().toList()

    fun filesFor(stage: ReportStage): List<File> = when (stage) {
        ReportStage.DUMP -> dumpFiles
        ReportStage.LOGS -> logFiles
        ReportStage.META -> emptyList()
    }

    /**
     * 给宿主的精简结果。埋点用 [CrashTelemetryPayload.wireText]，不要拼接 dump / logcat。
     */
    @JvmOverloads
    fun telemetry(maxChars: Int = CrashTelemetry.MAX_CHARS): CrashTelemetryPayload {
        return CrashTelemetry.of(this, maxChars)
    }

    @JvmOverloads
    fun telemetryText(maxChars: Int = CrashTelemetry.MAX_CHARS): String {
        return telemetry(maxChars).wireText
    }
}
