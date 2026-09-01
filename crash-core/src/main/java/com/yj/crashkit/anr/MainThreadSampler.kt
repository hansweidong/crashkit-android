package com.yj.crashkit.anr

import android.os.Looper
import com.yj.crashkit.CrashKitLab
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import java.util.ArrayList

/** 主线程栈采样。间隔为 [Long.MAX_VALUE] 时不建线程、不采样。线上间隔不低于 200ms。 */
class MainThreadSampler private constructor() {
    private val lock = Any()
    private var sampler: StackSampler? = null
    private var started = false
    @Volatile private var processName = ""
    @Volatile private var pendingMaxEntryCount = 0

    @Synchronized
    fun start(sampleIntervalMs: Long) {
        val interval = CrashKitOnlinePolicy.sampleInterval(sampleIntervalMs, CrashKitLab.isEnabled())
        if (interval != sampleIntervalMs && sampleIntervalMs >= 10) {
            CrashKitLab.logClamp("MainThreadSampler.start", sampleIntervalMs, interval)
        }
        if (!CrashKitOnlinePolicy.shouldSampleMainThread(interval)) {
            return
        }
        if (started) {
            sampler?.setSampleInterval(interval)
            return
        }
        started = true
        val created = getOrCreateSampler()
        created.setSampleInterval(interval)
        created.start()
    }

    fun setSampleInterval(sampleIntervalMs: Long) {
        val interval = CrashKitOnlinePolicy.sampleInterval(sampleIntervalMs, CrashKitLab.isEnabled())
        sampler?.setSampleInterval(interval)
    }

    fun setMaxEntryCount(max: Int) {
        pendingMaxEntryCount = max
        sampler?.setMaxEntryCount(max)
    }

    fun setProcessName(processName: String?) {
        this.processName = processName ?: ""
        sampler?.setProcessName(this.processName)
    }

    fun getThreadStackEntries(startMs: Long, endMs: Long): ArrayList<String> {
        val current = sampler
        if (current == null) {
            return ArrayList()
        }
        return current.getThreadStackEntries(startMs, endMs)
    }

    private fun getOrCreateSampler(): StackSampler {
        val existing = sampler
        if (existing != null) {
            return existing
        }
        synchronized(lock) {
            var created = sampler
            if (created == null) {
                created = StackSampler(Looper.getMainLooper().thread, DEFAULT_INTERVAL_MS)
                created.setProcessName(processName)
                if (pendingMaxEntryCount > 0) {
                    created.setMaxEntryCount(pendingMaxEntryCount)
                }
                sampler = created
            }
            return created
        }
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 1000L
        const val HIGH_FREQ_INTERVAL_MS = 53L
        private val INSTANCE = MainThreadSampler()

        @JvmStatic
        fun get(): MainThreadSampler = INSTANCE
    }
}
