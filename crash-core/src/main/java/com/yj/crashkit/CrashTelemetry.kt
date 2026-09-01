package com.yj.crashkit

import com.yj.crashkit.internal.TelemetryCompact

/**
 * 把 [CrashRecord] 压成宿主可用的 [CrashTelemetryPayload]。
 *
 * 埋点请用 [CrashTelemetrySink] 拿现成对象，不要自己拼 dump。
 */
object CrashTelemetry {
    const val MAX_CHARS = TelemetryCompact.MAX_CHARS

    @JvmStatic
    @JvmOverloads
    fun of(record: CrashRecord, maxChars: Int = MAX_CHARS): CrashTelemetryPayload {
        return TelemetryCompact.build(record, maxChars)
    }

    @JvmStatic
    @JvmOverloads
    fun compact(record: CrashRecord, maxChars: Int = MAX_CHARS): String {
        return of(record, maxChars).wireText
    }
}
