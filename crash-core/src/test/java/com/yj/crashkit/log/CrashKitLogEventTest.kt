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

class CrashKitLogEventTest {
    @Test
    fun javaCrashMatchesLogModelFields() {
        val dump = File(System.getProperty("java.io.tmpdir"), "crashkit-crash-${System.nanoTime()}.dmp")
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
            val body = CrashKitLogEvent.requestBody(
                payload,
                record,
                CrashKitLogSession(
                    pkg = "xyz.kenterk.test.android",
                    ver = "1.0.0",
                    deviceId = "dev-1",
                    userId = "u-9",
                    lanId = "lan-1",
                    secId = 7,
                    logType = HOST_LOG_TYPE,
                    subtype = HOST_SUBTYPE,
                    crashBehavior = HOST_CRASH_BEHAVIOR,
                    anrBehavior = HOST_ANR_BEHAVIOR,
                    bodyAsListWrapper = true,
                ),
            )
            val root = JSONObject(body)
            val event = root.getJSONArray("list").getJSONObject(0)
            assertEquals(HOST_LOG_TYPE, event.getString("log_type"))
            assertEquals(HOST_SUBTYPE, event.getString("subtype"))
            assertEquals(HOST_CRASH_BEHAVIOR, event.getString("behavior"))
            assertEquals(CrashKitLogEvent.CLIENT_TYPE, event.getString("client_type"))
            assertEquals("crashkit", event.getString("bizver"))
            assertEquals("1.3", event.getString("p_ver"))
            assertEquals("xyz.kenterk.test.android", event.getString("pkg"))
            assertEquals("u-9", event.getString("user_id"))
            val crash = event.getJSONArray("data").getJSONObject(0)
            assertEquals("crashkit", crash.getString("sdk"))
            assertTrue(crash.getString("sdk_ver").isNotEmpty())
            assertEquals("JAVA_CRASH", crash.getString("crash_type"))
            assertEquals("id-1", crash.getString("crash_id"))
            assertTrue(crash.getString("exception").contains("NullPointerException"))
            assertTrue(crash.getString("stack_trace").contains("Foo.bar"))
            assertFalse(crash.has("ext_data1"))
        } finally {
            dump.delete()
        }
    }

    @Test
    fun anrUsesClientAnrAndMainStack() {
        val dir = File(System.getProperty("java.io.tmpdir"), "crashkit-anr-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val main = File(dir, "main_stack.txt")
        main.writeText(
            """
            ----- main "main" state=TIMED_WAITING
              at java.lang.Thread.sleep(Native Method)
              at com.crashkit.sample.DebugAnrService.onStartCommand(DebugAnrService.kt:13)
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
            val raw = CrashKitLogEvent.requestBody(
                payload,
                record,
                CrashKitLogSession(
                    lanId = "lan",
                    secId = 1,
                    logType = HOST_LOG_TYPE,
                    subtype = HOST_SUBTYPE,
                    crashBehavior = HOST_CRASH_BEHAVIOR,
                    anrBehavior = HOST_ANR_BEHAVIOR,
                    bodyAsListWrapper = false,
                ),
            )
            val list = JSONArray(raw)
            val event = list.getJSONObject(0)
            assertEquals(HOST_ANR_BEHAVIOR, event.getString("behavior"))
            assertEquals(HOST_SUBTYPE, event.getString("subtype"))
            val crash = event.getJSONArray("data").getJSONObject(0)
            assertEquals("crashkit", crash.getString("sdk"))
            assertEquals("ANR_CRASH", crash.getString("crash_type"))
            assertTrue(crash.getString("stack_trace").contains("DebugAnrService"))
            assertFalse(crash.has("ext_data1"))
        } finally {
            main.delete()
            dir.delete()
        }
    }

    @Test
    fun httpSuccessFollowsCrashKitApiResult() {
        assertTrue(CrashKitLogEvent.httpSucceeded(200, """{"code":0,"success":true,"fail":false}"""))
        assertFalse(CrashKitLogEvent.httpSucceeded(200, """{"code":1,"success":false}"""))
        assertFalse(CrashKitLogEvent.httpSucceeded(500, "{}"))
        assertTrue(CrashKitLogEvent.httpSucceeded(204, ""))
    }

    @Test
    fun memoryAndExtrasComeFromCrashMetaNotLiveRuntime() {
        val dump = File(System.getProperty("java.io.tmpdir"), "crashkit-mem-${System.nanoTime()}.dmp")
        dump.writeText("java.lang.OutOfMemoryError: Java heap space\n\tat com.example.Foo.a(Foo.java:1)\n")
        try {
            val record = CrashRecord(
                "oom-1",
                CrashType.JAVA_OOM,
                """{"exception":"java.lang.OutOfMemoryError: Java heap space","app_ver":"2.0.0","pkg":"com.example.app","process":"com.example.app:push","thread_id":"9","crash_time_ms":"1700000000123","is_in_bg":"1","mem_total_mb":"7800","mem_java_used_mb":"500","mem_java_alloc_mb":"512","mem_java_max_mb":"512","heap_pct":"97","mem_pss_mb":"640","vm_rss_kb":"420000","vm_size_kb":"2100000","fd_count":"120","thread_count":"64","history":"C:Main -> R:Main","ext":{"room":"1"}}""",
                listOf(dump),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            assertEquals("7800", payload.memoryTotal)
            assertEquals("512", payload.memoryAllocate)
            assertEquals("640", payload.memoryUsage)
            assertEquals(1700000000123L, payload.crashTimeMs)
            assertEquals(true, payload.isInBg)
            val body = CrashKitLogEvent.requestBody(
                payload,
                record,
                CrashKitLogSession(
                    lanId = "lan",
                    secId = 3,
                    isInBg = false,
                    memoryTotal = "1",
                    memoryUsage = "2",
                    bodyAsListWrapper = false,
                ),
            )
            val event = JSONArray(body).getJSONObject(0)
            val crash = event.getJSONArray("data").getJSONObject(0)
            assertEquals("7800", crash.getString("memory_total"))
            assertEquals("512", crash.getString("memory_allocate"))
            assertEquals("640", crash.getString("memory_usage"))
            assertEquals("500", crash.getString("heap_used"))
            assertEquals("512", crash.getString("heap_max"))
            assertEquals("97", crash.getString("heap_pct"))
            assertEquals("420000", crash.getString("vm_rss_kb"))
            assertEquals("120", crash.getString("fd"))
            assertEquals("64", crash.getString("threads"))
            assertEquals("Main(C:R)", crash.getString("activity"))
            assertEquals("com.example.app:push", crash.getString("proc"))
            assertEquals("9", crash.getString("thread_id"))
            assertEquals(1700000000123L, crash.getLong("tm"))
            assertTrue(crash.getBoolean("is_in_bg"))
            assertEquals(1700000000123L, event.getLong("tm"))
            assertTrue(event.getBoolean("is_in_bg"))
            assertTrue(crash.getString("stack_trace").startsWith("mem:"))
            assertTrue(crash.getString("stack_trace").contains("ram=7800"))
            assertTrue(crash.getString("exception").contains("OutOfMemoryError"))
            assertFalse(crash.has("ext_data1"))
        } finally {
            dump.delete()
        }
    }

    @Test
    fun oldPendingWithoutMetaStillParsesOomCounters() {
        val dir = File(System.getProperty("java.io.tmpdir"), "oom-lite-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val lite = File(dir, "oom_lite.txt")
        lite.writeText("heap_used=${80L * 1024 * 1024}\nheap_max=${100L * 1024 * 1024}\nheap_pct=80\nVmRSS=300000 kB\n")
        try {
            val record = CrashRecord(
                "old-1",
                CrashType.JAVA_OOM,
                """{"exception":"java.lang.OutOfMemoryError"}""",
                emptyList(),
                listOf(lite),
            )
            val payload = CrashTelemetry.of(record)
            assertEquals("80", payload.heapUsedMb)
            assertEquals("100", payload.heapMaxMb)
            assertEquals("80", payload.heapPct)
            assertEquals("300000", payload.vmRssKb)
            val crash = JSONArray(
                CrashKitLogEvent.requestBody(payload, record, CrashKitLogSession(bodyAsListWrapper = false)),
            ).getJSONObject(0).getJSONArray("data").getJSONObject(0)
            assertEquals("80", crash.getString("memory_usage"))
            assertEquals("80", crash.getString("heap_used"))
            assertTrue(crash.getString("res").contains("heap_used="))
        } finally {
            lite.delete()
            dir.delete()
        }
    }

    @Test
    fun omittedHostEnvelopeFieldsUseLocalDefault() {
        val record = CrashRecord("id-omit", CrashType.JAVA_CRASH, """{"exception":"e"}""", emptyList(), emptyList())
        val event = CrashKitLogEvent.logEvent(CrashTelemetry.of(record), record, CrashKitLogSession.Default)
        assertEquals(CrashKitLogSession.Default.logType, event.getString("log_type"))
        assertEquals(CrashKitLogSession.Default.subtype, event.getString("subtype"))
        assertEquals(CrashKitLogSession.Default.crashBehavior, event.getString("behavior"))
    }

    companion object {
        private const val HOST_LOG_TYPE = "host-log-type"
        private const val HOST_SUBTYPE = "host-subtype"
        private const val HOST_CRASH_BEHAVIOR = "host-crash-behavior"
        private const val HOST_ANR_BEHAVIOR = "host-anr-behavior"
    }
}
