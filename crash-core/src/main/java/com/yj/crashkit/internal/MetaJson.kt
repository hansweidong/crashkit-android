package com.yj.crashkit.internal

import android.os.Build
import android.os.Process
import android.text.TextUtils
import com.yj.crashkit.CrashKit
import com.yj.crashkit.CrashType
import com.yj.crashkit.history.ActivityTracker
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MetaJson {
    private const val EXT_MAX_KEYS = 12
    private const val EXT_VALUE_MAX = 128
    private const val EXT_TOTAL_MAX = 400
    @JvmStatic
    fun build(
        rt: CrashKitRuntime,
        type: CrashType,
        crashId: String,
        description: String?,
        threadId: Int,
    ): String {
        val json = JSONObject()
        try {
            json.put("report_id", crashId)
            json.put("sdk_ver", CrashKit.VERSION)
            json.put("launch_time", formatBeijing(rt.launchTimeMs))
            val now = System.currentTimeMillis()
            val inBg = !ActivityTracker.get().isForeground
            val mem = MemSnapshot.capture(type == CrashType.JAVA_OOM, now, inBg)
            json.put("crash_time", formatBeijing(now))
            json.put("crash_time_ms", mem.crashTimeMs.toString())
            json.put("launch_time_ms", rt.launchTimeMs.toString())
            json.put("local_time", formatLocal(now))
            json.put("crash_type", type.wireName())
            json.put("is_in_bg", if (inBg) "1" else "0")
            putMem(json, mem)
            json.put("pkg", rt.packageName)
            json.put("app_ver", rt.appVersion)
            json.put("app_id", rt.appId)
            json.put("app_market", rt.appMarket)
            json.put("os_ver", Build.VERSION.RELEASE)
            json.put("sdk_int", Build.VERSION.SDK_INT)
            json.put("process", processName(rt))
            json.put("thread_id", if (threadId > 0) threadId else Process.myTid())
            json.put("guid", rt.guid)
            json.put("uid", rt.getUid().toString())
            json.put("model", Build.MANUFACTURER + " " + Build.MODEL)
            json.put("history", ActivityTracker.get().getHistory())
            json.put("ext", extJson(rt.snapshotExt()))
            var desc = description ?: ""
            if (desc.length > 512) {
                desc = desc.substring(0, 510)
            }
            json.put("exception", desc)
            json.put("catch_native", rt.isCatchNative())
        } catch (_: Throwable) {
        }
        return json.toString()
    }

    private fun putMem(json: JSONObject, mem: MemSnapshot.Snapshot) {
        putNonBlank(json, "mem_total_mb", mem.totalMb)
        putNonBlank(json, "mem_java_used_mb", mem.javaUsedMb)
        putNonBlank(json, "mem_java_alloc_mb", mem.javaAllocMb)
        putNonBlank(json, "mem_java_max_mb", mem.javaMaxMb)
        putNonBlank(json, "heap_pct", mem.heapPct)
        putNonBlank(json, "mem_pss_mb", mem.pssMb)
        putNonBlank(json, "mem_pss_kb", mem.pssKb)
        putNonBlank(json, "mem_native_kb", mem.nativeHeapKb)
        putNonBlank(json, "vm_rss_kb", mem.vmRssKb)
        putNonBlank(json, "vm_size_kb", mem.vmSizeKb)
        putNonBlank(json, "fd_count", mem.fd)
        putNonBlank(json, "thread_count", mem.threads)
    }

    private fun putNonBlank(json: JSONObject, key: String, value: String) {
        if (value.isNotEmpty()) {
            json.put(key, value)
        }
    }

    private fun extJson(ext: Map<String, String>): JSONObject {
        val o = JSONObject()
        try {
            var n = 0
            var used = 0
            for ((key, value) in ext) {
                if (TextUtils.isEmpty(key) || n >= EXT_MAX_KEYS) {
                    continue
                }
                val clipped = if (value.length > EXT_VALUE_MAX) {
                    value.substring(0, EXT_VALUE_MAX)
                } else {
                    value
                }
                if (used + key.length + clipped.length > EXT_TOTAL_MAX) {
                    break
                }
                o.put(key, clipped)
                used += key.length + clipped.length
                n++
            }
        } catch (_: Throwable) {
        }
        return o
    }

    private fun processName(rt: CrashKitRuntime): String {
        val name = rt.processName
        return if (name.isNotEmpty()) name else rt.packageName
    }

    private fun formatBeijing(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT+8")
        return fmt.format(Date(ms))
    }

    private fun formatLocal(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        return fmt.format(Date(ms))
    }
}
