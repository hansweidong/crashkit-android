package com.yj.crashkit.log

/**
 * 宿主网络出口。CrashKit 只组日志 JSON，**不自己开连接**。
 *
 * [send] 返回 `true` 才会删 pending。应在返回前确认已送达或已写入宿主自己的持久化队列。
 */
fun interface CrashKitLogTransport {
    fun send(body: String): Boolean
}
