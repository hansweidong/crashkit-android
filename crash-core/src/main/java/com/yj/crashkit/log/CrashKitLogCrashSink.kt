package com.yj.crashkit.log

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetryAckSink
import com.yj.crashkit.CrashTelemetryPayload
import com.yj.crashkit.util.KitLog

/**
 * 把采集结果编成 JSON，再交给宿主 [CrashKitLogTransport] 发送。信封分类字段来自 [CrashKitLogSession]。
 *
 * CrashKit 不发起 HTTP。鉴权、域名、加密、重试都由宿主网络栈负责。
 * [CrashKitLogTransport.send] 返回 `true` 才删 pending。
 */
class CrashKitLogCrashSink(
    private val session: () -> CrashKitLogSession = { CrashKitLogSession.Default },
    private val transport: CrashKitLogTransport,
) : CrashTelemetryAckSink {
    override fun onTelemetryAck(payload: CrashTelemetryPayload, record: CrashRecord): Boolean {
        val fields = try {
            session()
        } catch (t: Throwable) {
            KitLog.e(TAG, "session", t)
            return false
        }
        val body = try {
            CrashKitLogEvent.requestBody(payload, record, fields)
        } catch (t: Throwable) {
            KitLog.e(TAG, "encode", t)
            return false
        }
        return try {
            val ok = transport.send(body)
            KitLog.i(TAG, "host send ok=$ok bytes=${body.length}")
            ok
        } catch (t: Throwable) {
            KitLog.e(TAG, "host send", t)
            false
        }
    }

    companion object {
        private const val TAG = "CrashKitLogCrashSink"
    }
}
