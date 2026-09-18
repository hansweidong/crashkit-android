package com.yj.crashkit.internal

import com.yj.crashkit.util.KitLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程默认 UncaughtExceptionHandler，对齐 Firebase Crashlytics
 * `CrashlyticsUncaughtExceptionHandler`。
 *
 * 第一次 [install] 时记下当时的系统 handler。采集限时完成后把线程交回
 * [originalHandler]（ART `KillApplicationHandler`），由 AMS 按正式崩溃收场。
 * 没有上一层 handler 时 [System.exit] `(1)`，与 Java 默认 UEH / Crashlytics 一致。
 * 不 `rewrap` 抢最外层，不 `startActivity`，不 `killProcess`。
 *
 * 宿主若在 [com.yj.crashkit.CrashKit.init] 之后再装自己的 UEH，应包在外层、
 * 做完业务后回调本 handler；本 handler 始终只转给安装时记下的那一层。
 */
class JavaCrashHandler private constructor(
    private val onJavaCrash: (Thread, Throwable) -> Unit,
    private val processExit: () -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val handling = AtomicBoolean(false)

    /** 第一次 install 时的系统/上一层 handler。 */
    private val originalHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        if (!handling.compareAndSet(false, true)) {
            KitLog.i(TAG, "skip reentrant UEH")
            return
        }
        try {
            if (!CrashKitExceptionDeduper.wasMarked(ex)) {
                onJavaCrash(thread, ex)
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "handleJava", t)
        } finally {
            dispatchOriginal(thread, ex)
        }
    }

    private fun dispatchOriginal(thread: Thread, ex: Throwable) {
        val next = originalHandler
        if (next != null && next !== this) {
            try {
                next.uncaughtException(thread, ex)
            } catch (t: Throwable) {
                KitLog.e(TAG, "originalHandler", t)
            }
            return
        }
        KitLog.i(TAG, "no default exception handler, System.exit(1)")
        try {
            processExit()
        } catch (t: Throwable) {
            KitLog.e(TAG, "processExit", t)
        }
    }

    companion object {
        private const val TAG = "JavaCrashHandler"
        private var instance: JavaCrashHandler? = null

        @JvmStatic
        @Synchronized
        fun install(pipeline: CrashPipeline) {
            if (instance == null) {
                instance = JavaCrashHandler(
                    onJavaCrash = { thread, ex -> pipeline.handleJava(thread, ex) },
                    processExit = { System.exit(1) },
                )
            }
        }

        @Synchronized
        internal fun installForTest(onJavaCrash: (Thread, Throwable) -> Unit): JavaCrashHandler {
            return installForTest(onJavaCrash, processExit = {})
        }

        @Synchronized
        internal fun installForTest(
            onJavaCrash: (Thread, Throwable) -> Unit,
            processExit: () -> Unit,
        ): JavaCrashHandler {
            resetForTest()
            return JavaCrashHandler(onJavaCrash, processExit).also { instance = it }
        }

        @Synchronized
        internal fun resetForTest() {
            instance = null
        }
    }
}
