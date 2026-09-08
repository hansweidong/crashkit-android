package com.yj.crashkit

import com.yj.crashkit.internal.TelemetryCompact
import com.yj.crashkit.reporter.CrashReporter
import com.yj.crashkit.reporter.TelemetryCrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CrashTelemetryUnitTest {
    @Test
    fun compactPayloadNeverExceeds9000() {
        val dump = tempFile("long.dmp", longJavaStack(220))
        try {
            val record = CrashRecord(
                "id-1",
                CrashType.JAVA_CRASH,
                """{"app_ver":"2.0.0","os_ver":"14","model":"Pixel 8","pkg":"com.example.app","thread_id":"12","uid":"1001","exception":"java.lang.NullPointerException: boom","history":"C:Splash -> S:Splash -> R:Splash -> C:Main -> R:Main"}""",
                listOf(dump),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            assertTrue(payload.wireText.length <= CrashTelemetry.MAX_CHARS)
            assertEquals(CrashType.JAVA_CRASH, payload.type)
            assertEquals("id-1", payload.crashId)
            assertTrue(payload.exception.contains("NullPointerException"))
            assertTrue(payload.stack.contains("com.example.app.Foo.bar") || payload.wireText.contains("Foo.bar"))
            val text = payload.wireText
            assertTrue(text.contains("JAVA_CRASH"))
            assertTrue(text.contains("id-1"))
        } finally {
            dump.delete()
        }
    }

    @Test
    fun frameworkFramesCollapsedButAppFrameKept() {
        val raw = buildString {
            appendLine("java.lang.RuntimeException: x")
            repeat(8) { appendLine("\tat android.os.Handler.handleMessage(Handler.java:1)") }
            repeat(30) { appendLine("\tat java.lang.Thread.run(Thread.java:1)") }
            appendLine("\tat com.example.app.Foo.bar(Foo.java:9)")
        }
        val compact = TelemetryCompact.compactStack(raw, 2000)
        assertTrue(compact.contains("RuntimeException"))
        assertTrue(compact.contains("com.example.app.Foo.bar"))
        assertTrue(compact.contains("fw"))
        assertTrue(compact.length < raw.length)
    }

    @Test
    fun nativeMapsDropped() {
        val raw = """
            *** CrashKit native dump ***
            signal: 11
            backtrace:
              #0 pc 0x1  /data/app/libfoo.so (crash)
              #1 pc 0x2  /system/lib64/libc.so
            maps:
            70e00000-70f00000 r-xp /system/lib64/libc.so
            70f00000-71000000 r-xp /system/lib64/libart.so
        """.trimIndent()
        val compact = TelemetryCompact.compactStack(raw, 1500)
        assertTrue(compact.contains("libfoo.so"))
        assertFalse(compact.contains("libart.so"))
        assertFalse(compact.contains("maps:"))
    }

    @Test
    fun telemetryReporterSendsOnlyMeta() {
        val dump = tempFile("npe.dmp", "java.lang.NullPointerException: boom\n\tat com.example.A.a(A.java:1)\n")
        val sent = AtomicReference<String>()
        val calls = AtomicInteger()
        val reporter = TelemetryCrashReporter { payload, _ ->
            calls.incrementAndGet()
            sent.set(payload.wireText)
        }
        val record = CrashRecord("c1", CrashType.JAVA_CRASH, "{}", listOf(dump), emptyList())
        var metaAck = false
        var dumpAck = false
        var logsAck = false
        reporter.report(record, ReportStage.META) { metaAck = it }
        reporter.report(record, ReportStage.DUMP) { dumpAck = it }
        reporter.report(record, ReportStage.LOGS) { logsAck = it }
        assertTrue(metaAck)
        assertTrue(dumpAck)
        assertTrue(logsAck)
        assertEquals(1, calls.get())
        assertTrue(sent.get().length <= CrashTelemetry.MAX_CHARS)
        dump.delete()
    }

    @Test
    fun telemetryReporterDedupesSameCrashId() {
        val dump = tempFile("dup.dmp", "java.lang.NullPointerException: boom\n")
        val calls = AtomicInteger()
        val reporter = TelemetryCrashReporter { _, _ -> calls.incrementAndGet() }
        val record = CrashRecord("same-id", CrashType.JAVA_CRASH, "{}", listOf(dump), emptyList())
        reporter.report(record, ReportStage.META) { }
        reporter.report(record, ReportStage.META) { }
        reporter.report(record, ReportStage.DUMP) { }
        assertEquals(1, calls.get())
        val other = CrashRecord("other-id", CrashType.JAVA_CRASH, "{}", listOf(dump), emptyList())
        reporter.report(other, ReportStage.META) { }
        assertEquals(2, calls.get())
        dump.delete()
    }

    @Test
    fun configTelemetrySinkInstallsAdapterReporter() {
        val cfg = CrashKitConfig.Builder()
            .setTelemetrySink { _, _ -> }
            .build()
        assertTrue(cfg.reporter is TelemetryCrashReporter)
        assertTrue(cfg.telemetrySink != null)
    }

    @Test
    fun configReporterWinsOverTelemetrySink() {
        val reporter = CrashReporter { _, _, cb -> cb.onResult(true) }
        val cfg = CrashKitConfig.Builder()
            .setReporter(reporter)
            .setTelemetrySink { _, _ -> }
            .build()
        assertEquals(reporter, cfg.reporter)
    }

    @Test
    fun anrExceptionIsCompactMainStack() {
        val dir = File(System.getProperty("java.io.tmpdir"), "crashkit-anr-e-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val main = File(dir, "main_stack.txt")
        main.writeText(
            """
            ----- main "main" state=TIMED_WAITING
              at java.lang.Thread.sleep(Native Method)
              at java.lang.Thread.sleep(Thread.java:443)
              at java.lang.Thread.sleep(Thread.java:359)
              at android.os.SystemClock.sleep(SystemClock.java:131)
              at com.crashkit.sample.DebugAnrService.onStartCommand(DebugAnrService.kt:13)
              at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:4956)
              at android.app.ActivityThread.handleMessage(ActivityThread.java:1426)
              at android.os.Handler.dispatchMessage(Handler.java:102)
              at android.os.Looper.loop(Looper.java:148)
              at android.app.ActivityThread.main(ActivityThread.java:5443)
              at java.lang.reflect.Method.invoke(Native Method)
              at com.android.internal.os.ZygoteInit.run(ZygoteInit.java:728)
              at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:618)
            """.trimIndent(),
        )
        try {
            val record = CrashRecord(
                "anr-e",
                CrashType.ANR_CRASH,
                """{"exception":"ANR"}""",
                listOf(main),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            assertTrue(payload.exception.contains("----- main \"main\" state=TIMED_WAITING"))
            assertTrue(payload.exception.contains("DebugAnrService.onStartCommand"))
            assertTrue(payload.exception.contains("fw"))
            assertFalse(payload.exception.startsWith("ANR"))
            assertTrue(payload.wireText.contains("DebugAnrService.onStartCommand"))
            assertTrue(payload.wireText.length <= CrashTelemetry.MAX_CHARS)
        } finally {
            main.delete()
            dir.delete()
        }
    }

    @Test
    fun anrTelemetryIncludesTracesAndLastMainSample() {
        val dir = File(System.getProperty("java.io.tmpdir"), "crashkit-anr-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val main = File(dir, "main_stack.txt")
        val traces = File(dir, "traces.txt")
        main.writeText(
            """
            ----- pid 1 01-01 00:00:00.000
            Cmd line: com.example.app
             tid=1 
            at android.os.MessageQueue.nativePollOnce(Native Method)
            ----- end 1
            ----- pid 1 01-01 00:00:01.000
            Cmd line: com.example.app
             tid=1 
            at com.example.app.LiveActivity.onCreate(LiveActivity.java:42)
            at android.os.Looper.loop(Looper.java:1)
            ----- end 1
            """.trimIndent(),
        )
        traces.writeText(
            """
            ----- pid 1 at 01-01 00:00:00
            Cmd line: com.example.app
            "main" prio=5 tid=1 Native
              #0 pc 0xabc  /data/app/liblive.so (blockOnMain)
              #1 pc 0xdef  /system/lib64/libc.so
            maps:
            70f00000-71000000 r-xp /system/lib64/libart.so
            """.trimIndent(),
        )
        try {
            val record = CrashRecord(
                "anr-1",
                CrashType.ANR_CRASH,
                """{"exception":"ANR"}""",
                listOf(main, traces),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            assertTrue(payload.wireText.length <= CrashTelemetry.MAX_CHARS)
            assertEquals(CrashType.ANR_CRASH, payload.type)
            assertTrue(payload.stack.contains("liblive.so"))
            assertTrue(payload.stack.contains("LiveActivity"))
            assertTrue(payload.wireText.contains("liblive.so"))
            assertFalse("stale sampler block should be dropped", payload.stack.contains("nativePollOnce"))
            assertFalse("maps must stay out of telemetry", payload.stack.contains("libart.so"))
        } finally {
            main.delete()
            traces.delete()
            dir.delete()
        }
    }

    @Test
    fun anrTelemetryKeepsBlockedWorkerDropsIdlePark() {
        val dir = File(System.getProperty("java.io.tmpdir"), "crashkit-anr-threads-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val main = File(dir, "main_stack.txt")
        val threads = File(dir, "threads.txt")
        main.writeText(
            """
            ----- main "main" state=BLOCKED
              at com.example.app.Pref.save(Pref.java:12)
              at java.lang.Object.wait(Native Method)
            """.trimIndent(),
        )
        threads.writeText(
            """
            java_threads=4 blocked=1 waiting=1 runnable=1
            heap_used=10 heap_max=20
            index:
            "main" state=BLOCKED
            "okhttp" state=WAITING
            "db" state=BLOCKED
            ----- "okhttp" tid=8 state=WAITING
              at java.util.concurrent.locks.LockSupport.park(Native Method)
              at okhttp3.ConnectionPool.wait(ConnectionPool.java:1)
            ----- "db" tid=9 state=BLOCKED
              at com.example.app.UserDao.insert(UserDao.java:40)
              at android.database.sqlite.SQLiteDatabase.insert(SQLiteDatabase.java:1)
            """.trimIndent(),
        )
        try {
            val record = CrashRecord(
                "anr-threads",
                CrashType.ANR_CRASH,
                """{"exception":"ANR"}""",
                listOf(main, threads),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            assertTrue(payload.wireText.length <= CrashTelemetry.MAX_CHARS)
            assertTrue(payload.stack.contains("Pref.save") || payload.stack.contains("main:"))
            assertTrue(payload.stack.contains("threads:"))
            assertTrue(payload.stack.contains("UserDao.insert"))
            assertFalse(payload.stack.contains("LockSupport.park"))
            assertTrue(payload.resource.contains("blocked=1"))
        } finally {
            main.delete()
            threads.delete()
            dir.delete()
        }
    }

    @Test
    fun anrEmptySamplerBracketsAreDropped() {
        val dir = File(System.getProperty("java.io.tmpdir"), "crashkit-anr-empty-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val main = File(dir, "main_stack.txt")
        val error = File(dir, "anr_error.log")
        main.writeText("[]")
        error.writeText(
            """
            AppFreeze! Input dispatching timed out
            "main" prio=5 tid=1 Blocked
              at com.example.app.SettingActivity.onCreate(SettingActivity.java:88)
              at android.os.Looper.loop(Looper.java:1)
            """.trimIndent(),
        )
        try {
            val record = CrashRecord(
                "anr-empty",
                CrashType.ANR_CRASH,
                """{"exception":"AppFreeze!"}""",
                listOf(main),
                listOf(error),
            )
            val payload = CrashTelemetry.of(record)
            assertTrue(payload.wireText.length <= CrashTelemetry.MAX_CHARS)
            assertFalse(payload.stack.contains("[]"))
            assertTrue(payload.stack.contains("SettingActivity"))
            assertTrue(payload.stack.contains("sys:"))
            assertEquals("AppFreeze!", payload.exception)
        } finally {
            main.delete()
            error.delete()
            dir.delete()
        }
    }

    @Test
    fun anrLiveJavaDumpPreferredOverWatchdogNativeTraces() {
        val dir = File(System.getProperty("java.io.tmpdir"), "crashkit-anr-live-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val main = File(dir, "main_stack.txt")
        val traces = File(dir, "traces.txt")
        main.writeText(
            """
            ----- main "main" state=RUNNABLE
              at com.example.app.DebugActivity.onClick(DebugActivity.java:17)
              at android.os.Looper.loop(Looper.java:1)
            ----- "OkHttp" tid=42 state=WAITING
              at java.lang.Object.wait(Native Method)
            """.trimIndent(),
        )
        traces.writeText(
            """
            *** CrashKit ANR traces ***
            pid: 1 tid: 99
            backtrace:
              #0 pc 0xabc  /data/app/libcrashkit.so (watchdog)
            """.trimIndent(),
        )
        try {
            val record = CrashRecord(
                "anr-live",
                CrashType.ANR_CRASH,
                """{"exception":"ANR"}""",
                listOf(main, traces),
                emptyList(),
            )
            val payload = CrashTelemetry.of(record)
            assertTrue(payload.stack.contains("DebugActivity"))
            assertTrue(payload.stack.contains("main:"))
            assertFalse(payload.stack.contains("libcrashkit.so"))
            assertFalse(payload.stack.contains("watchdog"))
        } finally {
            main.delete()
            traces.delete()
            dir.delete()
        }
    }

    private fun tempFile(name: String, content: String): File {
        val f = File(System.getProperty("java.io.tmpdir"), "crashkit-$name-${System.nanoTime()}")
        f.writeText(content)
        return f
    }

    private fun longJavaStack(frames: Int): String {
        val sb = StringBuilder()
        sb.append("java.lang.IllegalStateException: overflow\n")
        for (i in 0 until frames) {
            sb.append("\tat com.example.app.Foo.bar(Foo.java:").append(i).append(")\n")
            sb.append("\tat android.os.Looper.loop(Looper.java:1)\n")
        }
        sb.append("Caused by: java.lang.NullPointerException: inner\n")
        sb.append("\tat com.example.app.Foo.init(Foo.java:2)\n")
        return sb.toString()
    }
}
