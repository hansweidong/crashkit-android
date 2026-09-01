package com.yj.crashkit.internal

import java.util.Collections
import java.util.WeakHashMap

/**
 * 协程全局 Handler 会先走 [com.yj.crashkit.CrashKit.uploadCustomCrash]，
 * kotlinx 随后仍可能把同一条异常交给 UEH。用这个去重，避免 JAVA_ERROR + JAVA_CRASH 各打一份。
 */
internal object CrashKitExceptionDeduper {
    private val lock = Any()
    private val marked: MutableSet<Throwable> =
        Collections.newSetFromMap(WeakHashMap())

    fun mark(throwable: Throwable?) {
        if (throwable == null) {
            return
        }
        synchronized(lock) {
            var t: Throwable? = throwable
            var depth = 0
            while (t != null && depth++ < 8) {
                marked.add(t)
                t = t.cause
            }
        }
    }

    fun wasMarked(throwable: Throwable?): Boolean {
        if (throwable == null) {
            return false
        }
        synchronized(lock) {
            var t: Throwable? = throwable
            var depth = 0
            while (t != null && depth++ < 8) {
                if (marked.contains(t)) {
                    return true
                }
                t = t.cause
            }
        }
        return false
    }
}
