package com.yj.crashkit.log

import android.os.Build
import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetryPayload
import com.yj.crashkit.CrashType
import com.yj.crashkit.history.ActivityTracker
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.MemSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 CrashKit 采集结果编成日志 JSON。信封分类字段（`log_type` / `subtype` / `behavior`）
 * 优先使用 [CrashKitLogSession] 里宿主传入的值；未传时用 [CrashKitLogSession.Default]。
 *
 * data 带 `sdk`、`sdk_ver`、`crash_type`、`crash_id`。
 */
object CrashKitLogEvent {
    const val SDK = "crashkit"
    const val CLIENT_TYPE = "crashkit"
    const val P_VER = "1.3"
    private const val STACK_MAX = 24 * 1024
    private val secSeq = AtomicInteger(0)

    fun requestBody(payload: CrashTelemetryPayload, record: CrashRecord, session: CrashKitLogSession): String {
        val event = logEvent(payload, record, session)
        return if (session.bodyAsListWrapper) {
            JSONObject().put("list", JSONArray().put(event)).toString()
        } else {
            JSONArray().put(event).toString()
        }
    }

    fun logEvent(payload: CrashTelemetryPayload, record: CrashRecord, session: CrashKitLogSession): JSONObject {
        val rt = CrashKitRuntime.get()
        val pkg = firstNonBlank(session.pkg, payload.process, rt?.packageName.orEmpty())
        val ver = firstNonBlank(session.ver, payload.appVersion, rt?.appVersion.orEmpty())
        val deviceId = firstNonBlank(session.deviceId, session.androidId, rt?.guid.orEmpty(), payload.uid)
        val lanId = firstNonBlank(session.lanId, rt?.guid.orEmpty(), payload.crashId)
        val secId = if (session.secId > 0) session.secId else secSeq.incrementAndGet()
        val tm = if (payload.crashTimeMs > 0L) payload.crashTimeMs else System.currentTimeMillis()
        val inBg = payload.isInBg ?: session.isInBg ?: !ActivityTracker.get().isForeground
        val anr = payload.type == CrashType.ANR_CRASH
        val memTotal = firstNonBlank(payload.memoryTotal, session.memoryTotal)
        val memAlloc = firstNonBlank(payload.memoryAllocate, session.memoryAllocate)
        val memUsage = firstNonBlank(payload.memoryUsage, session.memoryUsage)

        val crash = JSONObject()
            .put("sdk", SDK)
            .put("sdk_ver", payload.sdkVersion)
            .put("crash_id", payload.crashId)
            .put("crash_type", payload.type.wireName())
            .put("lan_id", lanId)
            .put("sec_id", secId)
            .put("stack_trace", stackTrace(payload, record))
            .put("user_level", session.userLevel)
            .put("user_live_level", session.userLiveLevel)
            .put("memory_total", memTotal)
            .put("memory_allocate", memAlloc)
            .put("memory_usage", memUsage)
            .put("exception", payload.exception)
            .put("is_in_bg", inBg)
            .put("tm", tm)
        putNullable(crash, "vip_level", session.vipLevel)
        putNullable(crash, "svip_level", session.svipLevel)
        putNonBlank(crash, "heap_used", payload.heapUsedMb)
        putNonBlank(crash, "heap_max", payload.heapMaxMb)
        putNonBlank(crash, "heap_pct", payload.heapPct)
        putNonBlank(crash, "pss_mb", payload.pssMb)
        putNonBlank(crash, "pss_kb", payload.pssKb)
        putNonBlank(crash, "native_heap_kb", payload.nativeHeapKb)
        putNonBlank(crash, "vm_rss_kb", payload.vmRssKb)
        putNonBlank(crash, "vm_size_kb", payload.vmSizeKb)
        putNonBlank(crash, "fd", payload.fdCount)
        putNonBlank(crash, "threads", payload.threadCount)
        putNonBlank(crash, "activity", payload.activityHistory)
        putNonBlank(crash, "thread_id", payload.threadId)
        putNonBlank(crash, "proc", payload.processName)
        putNonBlank(crash, "res", payload.resource)
        putNonBlank(crash, "ext", payload.ext)
        if (payload.truncated) {
            crash.put("truncated", true)
        }

        val envelope = JSONObject()
            .put("data", JSONArray().put(crash))
            .put("android_id", firstNonBlank(session.androidId, deviceId))
            .put("device_id", deviceId)
            .put("pkg", pkg)
            .put("ver", ver)
            .put("platform", "android")
            .put("platform_ver", Build.VERSION.SDK_INT)
            .put("app_platform", "app")
            .put("app_platform_ver", ver)
            .put("model", firstNonBlank(session.model, payload.model, Build.MANUFACTURER + " " + Build.MODEL))
            .put("lan_id", lanId)
            .put("sec_id", secId)
            .put("sys_lan", session.sysLan ?: Locale.getDefault().toString())
            .put("country", session.country ?: Locale.getDefault().country)
            .put("is_in_bg", inBg)
            .put("is_anchor", false)
            .put("client_type", CLIENT_TYPE)
            .put("bizver", SDK)
            .put("p_ver", P_VER)
            .put("tm", tm)
        putNonBlank(envelope, "log_type", session.logType)
        putNonBlank(envelope, "subtype", session.subtype)
        putNonBlank(envelope, "behavior", if (anr) session.anrBehavior else session.crashBehavior)
        val uid = session.userId
        if (!uid.isNullOrEmpty()) {
            envelope.put("user_id", uid)
        }
        return envelope
    }

    internal fun httpSucceeded(code: Int, body: String): Boolean {
        if (code !in 200..299) {
            return false
        }
        val text = body.trim()
        if (text.isEmpty()) {
            return true
        }
        return try {
            val json = JSONObject(text)
            when {
                json.has("success") -> json.optBoolean("success")
                json.has("code") -> json.optInt("code") == 0
                json.has("fail") -> !json.optBoolean("fail")
                else -> true
            }
        } catch (_: Throwable) {
            true
        }
    }

    private fun stackTrace(payload: CrashTelemetryPayload, record: CrashRecord): String {
        val body = stackBody(payload, record)
        val head = MemSnapshot.headerLine(
            MemSnapshot.Snapshot(
                crashTimeMs = payload.crashTimeMs,
                totalMb = payload.memoryTotal,
                javaUsedMb = payload.heapUsedMb,
                javaAllocMb = payload.memoryAllocate,
                javaMaxMb = payload.heapMaxMb,
                heapPct = payload.heapPct,
                pssMb = payload.pssMb,
                pssKb = payload.pssKb,
                nativeHeapKb = payload.nativeHeapKb,
                vmRssKb = payload.vmRssKb,
                vmSizeKb = payload.vmSizeKb,
                fd = payload.fdCount,
                threads = payload.threadCount,
                inBg = payload.isInBg,
            ),
        )
        val merged = when {
            head.isEmpty() -> body
            body.isEmpty() -> head
            body.startsWith("mem:") -> body
            else -> "$head\n$body"
        }
        return if (merged.length <= STACK_MAX) merged else merged.substring(0, STACK_MAX)
    }

    private fun stackBody(payload: CrashTelemetryPayload, record: CrashRecord): String {
        if (payload.type != CrashType.JAVA_OOM) {
            for (f in record.dumpFiles) {
                val text = readBounded(f, STACK_MAX)
                if (text.isNotEmpty()) {
                    return text
                }
            }
        }
        val sb = StringBuilder()
        if (payload.exception.isNotEmpty()) {
            sb.append(payload.exception)
        }
        if (payload.stack.isNotEmpty()) {
            if (sb.isNotEmpty()) {
                sb.append('\n')
            }
            sb.append(payload.stack)
        }
        return sb.toString()
    }

    private fun readBounded(file: java.io.File?, max: Int): String {
        if (file == null || !file.exists() || !file.isFile) {
            return ""
        }
        return try {
            val len = file.length().coerceAtMost(max.toLong()).toInt()
            val bytes = ByteArray(len)
            file.inputStream().use { input ->
                var off = 0
                while (off < len) {
                    val n = input.read(bytes, off, len - off)
                    if (n < 0) {
                        break
                    }
                    off += n
                }
            }
            String(bytes, Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
    }

    private fun putNonBlank(json: JSONObject, key: String, value: String?) {
        if (!value.isNullOrEmpty()) {
            json.put(key, value)
        }
    }

    private fun putNullable(json: JSONObject, key: String, value: Int?) {
        if (value != null) {
            json.put(key, value)
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (v in values) {
            if (!v.isNullOrEmpty()) {
                return v
            }
        }
        return ""
    }
}
