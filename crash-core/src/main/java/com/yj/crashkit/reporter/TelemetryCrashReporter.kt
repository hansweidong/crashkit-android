package com.yj.crashkit.reporter

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetry
import com.yj.crashkit.CrashTelemetryAckSink
import com.yj.crashkit.CrashTelemetrySink
import com.yj.crashkit.ReportStage
import com.yj.crashkit.util.KitLog
import java.util.concurrent.atomic.AtomicReference

/**
 * 把三阶段 [CrashReporter] 收成一次 [CrashTelemetrySink] 回调。
 *
 * META：把 [CrashTelemetry.of] 交给宿主；DUMP / LOGS：立刻 [CrashReporter.ReportCallback.onResult]，不传文件。
 * 同一 [CrashRecord.crashId] 只回调一次，避免宿主或管线重入把 payload 打两遍。
 */
class TelemetryCrashReporter(
    private val sink: CrashTelemetrySink,
) : CrashReporter {
    private val lastEmittedId = AtomicReference<String?>(null)

    override fun report(record: CrashRecord, stage: ReportStage, callback: CrashReporter.ReportCallback) {
        if (stage != ReportStage.META) {
            callback.onResult(true)
            return
        }
        val id = record.crashId
        while (true) {
            val prev = lastEmittedId.get()
            if (prev != null && prev == id) {
                KitLog.i(TAG, "skip duplicate telemetry id=$id")
                callback.onResult(true)
                return
            }
            if (lastEmittedId.compareAndSet(prev, id)) {
                break
            }
        }
        try {
            val payload = CrashTelemetry.of(record)
            // 成功路径也要留一行：否则宿主没法从日志区分「sink 收到了」和「什么都没发生」
            KitLog.i(
                TAG,
                "-> sink id=$id type=${record.type} chars=${payload.wireText.length}" +
                    " truncated=${payload.truncated} dumps=${record.dumpFiles.size} logs=${record.logFiles.size}",
            )
            // 只实现 CrashTelemetrySink 的宿主给不出投递结果，只能按成功记；
            // 实现 CrashTelemetryAckSink 的可以返回 false 让记录留在 pending 等下次重投。
            val ok = if (sink is CrashTelemetryAckSink) {
                sink.onTelemetryAck(payload, record)
            } else {
                sink.onTelemetry(payload, record)
                true
            }
            KitLog.i(TAG, "sink returned id=$id ok=$ok ackable=${sink is CrashTelemetryAckSink}")
            callback.onResult(ok)
        } catch (t: Throwable) {
            KitLog.e(TAG, "telemetry sink", t)
            callback.onResult(false)
        }
    }

    companion object {
        private const val TAG = "TelemetryCrashReporter"
    }
}
