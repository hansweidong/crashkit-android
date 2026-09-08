package com.yj.crashkit.log

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetry
import com.yj.crashkit.CrashType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class CrashKitLogCrashSinkTest {
    @Test
    fun encodesThenHandsBodyToHostTransport() {
        val dump = File(System.getProperty("java.io.tmpdir"), "sink-${System.nanoTime()}.dmp")
        dump.writeText("java.lang.NullPointerException: boom\n\tat com.example.Foo.a(Foo.java:1)\n")
        val captured = AtomicReference<String>()
        try {
            val record = CrashRecord(
                "id-1",
                CrashType.JAVA_CRASH,
                """{"exception":"java.lang.NullPointerException: boom"}""",
                listOf(dump),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            val sink = CrashKitLogCrashSink(
                session = {
                    CrashKitLogSession(
                        lanId = "lan",
                        secId = 2,
                        crashBehavior = "host-crash-behavior",
                        bodyAsListWrapper = true,
                    )
                },
                transport = CrashKitLogTransport { body ->
                    captured.set(body)
                    true
                },
            )
            assertTrue(sink.onTelemetryAck(payload, record))
            val body = captured.get()
            assertTrue(body.contains("\"list\""))
            assertTrue(body.contains("host-crash-behavior"))
            assertFalse(body.contains("http://"))
        } finally {
            dump.delete()
        }
    }

    @Test
    fun hostFalseKeepsFailure() {
        val record = CrashRecord("id-2", CrashType.ANR_CRASH, "{}", emptyList(), emptyList())
        val payload = CrashTelemetry.of(record)
        val sink = CrashKitLogCrashSink(
            transport = CrashKitLogTransport { false },
        )
        assertFalse(sink.onTelemetryAck(payload, record))
    }

    @Test
    fun hostThrowIsFailure() {
        val record = CrashRecord("id-3", CrashType.JAVA_CRASH, "{}", emptyList(), emptyList())
        val payload = CrashTelemetry.of(record)
        val sink = CrashKitLogCrashSink(
            transport = CrashKitLogTransport { error("net down") },
        )
        assertFalse(sink.onTelemetryAck(payload, record))
    }
}
