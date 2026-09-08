package com.yj.crashkit.log

/**
 * 上报信封。每次上报时由宿主提供，CrashKit 不读业务账号体系。
 *
 * [logType] / [subtype] / [crashBehavior] / [anrBehavior] 可由宿主覆盖；未传时用 [Default]。
 * 用户等级、设备 id、lanId 只有宿主知道；没填的字段用 CrashKit 已有的 pkg / ver / guid 兜底。
 */
class CrashKitLogSession(
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
    /** 信封 `log_type`，未传时用 [Default]。 */
    val logType: String,
    /** 信封 `subtype`，未传时用 [Default]。 */
    val subtype: String,
    /** 非 ANR 时的信封 `behavior`，未传时用 [Default]。 */
    val crashBehavior: String,
    /** ANR 时的信封 `behavior`，未传时用 [Default]。 */
    val anrBehavior: String,
    /** 附加头，仅当宿主自己的 [CrashKitLogTransport] 需要时使用。CrashKit 不发 HTTP。 */
    val headers: Map<String, String> = emptyMap(),
    /**
     * true：组 `{"list":[event]}`，对齐 `LogModel.submitCrash` → `submitLogV3`。
     * false：组 `[event]`，对齐明文 `/log/live-chat` 数组体。
     */
    val bodyAsListWrapper: Boolean = true,
    val connectTimeoutMs: Int = 3_000,
    val readTimeoutMs: Int = 5_000,
) {
    companion object {
        private const val defaultLogType = "crashKit"
        private const val defaultSubtype = "crashKit"
        private const val defaultCrashBehavior = "crash"
        private const val defaultAnrBehavior = "anr"

        /** 本地默认信封。宿主未覆盖分类字段时使用。 */
        @JvmField
        val Default = CrashKitLogSession(
            logType = defaultLogType,
            subtype = defaultSubtype,
            crashBehavior = defaultCrashBehavior,
            anrBehavior = defaultAnrBehavior,
        )
    }
}
