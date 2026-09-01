package com.yj.crashkit.internal

@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
class CrashBlocker {
    @Volatile private var waiting = PREWAITING
    @Volatile private var unblockCount = 0
    @Volatile private var needUnblockCount = 0

    fun preBlock(needUnblockCount: Int) {
        waiting = PREWAITING
        unblockCount = 0
        this.needUnblockCount = needUnblockCount
    }

    @Synchronized
    fun unblock() {
        if (waiting != UNBLOCKED) {
            unblockCount += 1
            if (unblockCount >= needUnblockCount) {
                waiting = UNBLOCKED
                (this as Object).notifyAll()
            }
        }
    }

    @Synchronized
    fun waitForUnblock(waitTimeMs: Int): Boolean {
        if (waiting != PREWAITING) {
            return true
        }
        return try {
            waiting = WAITING
            (this as Object).wait(waitTimeMs.toLong())
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            waiting = UNBLOCKED
            false
        }
    }

    private companion object {
        const val PREWAITING = 0
        const val WAITING = 1
        const val UNBLOCKED = 2
    }
}
