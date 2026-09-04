package com.yj.crashkit

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.yj.crashkit.anr.AnrDetector
import com.yj.crashkit.anr.AnrListener
import com.yj.crashkit.anr.MainThreadSampler
import com.yj.crashkit.history.ActivityTracker
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.internal.CrashKitRuntime
import com.yj.crashkit.internal.CrashPipeline
import com.yj.crashkit.internal.JavaCrashHandler
import com.yj.crashkit.nativecrash.NativeCrashBridge
import com.yj.crashkit.oom.JavaOomMonitor
import com.yj.crashkit.reporter.CrashReporter
import com.yj.crashkit.reporter.NoOpCrashReporter
import com.yj.crashkit.reporter.TelemetryCrashReporter
import com.yj.crashkit.resource.ResourceMonitor
import com.yj.crashkit.util.KitLog

/**
 * 采集入口。不含 HTTP。埋点宿主注册 [CrashTelemetrySink]；文件/自建通道实现 [CrashReporter]。
 *
 * 线上请只调本对象。ANR 的 AM poll / SIGQUIT 随 [init] 打开。
 * 加重诊断（主动崩溃、53ms 采样、inline 轮询、hprof）走 [CrashKitLab]。
 */
object CrashKit {
    const val VERSION = "1.1.6"
    private const val TAG = "CrashKit"

    private val lock = Any()
    @Volatile private var inited = false
    @Volatile private var pipeline: CrashPipeline? = null
    @Volatile private var anrDetector: AnrDetector? = null

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
            KitLog.i(TAG, "init version=$VERSION native=$nativeOk dumpDir=${runtime.dumpDir}")
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

    @JvmStatic
    fun setCrashCallback(callback: CrashCallback?) {
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

    @JvmStatic
    fun setUid(uid: Long) {
        CrashKitRuntime.get()?.setUid(uid)
    }

    @JvmStatic
    fun setExtInfo(extInfo: Map<String, String>?) {
        CrashKitRuntime.get()?.setExtInfo(extInfo)
    }

    @JvmStatic
    fun addExtInfo(extInfo: Map<String, String>?) {
        CrashKitRuntime.get()?.addExtInfo(extInfo)
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
     * 打开或加强 ANR 检测。
     *
     * [CrashKit.init] 已经启动 1s AM poll 和 SIGQUIT traces，不必再调本方法才能上报 ANR。
     * 传入有限间隔时才会采主线程栈（线上不低于 200ms）。53ms 见 [CrashKitLab.startAggressiveAnrSampling]。
     */
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
    fun uploadCustomCrash(type: CrashType?, throwable: Throwable?) {
        pipeline?.handleCustom(type ?: CrashType.JAVA_ERROR, throwable)
    }

    @JvmStatic
    fun uploadCustomCrash(type: CrashType?, stack: String?, threadId: Int) {
        pipeline?.handleCustom(type ?: CrashType.JAVA_ERROR, stack, threadId)
    }

    /**
     * 自研 OOM 预检。线上忽略 [dumpHprof]；要 dump hprof 必须先 [CrashKitLab.enable]。
     */
    @JvmStatic
    fun openJavaOom(application: Application?, dumpHprof: Boolean) {
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
