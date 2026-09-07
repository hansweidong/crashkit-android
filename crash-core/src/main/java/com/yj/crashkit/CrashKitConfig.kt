package com.yj.crashkit

import android.content.Context
import com.yj.crashkit.reporter.CrashReporter
import com.yj.crashkit.reporter.TelemetryCrashReporter
import com.yj.crashkit.util.KitLog
import java.io.File

class CrashKitConfig private constructor(
    val context: Context?,
    val appId: String,
    val appVersion: String?,
    val guid: String,
    val appMarket: String,
    val dumpDir: File?,
    val logger: KitLog.ILog?,
    val reporter: CrashReporter?,
    val telemetrySink: CrashTelemetrySink?,
) {
    open class Builder {
        private var contextField: Context? = null
        private var appIdField: String? = null
        private var appVersionField: String? = null
        private var guidField: String? = null
        private var appMarketField: String = "official"
        private var dumpDirField: File? = null
        private var loggerField: KitLog.ILog? = null
        private var reporterField: CrashReporter? = null
        private var telemetrySinkField: CrashTelemetrySink? = null
        private var wigoLogUrlField: String? = null
        private var wigoLogSessionField: (() -> com.yj.crashkit.log.WigoLogSession)? = null

        fun setContext(context: Context) = apply { contextField = context }
        fun setAppId(appId: String?) = apply { appIdField = appId }
        fun setAppVersion(appVersion: String?) = apply { appVersionField = appVersion }
        fun setGUid(guid: String?) = apply { guidField = guid }
        fun setAppMarket(appMarket: String?) = apply {
            appMarketField = appMarket ?: "official"
        }
        fun setDumpDir(dumpDir: File?) = apply { dumpDirField = dumpDir }
        fun setLogger(logger: KitLog.ILog?) = apply { loggerField = logger }

        /**
         * 高级三阶段上报（META / DUMP / LOGS）。埋点请用 [setTelemetrySink]。
         * 与 sink 同时设置时以本 reporter 为准。
         */
        fun setReporter(reporter: CrashReporter?) = apply { reporterField = reporter }

        /**
         * 埋点宿主入口。采集完成后回调一次 [CrashTelemetrySink.onTelemetry]，
         * 内部走 [TelemetryCrashReporter]，DUMP / LOGS 不会再进这个回调。
         */
        fun setTelemetrySink(sink: CrashTelemetrySink?) = apply { telemetrySinkField = sink }

        /**
         * 按 Wigo `LogModel.submitCrash` 的协议把崩溃 / ANR POST 到 [url]。
         * 运行时字段（设备 id、userId、lanId）每次上报时调用 [session] 现取。
         * 与 [setTelemetrySink] 同时设置时，先回调 sink，再上传；上传失败会保留 pending。
         */
        fun setWigoLogUpload(
            url: String,
            session: () -> com.yj.crashkit.log.WigoLogSession = { com.yj.crashkit.log.WigoLogSession() },
        ) = apply {
            wigoLogUrlField = url
            wigoLogSessionField = session
        }

        fun build(): CrashKitConfig {
            val wigoUrl = wigoLogUrlField?.trim().orEmpty()
            val wigoSink = if (wigoUrl.isEmpty()) {
                null
            } else {
                com.yj.crashkit.log.WigoLogCrashSink(
                    wigoUrl,
                    wigoLogSessionField ?: { com.yj.crashkit.log.WigoLogSession() },
                )
            }
            val sink = composeSink(telemetrySinkField, wigoSink)
            val reporter = when {
                reporterField != null -> reporterField
                sink != null -> TelemetryCrashReporter(sink)
                else -> null
            }
            return CrashKitConfig(
                context = contextField,
                appId = appIdField.orEmpty(),
                appVersion = appVersionField,
                guid = guidField.orEmpty(),
                appMarket = appMarketField,
                dumpDir = dumpDirField,
                logger = loggerField,
                reporter = reporter,
                telemetrySink = sink,
            )
        }
    }
}

private fun composeSink(
    host: CrashTelemetrySink?,
    wigo: CrashTelemetryAckSink?,
): CrashTelemetrySink? {
    if (host == null) {
        return wigo
    }
    if (wigo == null) {
        return host
    }
    return CrashTelemetryAckSink { payload, record ->
        val hostOk = if (host is CrashTelemetryAckSink) {
            host.onTelemetryAck(payload, record)
        } else {
            host.onTelemetry(payload, record)
            true
        }
        val uploadOk = wigo.onTelemetryAck(payload, record)
        hostOk && uploadOk
    }
}
