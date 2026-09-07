package com.yj.crashkit

/**
 * 埋点宿主接收精简崩溃信息的入口。
 *
 * SDK 在 `preCallback → 落盘 → crashCallback` 之后、进程可能被系统杀掉之前，
 * 在**当前采集线程**调用一次 [onTelemetry]。不会再为 DUMP / LOGS 回调本接口。
 *
 * 宿主应尽快把 [CrashTelemetryPayload.wireText] 交给埋点 SDK 并返回；
 * 不要在这里读大文件或做网络（除非埋点 SDK 自身是异步的）。
 */
fun interface CrashTelemetrySink {
    fun onTelemetry(payload: CrashTelemetryPayload, record: CrashRecord)
}

/**
 * 带投递结果的 [CrashTelemetrySink]。**需要「没送到就下次启动重投」的宿主必须实现这个。**
 *
 * 只实现 [CrashTelemetrySink] 时 [onTelemetry] 没有返回值，SDK 无从判断埋点是否真的收下，
 * 只能一律按成功处理并删掉 `pending/{id}.json` —— 重投兜底等于不存在。实现本接口后返回
 * `false` 即保留记录，下次冷启动 reporter 就位时自动重投。
 *
 * 返回 `true` 的含义是「**已确认送达或已落到你自己的持久化队列**」，不是「已入队内存」。
 * 在内存入队时就返回 `true`，进程随后被杀同样会丢，而且 pending 已经被删掉了。
 */
fun interface CrashTelemetryAckSink : CrashTelemetrySink {
    fun onTelemetryAck(payload: CrashTelemetryPayload, record: CrashRecord): Boolean

    override fun onTelemetry(payload: CrashTelemetryPayload, record: CrashRecord) {
        onTelemetryAck(payload, record)
    }
}
