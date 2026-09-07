package com.yj.crashkit.log

import android.os.Build
import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetryPayload
import com.yj.crashkit.CrashType
import com.yj.crashkit.history.ActivityTracker
import com.yj.crashkit.internal.CrashKitRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * 把 CrashKit 采集结果编成 Wigo 日志 V3 JSON，**字段集合对齐** `LogModel.buildCrashEvent`，
 * **事件名与旧 UEH 上报刻意分开**，避免和 `behavior=client_crash` / `subtype=diagnostic` 混在一张表里。
 *
 * 旧：`log_type=technical_log` + `subtype=diagnostic` + `behavior=client_crash`
 * 新：`log_type=technical_log` + `subtype=crashkit` + `behavior=crashkit_crash|crashkit_anr`
 * data 额外带 `sdk=crashkit`、`sdk_ver`、`crash_type`、`crash_id`。
 */
object WigoLogEvent {
    const val LOG_TYPE = "technical_log"
    const val SUBTYPE = "crashkit"
    const val BEHAVIOR_CRASH = "crashkit_crash"
    const val BEHAVIOR_ANR = "crashkit_anr"
    const val SDK = "crashkit"
    const val CLIENT_TYPE = "crashkit"
    const val P_VER = "1.3"
    private const val STACK_MAX = 24 * 1024
    private const val MSG_MAX = 180
    private val secSeq = AtomicInteger(0)

    fun requestBody(payload: CrashTelemetryPayload, record: CrashRecord, session: WigoLogSession): String {
        val event = logEvent(payload, record, session)
        return if (session.bodyAsListWrapper) {
            JSONObject().put("list", JSONArray().put(event)).toString()
        } else {
            JSONArray().put(event).toString()
        }
    }

    fun logEvent(payload: CrashTelemetryPayload, record: CrashRecord, session: WigoLogSession): JSONObject {
        val rt = CrashKitRuntime.get()
        val pkg = firstNonBlank(session.pkg, payload.process, rt?.packageName.orEmpty())
        val ver = firstNonBlank(session.ver, payload.appVersion, rt?.appVersion.orEmpty())
        val deviceId = firstNonBlank(session.deviceId, session.androidId, rt?.guid.orEmpty(), payload.uid)
        val lanId = firstNonBlank(session.lanId, rt?.guid.orEmpty(), payload.crashId)
        val secId = if (session.secId > 0) session.secId else secSeq.incrementAndGet()
        val tm = System.currentTimeMillis()
        val inBg = session.isInBg ?: !ActivityTracker.get().isForeground
        val anr = payload.type == CrashType.ANR_CRASH
        val msgs = extData(payload)
        val heap = Runtime.getRuntime()
        val usedMb = ((heap.totalMemory() - heap.freeMemory()) / (1024L * 1024L)).toString()
        val allocMb = (heap.totalMemory() / (1024L * 1024L)).toString()
        val maxMb = (heap.maxMemory() / (1024L * 1024L)).toString()

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
            .put("memory_total", session.memoryTotal ?: maxMb)
            .put("memory_allocate", session.memoryAllocate ?: allocMb)
            .put("memory_usage", session.memoryUsage ?: usedMb)
            .put("ext_data1", payload.exception)
            .put("is_in_bg", inBg)
            .put("tm", tm)
        putNullable(crash, "vip_level", session.vipLevel)
        putNullable(crash, "svip_level", session.svipLevel)

        val envelope = JSONObject()
            .put("data", JSONArray().put(crash))
            .put("log_type", LOG_TYPE)
            .put("subtype", SUBTYPE)
            .put("behavior", if (anr) BEHAVIOR_ANR else BEHAVIOR_CRASH)
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
        val uid = session.userId
        if (!uid.isNullOrEmpty()) {
            envelope.put("user_id", uid)
        }
        return envelope
    }

    internal fun extData(payload: CrashTelemetryPayload): Array<String?> {
        val exception = payload.exception.trim()
        if (payload.type == CrashType.ANR_CRASH) {
            return arrayOf(
                clip(payload.type.wireName(), MSG_MAX),
                clip(exception.lineSequence().firstOrNull().orEmpty(), MSG_MAX),
                clip(payload.resource, MSG_MAX).ifEmpty { null },
                null,
                firstAtFrame(exception) ?: firstAtFrame(payload.stack),
            )
        }
        val (cls, msg) = splitClassAndMessage(exception)
        val cause = causeOf(payload.stack)
        return arrayOf(
            clip(cls ?: payload.type.wireName(), MSG_MAX),
            clip(msg.orEmpty(), MSG_MAX).ifEmpty { null },
            cause?.first,
            cause?.second,
            firstAtFrame(payload.stack) ?: firstAtFrame(exception),
        )
    }

    internal fun splitClassAndMessage(exception: String): Pair<String?, String?> {
        val line = exception.lineSequence().firstOrNull()?.trim().orEmpty()
        if (line.isEmpty() || line.startsWith("----- ")) {
            return null to line.ifEmpty { null }
        }
        val idx = line.indexOf(": ")
        return if (idx <= 0) {
            line to null
        } else {
            line.substring(0, idx) to line.substring(idx + 2)
        }
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
        return if (sb.length <= STACK_MAX) sb.toString() else sb.substring(0, STACK_MAX)
    }

    private fun causeOf(stack: String): Pair<String, String?>? {
        for (line in stack.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("Caused by:")) {
                continue
            }
            val body = t.removePrefix("Caused by:").trim()
            val (cls, msg) = splitClassAndMessage(body)
            if (cls.isNullOrEmpty()) {
                return null
            }
            return cls to msg
        }
        return null
    }

    private fun firstAtFrame(text: String): String? {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.startsWith("at ")) {
                return clip(t, MSG_MAX)
            }
        }
        return null
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

    private fun clip(text: String, max: Int): String {
        if (text.length <= max) {
            return text
        }
        return text.substring(0, max)
    }
}
