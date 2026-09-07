package com.yj.crashkit.log

import com.yj.crashkit.CrashRecord
import com.yj.crashkit.CrashTelemetry
import com.yj.crashkit.CrashType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WigoLogEventTest {
    @Test
    fun javaCrashMatchesLogModelFields() {
        val dump = File(System.getProperty("java.io.tmpdir"), "wigo-crash-${System.nanoTime()}.dmp")
        dump.writeText(
            """
            java.lang.NullPointerException: boom
            	at com.example.app.Foo.bar(Foo.java:9)
            Caused by: java.lang.IllegalStateException: inner
            	at com.example.app.Foo.init(Foo.java:2)
            """.trimIndent(),
        )
        try {
            val record = CrashRecord(
                "id-1",
                CrashType.JAVA_CRASH,
                """{"exception":"java.lang.NullPointerException: boom","app_ver":"2.0.0","pkg":"com.example.app"}""",
                listOf(dump),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            val body = WigoLogEvent.requestBody(
                payload,
                record,
                WigoLogSession(
                    pkg = "xyz.kenterk.test.android",
                    ver = "1.0.0",
                    deviceId = "dev-1",
                    userId = "u-9",
                    lanId = "lan-1",
                    secId = 7,
                    bodyAsListWrapper = true,
                ),
            )
            val root = JSONObject(body)
            val event = root.getJSONArray("list").getJSONObject(0)
            assertEquals(WigoLogEvent.LOG_TYPE, event.getString("log_type"))
            assertEquals(WigoLogEvent.SUBTYPE, event.getString("subtype"))
            assertEquals(WigoLogEvent.BEHAVIOR_CRASH, event.getString("behavior"))
            assertEquals(WigoLogEvent.CLIENT_TYPE, event.getString("client_type"))
            assertEquals("crashkit", event.getString("bizver"))
            assertEquals("1.3", event.getString("p_ver"))
            assertEquals("xyz.kenterk.test.android", event.getString("pkg"))
            assertEquals("u-9", event.getString("user_id"))
            val crash = event.getJSONArray("data").getJSONObject(0)
            assertEquals("crashkit", crash.getString("sdk"))
            assertTrue(crash.getString("sdk_ver").isNotEmpty())
            assertEquals("JAVA_CRASH", crash.getString("crash_type"))
            assertEquals("id-1", crash.getString("crash_id"))
            assertEquals("java.lang.NullPointerException", crash.getString("ext_data1"))
            assertEquals("boom", crash.getString("ext_data2"))
            assertEquals("java.lang.IllegalStateException", crash.getString("ext_data3"))
            assertTrue(crash.getString("stack_trace").contains("Foo.bar"))
        } finally {
            dump.delete()
        }
    }

    @Test
    fun anrUsesClientAnrAndMainStack() {
        val dir = File(System.getProperty("java.io.tmpdir"), "wigo-anr-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val main = File(dir, "main_stack.txt")
        main.writeText(
            """
            ----- main "main" state=TIMED_WAITING
              at java.lang.Thread.sleep(Native Method)
              at com.wigo.liveh5dev.DebugAnrService.onStartCommand(DebugAnrService.kt:13)
            """.trimIndent(),
        )
        try {
            val record = CrashRecord(
                "anr-1",
                CrashType.ANR_CRASH,
                """{"exception":"ANR"}""",
                listOf(main),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            val raw = WigoLogEvent.requestBody(
                payload,
                record,
                WigoLogSession(lanId = "lan", secId = 1, bodyAsListWrapper = false),
            )
            val list = JSONArray(raw)
            val event = list.getJSONObject(0)
            assertEquals(WigoLogEvent.BEHAVIOR_ANR, event.getString("behavior"))
            assertEquals(WigoLogEvent.SUBTYPE, event.getString("subtype"))
            val crash = event.getJSONArray("data").getJSONObject(0)
            assertEquals("crashkit", crash.getString("sdk"))
            assertEquals("ANR_CRASH", crash.getString("crash_type"))
            assertEquals("ANR_CRASH", crash.getString("ext_data1"))
            assertTrue(crash.getString("ext_data2").contains("----- main"))
            assertTrue(crash.getString("stack_trace").contains("DebugAnrService"))
        } finally {
            main.delete()
            dir.delete()
        }
    }

    @Test
    fun httpSuccessFollowsWigoApiResult() {
        assertTrue(WigoLogEvent.httpSucceeded(200, """{"code":0,"success":true,"fail":false}"""))
        assertFalse(WigoLogEvent.httpSucceeded(200, """{"code":1,"success":false}"""))
        assertFalse(WigoLogEvent.httpSucceeded(500, "{}"))
        assertTrue(WigoLogEvent.httpSucceeded(204, ""))
    }
}
