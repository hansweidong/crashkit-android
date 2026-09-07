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
        // Object.wait(0) 的语义是**无限等**，不是「不等」。ANR 的 blockerWaitMs 就是 0，
        // 宿主 reporter 只要是异步的（不在 report() 里同步 onResult），这里会把 ANR dump
        // 线程永久挂住，`dumping` 标志也永远放不掉，后续 SIGQUIT 全被「dump thread busy」挡掉。
        if (waitTimeMs <= 0) {
            waiting = UNBLOCKED
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
