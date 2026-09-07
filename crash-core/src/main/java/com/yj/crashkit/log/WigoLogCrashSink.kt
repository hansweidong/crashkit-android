package com.yj.crashkit.log

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetryAckSink
import com.yj.crashkit.CrashTelemetryPayload
import com.yj.crashkit.util.KitLog
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.Charset

/**
 * 按 Wigo `LogModel.submitCrash` 的 JSON 把崩溃 / ANR POST 到宿主给出的地址。
 *
 * 返回 `true` 才删 pending；HTTP 失败下次启动重投。不要把加密过的业务网关地址填进来，
 * 除非宿主已经在 [WigoLogSession.headers] 里带好鉴权——CrashKit 只发明文 JSON。
 */
class WigoLogCrashSink(
    private val endpoint: String,
    private val session: () -> WigoLogSession = { WigoLogSession() },
) : CrashTelemetryAckSink {
    override fun onTelemetryAck(payload: CrashTelemetryPayload, record: CrashRecord): Boolean {
        val url = endpoint.trim()
        if (url.isEmpty()) {
            KitLog.i(TAG, "skip upload: empty endpoint")
            return false
        }
        val fields = try {
            session()
        } catch (t: Throwable) {
            KitLog.e(TAG, "session", t)
            return false
        }
        val body = try {
            WigoLogEvent.requestBody(payload, record, fields)
        } catch (t: Throwable) {
            KitLog.e(TAG, "encode", t)
            return false
        }
        return post(url, body, fields)
    }

    private fun post(url: String, body: String, session: WigoLogSession): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = session.connectTimeoutMs.coerceAtLeast(500)
                readTimeout = session.readTimeoutMs.coerceAtLeast(500)
                doOutput = true
                useCaches = false
                instanceFollowRedirects = true
                setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                for ((k, v) in session.headers) {
                    if (k.isNotEmpty()) {
                        setRequestProperty(k, v)
                    }
                }
            }
            val bytes = body.toByteArray(CHARSET)
            conn.setFixedLengthStreamingMode(bytes.size)
            BufferedOutputStream(conn.outputStream).use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = if (stream == null) {
                ""
            } else {
                BufferedInputStream(stream).use { it.readBytes().toString(CHARSET) }
            }
            val ok = WigoLogEvent.httpSucceeded(code, resp)
            KitLog.i(TAG, "POST $url code=$code ok=$ok bytes=${bytes.size}")
            ok
        } catch (t: Throwable) {
            KitLog.e(TAG, "POST $url", t)
            false
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "WigoLogCrashSink"
        private val CHARSET = Charset.forName("UTF-8")
    }
}
