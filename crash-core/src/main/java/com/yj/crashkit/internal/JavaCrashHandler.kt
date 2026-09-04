package com.yj.crashkit.internal

import com.yj.crashkit.util.KitLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 进程默认 UncaughtExceptionHandler。
 *
 * 宿主在 [com.yj.crashkit.CrashKit.init] 之后再 `setDefaultUncaughtExceptionHandler`
 * 是常见接入方式。发现被替换后会把对方收成内层、自己重新站到最外层，这样
 * `setTelemetrySink` 仍能收到 JAVA_CRASH。业务过滤请走 CrashCallback。
 */
class JavaCrashHandler private constructor(
    private val onJavaCrash: (Thread, Throwable) -> Unit,
) : Thread.UncaughtExceptionHandler {
    private val handling = AtomicBoolean(false)

    @Volatile
    private var nextHandler: Thread.UncaughtExceptionHandler? =
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
        }
        try {
            val next = nextHandler
            if (next != null && next !== this) {
                next.uncaughtException(thread, ex)
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "nextHandler", t)
        }
    }

    @Synchronized
    fun rewrapIfNeeded() {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === this) {
            return
        }
        nextHandler = current
        Thread.setDefaultUncaughtExceptionHandler(this)
        KitLog.i(TAG, "re-wrapped UEH was=${current?.javaClass?.name}")
    }

    companion object {
        private const val TAG = "JavaCrashHandler"
        private var instance: JavaCrashHandler? = null

        @JvmStatic
        @Synchronized
        fun install(pipeline: CrashPipeline) {
            if (instance == null) {
                instance = JavaCrashHandler { thread, ex ->
                    pipeline.handleJava(thread, ex)
                }
            } else {
                instance?.rewrapIfNeeded()
            }
        }

        @JvmStatic
        fun ensureOuter() {
            instance?.rewrapIfNeeded()
        }

        @Synchronized
        internal fun installForTest(onJavaCrash: (Thread, Throwable) -> Unit): JavaCrashHandler {
            resetForTest()
            return JavaCrashHandler(onJavaCrash).also { instance = it }
        }

        @Synchronized
        internal fun resetForTest() {
            instance = null
        }
    }
}
