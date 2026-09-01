package com.yj.crashkit.reporter

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetry
import com.yj.crashkit.CrashTelemetrySink
import com.yj.crashkit.ReportStage
import com.yj.crashkit.util.KitLog

/**
 * 把三阶段 [CrashReporter] 收成一次 [CrashTelemetrySink] 回调。
 *
 * META：把 [CrashTelemetry.of] 交给宿主；DUMP / LOGS：立刻 [CrashReporter.ReportCallback.onResult]，不传文件。
 * 埋点宿主更直接的方式是 [com.yj.crashkit.CrashKitConfig.Builder.setTelemetrySink]，不必自己 new 本类。
 */
class TelemetryCrashReporter(
    private val sink: CrashTelemetrySink,
) : CrashReporter {
    override fun report(record: CrashRecord, stage: ReportStage, callback: CrashReporter.ReportCallback) {
        if (stage != ReportStage.META) {
            callback.onResult(true)
            return
        }
        try {
            sink.onTelemetry(CrashTelemetry.of(record), record)
            callback.onResult(true)
        } catch (t: Throwable) {
            KitLog.e(TAG, "telemetry sink", t)
            callback.onResult(false)
        }
    }

    companion object {
        private const val TAG = "TelemetryCrashReporter"
    }
}
