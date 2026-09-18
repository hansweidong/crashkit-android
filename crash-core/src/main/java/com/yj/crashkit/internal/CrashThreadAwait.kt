package com.yj.crashkit.internal

import android.os.Looper
import com.yj.crashkit.util.KitLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 对齐 Crashlytics `Utils.awaitEvenIfOnMainThread`：致命采集放到后台线程，
 * 崩溃线程最多等主线程 3s / 其它线程 4s，超时仍把控制权交回系统 handler。
 */
internal object CrashThreadAwait {
    private const val TAG = "CrashThreadAwait"

    fun run(work: () -> Unit) {
        run(CrashKitOnlinePolicy.crashAwaitMs(isMainThread()), work)
    }

    fun run(timeoutMs: Int, work: () -> Unit) {
        val latch = CountDownLatch(1)
        val worker = Thread({
            try {
                work()
            } catch (t: Throwable) {
                KitLog.e(TAG, "fatal work", t)
            } finally {
                latch.countDown()
            }
        }, "CrashKit-Fatal")
        worker.isDaemon = true
        try {
            worker.start()
        } catch (t: Throwable) {
            KitLog.e(TAG, "start fatal worker", t)
            try {
                work()
            } catch (inner: Throwable) {
                KitLog.e(TAG, "fatal work", inner)
            } finally {
                latch.countDown()
            }
            return
        }
        val waitMs = timeoutMs.coerceAtLeast(0).toLong()
        try {
            if (!latch.await(waitMs, TimeUnit.MILLISECONDS)) {
                KitLog.e(TAG, "fatal work timed out after ${waitMs}ms")
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun isMainThread(): Boolean {
        return try {
            Looper.getMainLooper().thread === Thread.currentThread()
        } catch (_: Throwable) {
            false
        }
    }
}
