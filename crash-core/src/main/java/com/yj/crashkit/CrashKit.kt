package com.yj.crashkit

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yj.crashkit.anr.AnrDetector
import com.yj.crashkit.anr.AnrListener
import com.yj.crashkit.anr.ExitInfoAnrCollector
import com.yj.crashkit.anr.MainThreadSampler
import com.yj.crashkit.history.ActivityTracker
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.internal.JavaCrashHandler
import com.yj.crashkit.internal.OomLite
import com.yj.crashkit.nativecrash.NativeCrashBridge
import com.yj.crashkit.oom.JavaOomMonitor
import com.yj.crashkit.reporter.CrashReporter
import com.yj.crashkit.reporter.NoOpCrashReporter
import com.yj.crashkit.reporter.TelemetryCrashReporter
import com.yj.crashkit.resource.ResourceMonitor
import com.yj.crashkit.util.KitLog

/**
 * 采集入口。默认不含 HTTP。埋点宿主注册 [CrashTelemetrySink]；
 * CrashKit 日志协议 JSON 由 [setCrashKitLogUpload] 组好后交给宿主网络栈发送。
 *
 * 线上请只调本对象。ANR 对齐 Matrix SignalAnrTracer：SIGQUIT 旁路 + 队头/AM 确认后只上报一次，**不杀进程**。
 * 加重诊断（主动崩溃、53ms 采样、inline 轮询、hprof）走 [CrashKitLab]。
 */
object CrashKit {
    @JvmField
    val VERSION: String = BuildConfig.LIBRARY_VERSION
    private const val TAG = "CrashKit"

    private val lock = Any()
    @Volatile private var inited = false
    @Volatile private var pipeline: CrashPipeline? = null
    @Volatile private var anrDetector: AnrDetector? = null

    /** 原 `CrashReport.CrashCallback`，Java 可写 `new CrashKit.CrashCallback() {}`。 */
    interface CrashCallback : com.yj.crashkit.CrashCallback

    /** 原 `CrashReport.CrashReportBuilder`。 */
    class CrashReportBuilder : CrashKitConfig.Builder()

    @JvmStatic
    fun init(builder: CrashKitConfig.Builder?): Boolean {
        return init(builder?.build())
    }

    @JvmStatic
    fun init(config: CrashKitConfig?): Boolean {
        val ctx = config?.context ?: return false
        synchronized(lock) {
            if (inited) {
                KitLog.i(TAG, "already init")
                return false
            }
            val app = ctx.applicationContext
            KitLog.setLogger(config.logger)
            OomLite.preallocate()
            val runtime = CrashKitRuntime.create(app, config)
            runtime.setReporter(config.reporter ?: NoOpCrashReporter)
            val p = CrashPipeline(runtime)
            pipeline = p
            JavaCrashHandler.install(p)
            if (app is Application) {
                ActivityTracker.get().install(app)
            }
            scheduleUehRewrap()
            val nativeOk = NativeCrashBridge.install(runtime.dumpDir.absolutePath, p)
            runtime.setCatchNative(nativeOk)
            startAnrDetectorLocked(app)
            // 宿主常在 init 之后才 setTelemetrySink。这两件事都要等真正的 reporter 到位再跑，
            // 否则数据会被 NoOpCrashReporter 吃掉，而游标 / pending 已经被清了。
            runtime.whenReporterReady { ExitInfoAnrCollector.start(app, p) }
            runtime.whenReporterReady { resendPendingAsync(p) }
            KitLog.i(
                TAG,
                "init version=$VERSION native=$nativeOk process=${runtime.processName} dumpDir=${runtime.dumpDir}",
            )
            inited = true
            return true
        }
    }

    @JvmStatic
    fun init(context: Context, configure: CrashKitConfig.Builder.() -> Unit = {}): Boolean {
        return init(
            CrashKitConfig.Builder()
                .setContext(context)
                .apply(configure)
                .build()
        )
    }

    @JvmStatic
    fun setReporter(reporter: CrashReporter?) {
        if (reporter != null) {
            CrashKitRuntime.get()?.setReporter(reporter)
        }
    }

    /**
     * 埋点宿主入口，等价于 [setReporter] 一个 [TelemetryCrashReporter]。
     * 每次崩溃 / ANR / OOM 只回调一次 [CrashTelemetrySink.onTelemetry]。
     */
    @JvmStatic
    fun setTelemetrySink(sink: CrashTelemetrySink?) {
        if (sink != null) {
            setReporter(TelemetryCrashReporter(sink))
        }
    }

    /**
     * 组 JSON 后由 [send] 走宿主网络发出去。CrashKit 不开连接。
     * [session] 每次上报现取设备 id / userId / lanId，以及信封 `log_type` / `subtype` / `behavior`。
     */
    @JvmStatic
    @JvmOverloads
    fun setCrashKitLogUpload(
        send: com.yj.crashkit.log.CrashKitLogTransport,
        session: () -> com.yj.crashkit.log.CrashKitLogSession = { com.yj.crashkit.log.CrashKitLogSession.Default },
    ) {
        setTelemetrySink(com.yj.crashkit.log.CrashKitLogCrashSink(session, send))
    }

    @JvmStatic
    fun setCrashCallback(callback: com.yj.crashkit.CrashCallback?) {
        CrashKitRuntime.get()?.setCrashCallback(callback)
    }

    @JvmStatic
    fun setAnrListener(listener: AnrListener?) {
        val detector = anrDetector
        if (detector != null) {
            detector.setListener(listener)
        } else {
            AnrDetector.setPendingListener(listener)
        }
    }

    /** 原 `CrashReport.setANRListener`。参数用 [AnrListener]，不要再建 ANRDetector 类（与 AnrDetector 文件名冲突）。 */
    @JvmStatic
    fun setANRListener(listener: AnrListener?) {
        setAnrListener(listener)
    }

    @JvmStatic
    fun setUid(uid: Long) {
        CrashKitRuntime.get()?.setUid(uid)
    }

    @JvmStatic
    fun setAppVersion(version: String?) {
        CrashKitRuntime.get()?.setAppVersion(version)
    }

    /** 原 `ReportUtils.setGUid` / init 之后补 hd id。 */
    @JvmStatic
    fun setGUid(guid: String?) {
        CrashKitRuntime.get()?.setGUid(guid)
    }

    /** 原 `CrashReport.configCrashReport`。 */
    @JvmStatic
    fun configCrashReport(enabled: Boolean) {
        setReportEnabled(enabled)
    }

    /**
     * 原 `CrashReport.openSignalReport`。init 之后 native 已经装上；false 不会卸载 handler。
     */
    @JvmStatic
    fun openSignalReport(@Suppress("UNUSED_PARAMETER") open: Boolean) {
        KitLog.i(TAG, "openSignalReport=$open (native handler already installed at init)")
    }

    @JvmStatic
    fun setExtInfo(extInfo: Map<String?, String?>?) {
        CrashKitRuntime.get()?.setExtInfo(extInfo)
    }

    @JvmStatic
    fun addExtInfo(extInfo: Map<String?, String?>?) {
        CrashKitRuntime.get()?.addExtInfo(extInfo)
    }

    @JvmStatic
    fun addExtraInfo(extInfo: Map<String?, String?>?) {
        addExtInfo(extInfo)
    }

    /** 原 `CrashReport.setDynamicExtInfoProvider`：崩溃瞬间再取一遍动态扩展字段。 */
    @JvmStatic
    fun setDynamicExtInfoProvider(provider: (() -> Map<String, String>?)?) {
        CrashKitRuntime.get()?.setDynamicExtInfoProvider(provider)
    }

    @JvmStatic
    fun setUserLogList(paths: List<String>?) {
        CrashKitRuntime.get()?.setUserLogList(paths)
    }

    @JvmStatic
    fun setUserLogList(vararg paths: String) {
        CrashKitRuntime.get()?.setUserLogList(*paths)
    }

    @JvmStatic
    fun addUserLogList(paths: List<String>?) {
        CrashKitRuntime.get()?.addUserLogList(paths)
    }

    /** 只关上报，不卸 UEH、不停采样。 */
    @JvmStatic
    fun setReportEnabled(enabled: Boolean) {
        CrashKitRuntime.get()?.setReportEnabled(enabled)
    }

    /**
     * 可选：周期性采主线程栈，供 ANR 上报附带历史样本。
     *
     * ANR 采集已在 [init] 启动（SIGQUIT 旁路 + MessageQueue/AM 确认，再把信号交回系统）。
     * 传入有限间隔时才会采样（线上不低于 200ms）。53ms 见 [CrashKitLab.startAggressiveAnrSampling]。
     */
    @JvmStatic
    @JvmOverloads
    fun startANRDetecting(context: Context?, sampleIntervalMillis: Long = Long.MAX_VALUE) {
        startAnrDetecting(context, sampleIntervalMillis)
    }

    @JvmStatic
    @JvmOverloads
    fun startAnrDetecting(context: Context?, sampleIntervalMillis: Long = Long.MAX_VALUE) {
        if (context == null || !inited) {
            return
        }
        synchronized(lock) {
            startAnrDetectorLocked(context)
            val requested = if (sampleIntervalMillis < 10) {
                MainThreadSampler.DEFAULT_INTERVAL_MS
            } else {
                sampleIntervalMillis
            }
            val interval = CrashKitOnlinePolicy.sampleInterval(requested, CrashKitLab.isEnabled())
            if (interval != requested) {
                CrashKitLab.logClamp("startAnrDetecting", requested, interval)
            }
            MainThreadSampler.get().start(interval)
            KitLog.i(TAG, "ANR sampling interval=$interval")
        }
    }

    private fun startAnrDetectorLocked(context: Context) {
        if (anrDetector != null) {
            return
        }
        val p = pipeline ?: return
        val detector = AnrDetector(context.applicationContext, p)
        anrDetector = detector
        detector.start()
        KitLog.i(TAG, "ANR detecting started")
    }

    /** 重投要读盘、要走 reporter，别占着 init 所在的主线程。 */
    private fun resendPendingAsync(p: CrashPipeline) {
        val t = Thread({
            try {
                p.resendPending()
            } catch (e: Throwable) {
                KitLog.e(TAG, "resendPending", e)
            }
        }, "CrashKit-Resend")
        t.isDaemon = true
        t.start()
    }

    /**
     * 宿主常在 [init] 之后的同一个 Application.onCreate 里再装自己的 UEH。
     * 投递到当前消息之后，把对方收成内层。
     */
    private fun scheduleUehRewrap() {
        val looper = Looper.getMainLooper()
        if (looper != null) {
            Handler(looper).post { JavaCrashHandler.ensureOuter() }
        } else {
            JavaCrashHandler.ensureOuter()
        }
    }

    @JvmStatic
    fun sampler(): MainThreadSampler = MainThreadSampler.get()

    @JvmStatic
    fun uploadCustomCrash(throwable: Throwable?) {
        uploadCustomCrash(CrashType.JAVA_ERROR, throwable)
    }

    @JvmStatic
    fun uploadCustomCrash(type: CrashType?, throwable: Throwable?) {
        pipeline?.handleCustom(type ?: CrashType.JAVA_ERROR, throwable)
    }

    @JvmStatic
    fun uploadCustomCrash(type: CrashType?, stack: String?, threadId: Int) {
        pipeline?.handleCustom(type ?: CrashType.JAVA_ERROR, stack, threadId)
    }

    @JvmStatic
    fun testNativeCrash() {
        CrashKitLab.testNativeCrash()
    }

    @JvmStatic
    fun testJavaCrash() {
        CrashKitLab.testJavaCrash()
    }

    /**
     * OOM 预检：5s 轮询堆占比 / FD / 线程 / VSS，连续命中且堆仍在上涨才上报计数快照。
     *
     * [dumpHprof] 一律忽略——hprof 采集已整体下线。
     */
    @JvmStatic
    @JvmOverloads
    fun openJavaOom(application: Application?, dumpHprof: Boolean = false) {
        val p = pipeline ?: return
        JavaOomMonitor.open(application, dumpHprof, p)
    }

    @JvmStatic
    fun closeJavaOom() {
        JavaOomMonitor.close()
    }

    @JvmStatic
    fun openFdInfo(type: Int, inline: Boolean, javaStack: Boolean, context: Context?) {
        ResourceMonitor.openFdInfo(type, inline, javaStack, context)
    }

    @JvmStatic
    fun closeFdInfo() {
        ResourceMonitor.closeFdInfo()
    }

    @JvmStatic
    fun openMemInfo(context: Context?, inline: Boolean) {
        ResourceMonitor.openMemInfo(context, inline)
    }

    @JvmStatic
    fun closeMemInfo() {
        ResourceMonitor.closeMemInfo()
    }

    @JvmStatic
    fun openThreadInfo(inline: Boolean) {
        ResourceMonitor.openThreadInfo(inline)
    }

    @JvmStatic
    fun closeThreadInfo() {
        ResourceMonitor.closeThreadInfo()
    }
}

fun crashKitInit(context: Context, configure: CrashKitConfig.Builder.() -> Unit = {}): Boolean {
    return CrashKit.init(context, configure)
}

fun setCrashHooks(
    onPre: (nativeCrash: Boolean, dumpFile: String, symbolFile: String, logFile: String) -> Unit =
        { _, _, _, _ -> },
    onCrash: (
        crashId: String,
        nativeCrash: Boolean,
        dumpFile: String,
        symbolFile: String,
        logFile: String,
    ) -> Unit = { _, _, _, _, _ -> },
    onAfter: (
        crashId: String,
        nativeCrash: Boolean,
        dumpFile: String,
        symbolFile: String,
        logFile: String,
    ) -> Unit = { _, _, _, _, _ -> },
) {
    CrashKit.setCrashCallback(object : CrashCallback {
        override fun preCrashCallback(
            nativeCrash: Boolean,
            dumpFile: String,
            dumpSymbolFile: String,
            logFile: String,
        ) {
            onPre(nativeCrash, dumpFile, dumpSymbolFile, logFile)
        }

        override fun crashCallback(
            crashId: String,
            nativeCrash: Boolean,
            dumpFile: String,
            dumpSymbolFile: String,
            logFile: String,
        ) {
            onCrash(crashId, nativeCrash, dumpFile, dumpSymbolFile, logFile)
        }

        override fun afterCrashCallback(
            crashId: String,
            nativeCrash: Boolean,
            dumpFile: String,
            dumpSymbolFile: String,
            logFile: String,
        ) {
            onAfter(crashId, nativeCrash, dumpFile, dumpSymbolFile, logFile)
        }
    })
}
