package com.yj.crashkit.anr

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 固定节拍采样。
 *
 * `postDelayed(interval)` 会把每轮 [doSample] 的耗时叠进间隔，跑久了采样时间轴会明显漂移，
 * ANR 上报里「卡死前的栈演化」就对不上时间。这里改成按 [SystemClock.uptimeMillis] 排下一次的
 * 绝对时刻；落后超过一整个间隔就重新对齐，不做补偿式连发。
 */
internal abstract class AbstractSampler(sampleIntervalMs: Long) {
    protected val shouldSample = AtomicBoolean(false)
    protected var sampleIntervalMs: Long = if (sampleIntervalMs <= 0) 1000 else sampleIntervalMs

    @Volatile private var nextAt = 0L

    private val runnable = object : Runnable {
        override fun run() {
            doSample()
            if (!shouldSample.get()) {
                return
            }
            val interval = this@AbstractSampler.sampleIntervalMs
            val now = SystemClock.uptimeMillis()
            var next = nextAt + interval
            if (next <= now) {
                next = now + interval
            }
            nextAt = next
            loopHandler().postAtTime(this, next)
        }
    }

    fun setSampleInterval(sampleIntervalMs: Long) {
        this.sampleIntervalMs = if (sampleIntervalMs < 10) 1000 else sampleIntervalMs
    }

    fun start() {
        if (shouldSample.getAndSet(true)) {
            return
        }
        val handler = loopHandler()
        handler.removeCallbacks(runnable)
        nextAt = SystemClock.uptimeMillis()
        handler.post(runnable)
    }

    protected abstract fun doSample()

    companion object {
        private val lock = Any()
        @Volatile private var handler: Handler? = null

        private fun loopHandler(): Handler {
            val existing = handler
            if (existing != null) {
                return existing
            }
            synchronized(lock) {
                var created = handler
                if (created == null) {
                    val thread = HandlerThread("AnrChecker-loop")
                    thread.start()
                    created = Handler(thread.looper)
                    handler = created
                }
                return created
            }
        }
    }
}
