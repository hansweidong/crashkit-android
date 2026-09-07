package com.yj.crashkit.anr

import android.os.Looper
import android.os.Message
import android.os.MessageQueue
import android.os.SystemClock
import com.yj.crashkit.internal.CrashKitOnlinePolicy
import com.yj.crashkit.util.KitLog
import java.lang.reflect.Field

/**
 * 读主线程 MessageQueue 队头，判断是否已超期。
 * 对应 Matrix SignalAnrTracer.isMainThreadBlocked：不往主线程 post，避免干扰 AMS。
 */
internal object AnrMainQueue {
    private const val TAG = "AnrMainQueue"
    @Volatile private var messagesField: Field? = null
    @Volatile private var fieldTried = false
    @Volatile private var peekWarned = false

    data class Head(
        val text: String,
        val delayMs: Long,
    )

    fun isBlocked(foreground: Boolean): Boolean {
        val head = peekHead() ?: return false
        return isOverdue(head.delayMs, foreground)
    }

    fun peekHead(): Head? {
        return try {
            val queue = Looper.getMainLooper().queue
            val msg = headMessage(queue) ?: return null
            val whenMs = msg.`when`
            if (whenMs == 0L) {
                return null
            }
            Head(
                text = msg.toString(),
                delayMs = whenMs - SystemClock.uptimeMillis(),
            )
        } catch (t: Throwable) {
            if (!peekWarned) {
                peekWarned = true
                KitLog.w(TAG, "peekHead", t)
            }
            null
        }
    }

    internal fun isOverdue(delayMs: Long, foreground: Boolean): Boolean {
        val threshold = if (foreground) {
            CrashKitOnlinePolicy.ANR_FOREGROUND_MSG_THRESHOLD_MS
        } else {
            CrashKitOnlinePolicy.ANR_BACKGROUND_MSG_THRESHOLD_MS
        }
        return delayMs < threshold
    }

    private fun headMessage(queue: MessageQueue): Message? {
        val field = messagesField() ?: return null
        return field.get(queue) as? Message
    }

    private fun messagesField(): Field? {
        if (fieldTried) {
            return messagesField
        }
        synchronized(this) {
            if (fieldTried) {
                return messagesField
            }
            fieldTried = true
            return try {
                val f = MessageQueue::class.java.getDeclaredField("mMessages")
                f.isAccessible = true
                messagesField = f
                f
            } catch (t: Throwable) {
                KitLog.w(TAG, "mMessages", t)
                null
            }
        }
    }
}
