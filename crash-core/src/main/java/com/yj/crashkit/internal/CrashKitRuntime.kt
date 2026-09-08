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
    @Volatile
    var guid: String = config.guid
        private set
    val appMarket: String = config.appMarket
    val packageName: String = app.packageName
    val launchTimeMs: Long = System.currentTimeMillis()
    @Volatile
    var appVersion: String = if (!TextUtils.isEmpty(config.appVersion)) {
        config.appVersion!!
    } else {
        readVersion(app)
    }
        private set
    val dumpDir: File
    val gate: ReportGate = ReportGate()

    private val reporter = AtomicReference<CrashReporter>(NoOpCrashReporter)
    private val readyLock = Any()
    private val reporterReady = ArrayList<() -> Unit>()
    private val callback = AtomicReference<CrashCallback?>(null)
    private val extInfo = ConcurrentHashMap<String, String>()
    private val dynamicExtProvider = AtomicReference<(() -> Map<String, String>?)?>(null)
    private val userLogs = CopyOnWriteArrayList<String>()
    private val uid = AtomicLong(0)
    private val catchNative = AtomicBoolean(false)
    private val fdMonitor = AtomicBoolean(false)
    private val memMonitor = AtomicBoolean(false)
    private val threadMonitor = AtomicBoolean(false)
    private val currentCrashId = AtomicReference("")
    @Volatile private var crashThreadIdValue: Int = 0

    val processName: String = ProcessName.current(app)

    init {
        // 多进程分目录：固定名的 native_crash.dmp / anr_error.log / main_stack.txt 不再互相覆盖。
        // 主进程留在原目录，升级后旧的 pending/ 还读得到。
        val base = config.dumpDir ?: File(app.cacheDir, "crash")
        val segment = ProcessName.dirSegment(app, app.packageName)
        val dir = if (segment.isEmpty()) base else File(base, segment)
        if (!dir.exists() && !dir.mkdirs()) {
            KitLog.e("CrashKitRuntime", "mkdir dumpDir failed: $dir")
        }
        dumpDir = dir
        PendingStore.prune(dir)
        MemSnapshot.deviceTotalMb()
    }

    fun setReporter(r: CrashReporter?) {
        if (r == null) {
            return
        }
        // 先置 reporter 再取锁，[whenReporterReady] 里是先取锁再判 —— 两边这个顺序保证
        // 不会有「刚判完没装、随后被 drain 漏掉」的注册丢失。
        reporter.set(r)
        KitLog.i("CrashKitRuntime", "reporter=${r.javaClass.simpleName}")
        if (r === NoOpCrashReporter) {
            return
        }
        val pending: List<() -> Unit>
        synchronized(readyLock) {
            if (reporterReady.isEmpty()) {
                return
            }
            pending = ArrayList(reporterReady)
            reporterReady.clear()
        }
        for (action in pending) {
            try {
                action()
            } catch (t: Throwable) {
                KitLog.e("CrashKitRuntime", "reporterReady", t)
            }
        }
    }

    fun getReporter(): CrashReporter = reporter.get()

    fun hasRealReporter(): Boolean = reporter.get() !== NoOpCrashReporter

    /**
     * 挂起到宿主真正注册 reporter / telemetrySink 之后再跑。可注册多个。
     *
     * 宿主常在 [CrashKit.init] 之后才调 `setTelemetrySink`，此时 reporter 还是
     * [NoOpCrashReporter]。历史 ANR 补报、pending 重投要是在那之前就走完管线，
     * 数据会静默丢掉。
     */
    fun whenReporterReady(action: () -> Unit) {
        synchronized(readyLock) {
            if (!hasRealReporter()) {
                reporterReady.add(action)
                return
            }
        }
        action()
    }

    fun setCrashCallback(cb: CrashCallback?) {
        callback.set(cb)
    }

    fun getCrashCallback(): CrashCallback? = callback.get()

    fun setUid(value: Long) {
        uid.set(value)
    }

    fun setAppVersion(version: String?) {
        if (!TextUtils.isEmpty(version)) {
            appVersion = version!!
        }
    }

    fun setGUid(value: String?) {
        if (value != null) {
            guid = value
        }
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

    fun setExtInfo(map: Map<String?, String?>?) {
        extInfo.clear()
        addExtInfo(map)
    }

    fun addExtInfo(map: Map<String?, String?>?) {
        if (map == null) {
            return
        }
        for ((key, value) in map) {
            if (TextUtils.isEmpty(key)) {
                continue
            }
            extInfo[key!!] = value ?: ""
        }
    }

    fun snapshotExt(): Map<String, String> {
        val merged = HashMap(extInfo)
        try {
            val provider = dynamicExtProvider.get()
            val dynamic = provider?.invoke()
            if (dynamic != null) {
                for ((key, value) in dynamic) {
                    if (!TextUtils.isEmpty(key)) {
                        merged[key] = value
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return Collections.unmodifiableMap(merged)
    }

    fun setDynamicExtInfoProvider(provider: (() -> Map<String, String>?)?) {
        dynamicExtProvider.set(provider)
    }

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
