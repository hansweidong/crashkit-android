package com.yj.crashkit.internal

import com.yj.crashkit.util.KitLog

/**
 * 唯一的 UncaughtExceptionHandler。业务过滤请走 CrashCallback，不要再套一层 UEH。
 */
class JavaCrashHandler private constructor(
    private val pipeline: CrashPipeline,
) : Thread.UncaughtExceptionHandler {
    private val defaultHandler: Thread.UncaughtExceptionHandler? =
        Thread.getDefaultUncaughtExceptionHandler()

    init {
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        try {
            if (!CrashKitExceptionDeduper.wasMarked(ex)) {
                pipeline.handleJava(thread, ex)
            }
        } catch (t: Throwable) {
            KitLog.e(TAG, "handleJava", t)
        }
        defaultHandler?.uncaughtException(thread, ex)
    }

    companion object {
        private const val TAG = "JavaCrashHandler"
        private var instance: JavaCrashHandler? = null

        @JvmStatic
        @Synchronized
        fun install(pipeline: CrashPipeline) {
            if (instance != null) {
                return
            }
            instance = JavaCrashHandler(pipeline)
        }
    }
}
