package com.yj.crashkit

/**
 * 一次崩溃 / ANR / OOM 交给宿主的精简结果。
 *
 * ## 宿主怎么拿到
 * SDK **不会**自己 HTTP 上报。采集并落盘后，在崩溃线程（或 ANR 检测线程）上
 * **同步回调一次** [CrashTelemetrySink.onTelemetry]。
 *
 * 注册方式（埋点推荐，三选一即可）：
 * 1. `CrashKit.init { setTelemetrySink { payload, record -> ... } }`
 * 2. `CrashKit.setTelemetrySink { ... }`
 * 3. `setReporter(TelemetryCrashReporter { payload, record -> ... })`
 *
 * [wireText] 已压到 [CrashTelemetry.MAX_CHARS]（9000 字符），直接写入埋点扩展字段。
 * dump / logcat / traces 全文在 [CrashRecord.dumpFiles] 与 [CrashRecord.logFiles]，不要拼进埋点。
 */
data class CrashTelemetryPayload(
    val crashId: String,
    val type: CrashType,
    val sdkVersion: String,
    val appVersion: String,
    val osVersion: String,
    val model: String,
    val process: String,
    val threadId: String,
    val uid: String,
    /** 异常头或 ANR shortMsg，已截断。 */
    val exception: String,
    /**
     * 压缩栈。Java/Native 为去框架帧后的栈；
     * ANR 为 `traces:`（SIGQUIT traces.txt，已去掉 maps）+ `main:`（最后一次主线程采样）。
     */
    val stack: String,
    /** 最近若干 Activity 生命周期，已截断。 */
    val activityHistory: String,
    /** setExtInfo 压缩结果，`k=v;k=v`。 */
    val ext: String,
    /** OOM 计数快照；其它类型通常为空。 */
    val resource: String,
    /** 栈或整包被 9000 上限截过。 */
    val truncated: Boolean,
    /** 埋点扩展字段用的短键 JSON，长度 ≤ [CrashTelemetry.MAX_CHARS]。 */
    val wireText: String,
)
