package com.yj.crashkit.internal

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import android.os.Process
import android.text.TextUtils
import com.yj.crashkit.CrashCallback
import com.yj.crashkit.CrashKit
import com.yj.crashkit.CrashKitConfig
import com.yj.crashkit.reporter.CrashReporter
import com.yj.crashkit.reporter.NoOpCrashReporter
import com.yj.crashkit.util.KitLog
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class CrashKitRuntime private constructor(app: Context, config: CrashKitConfig) {
    val appContext: Context = app
    val appId: String = config.appId
    val guid: String = config.guid
    val appMarket: String = config.appMarket
    val packageName: String = app.packageName
    val launchTimeMs: Long = System.currentTimeMillis()
    val appVersion: String = if (!TextUtils.isEmpty(config.appVersion)) {
        config.appVersion!!
    } else {
        readVersion(app)
    }
    val dumpDir: File
    val gate: ReportGate = ReportGate()

    private val reporter = AtomicReference<CrashReporter>(NoOpCrashReporter)
    private val callback = AtomicReference<CrashCallback?>(null)
    private val extInfo = ConcurrentHashMap<String, String>()
    private val userLogs = CopyOnWriteArrayList<String>()
    private val uid = AtomicLong(0)
    private val catchNative = AtomicBoolean(false)
    private val fdMonitor = AtomicBoolean(false)
    private val memMonitor = AtomicBoolean(false)
    private val threadMonitor = AtomicBoolean(false)
    private val currentCrashId = AtomicReference("")
    @Volatile private var crashThreadIdValue: Int = 0

    init {
        var dir = config.dumpDir
        if (dir == null) {
            dir = File(app.cacheDir, "crash")
        }
        if (!dir.exists() && !dir.mkdirs()) {
            KitLog.e("CrashKitRuntime", "mkdir dumpDir failed: $dir")
        }
        dumpDir = dir
        PendingStore.prune(dir)
    }

    fun setReporter(r: CrashReporter?) {
        if (r != null) {
            reporter.set(r)
        }
    }

    fun getReporter(): CrashReporter = reporter.get()

    fun setCrashCallback(cb: CrashCallback?) {
        callback.set(cb)
    }

    fun getCrashCallback(): CrashCallback? = callback.get()

    fun setUid(value: Long) {
        uid.set(value)
    }

    fun getUid(): Long = uid.get()

    fun setCatchNative(value: Boolean) {
        catchNative.set(value)
    }

    fun isCatchNative(): Boolean = catchNative.get()

    fun setFdMonitor(value: Boolean) {
        fdMonitor.set(value)
    }

    fun isFdMonitor(): Boolean = fdMonitor.get()

    fun setMemMonitor(value: Boolean) {
        memMonitor.set(value)
    }

    fun isMemMonitor(): Boolean = memMonitor.get()

    fun setThreadMonitor(value: Boolean) {
        threadMonitor.set(value)
    }

    fun isThreadMonitor(): Boolean = threadMonitor.get()

    fun setReportEnabled(enabled: Boolean) {
        gate.setEnabled(enabled)
    }

    fun setCurrentCrashId(id: String?) {
        currentCrashId.set(id ?: "")
    }

    fun getCurrentCrashId(): String = currentCrashId.get()

    fun setCrashThreadId(tid: Int) {
        crashThreadIdValue = tid
    }

    fun getCrashThreadId(): Int = crashThreadIdValue

    fun setExtInfo(map: Map<String, String>?) {
        extInfo.clear()
        addExtInfo(map)
    }

    fun addExtInfo(map: Map<String, String>?) {
        if (map == null) {
            return
        }
        for ((key, value) in map) {
            if (TextUtils.isEmpty(key)) {
                continue
            }
            extInfo[key] = value
        }
    }

    fun snapshotExt(): Map<String, String> = Collections.unmodifiableMap(extInfo)

    fun setUserLogList(paths: List<String>?) {
        userLogs.clear()
        addUserLogList(paths)
    }

    fun setUserLogList(vararg paths: String) {
        userLogs.clear()
        addUserLogList(paths.toList())
    }

    fun addUserLogList(paths: List<String>?) {
        if (paths == null) {
            return
        }
        for (p in paths) {
            if (!TextUtils.isEmpty(p) && !userLogs.contains(p)) {
                userLogs.add(p)
            }
        }
        trimUserLogs(8)
    }

    fun snapshotUserLogs(): List<String> = ArrayList(userLogs)

    fun sdkVersion(): String = CrashKit.VERSION

    companion object {
        @Volatile
        private var instance: CrashKitRuntime? = null

        @JvmStatic
        fun create(app: Context, config: CrashKitConfig): CrashKitRuntime {
            val rt = CrashKitRuntime(app, config)
            instance = rt
            return rt
        }

        @JvmStatic
        fun get(): CrashKitRuntime? = instance

        @JvmStatic
        fun myTid(): Int = Process.myTid()

        private fun readVersion(context: Context): String {
            return try {
                val info: PackageInfo = context.packageManager
                    .getPackageInfo(context.packageName, 0)
                info.versionName ?: Build.VERSION.SDK_INT.toString()
            } catch (_: Throwable) {
                "unknown"
            }
        }
    }

    private fun trimUserLogs(max: Int) {
        while (userLogs.size > max) {
            userLogs.removeAt(0)
        }
    }
}
