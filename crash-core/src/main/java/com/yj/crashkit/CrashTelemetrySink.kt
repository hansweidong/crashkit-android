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
