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
            json.put("crash_time", formatBeijing(System.currentTimeMillis()))
            json.put("local_time", formatLocal(System.currentTimeMillis()))
            json.put("crash_type", type.wireName())
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
