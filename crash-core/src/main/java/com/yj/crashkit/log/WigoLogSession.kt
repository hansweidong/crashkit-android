package com.yj.crashkit.log

/**
 * Wigo `LogModel` / `LogEventArgs` 外层信封。每次上报时由宿主提供，CrashKit 不读业务账号体系。
 *
 * 用户等级、设备 id、lanId 只有宿主知道；没填的字段用 CrashKit 已有的 pkg / ver / guid 兜底。
 */
class WigoLogSession(
    val pkg: String? = null,
    val ver: String? = null,
    val deviceId: String? = null,
    val androidId: String? = null,
    val userId: String? = null,
    val lanId: String? = null,
    val secId: Int = 0,
    val userLevel: Int = 0,
    val userLiveLevel: Int = 0,
    val vipLevel: Int? = null,
    val svipLevel: Int? = null,
    val isInBg: Boolean? = null,
    val sysLan: String? = null,
    val country: String? = null,
    val model: String? = null,
    val memoryTotal: String? = null,
    val memoryAllocate: String? = null,
    val memoryUsage: String? = null,
    /** 附加到 HTTP 请求的头，例如 token。 */
    val headers: Map<String, String> = emptyMap(),
    /**
     * true：POST `{"list":[event]}`，对齐 `LogModel.submitCrash` → `submitLogV3`。
     * false：POST `[event]`，对齐明文 `/log/live-chat`（「把 list 里头那个数组传过去」）。
     */
    val bodyAsListWrapper: Boolean = true,
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 5_000,
)
