package com.yj.crashkit

import com.yj.crashkit.internal.CrashBlocker
import com.yj.crashkit.internal.ReportGate
import com.yj.crashkit.util.StackTraceFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CrashPipelineUnitTest {
    @Test
    fun stackKeepsClassMessageAndCause() {
        val cause = IllegalStateException("inner")
        val t = RuntimeException("outer", cause)
        val text = StackTraceFormatter.fromThrowable(t)
        assertTrue(text.contains("RuntimeException"))
        assertTrue(text.contains("outer"))
        assertTrue(text.contains("IllegalStateException"))
        assertTrue(text.contains("inner"))
        assertTrue(text.contains("Caused by:"))
    }

    @Test
    fun reportGateDefaultsOn() {
        val gate = ReportGate()
        assertTrue(gate.isEnabled())
        gate.setEnabled(false)
        assertFalse(gate.isEnabled())
    }

    @Test
    fun blockerUnblocksAfterThreeCallbacks() {
        val blocker = CrashBlocker()
        blocker.preBlock(3)
        val done = AtomicInteger()
        val latch = CountDownLatch(1)
        val waiter = Thread {
            blocker.waitForUnblock(2000)
            done.incrementAndGet()
            latch.countDown()
        }
        waiter.start()
        blocker.unblock()
        blocker.unblock()
        assertEquals(0, done.get())
        blocker.unblock()
        assertTrue(latch.await(1, TimeUnit.SECONDS))
        assertEquals(1, done.get())
    }

    @Test
    fun crashTypeWireNames() {
        assertEquals("JAVA_CRASH", CrashType.JAVA_CRASH.wireName())
        assertEquals("NATIVE_CRASH", CrashType.NATIVE_CRASH.wireName())
        assertEquals("ANR_CRASH", CrashType.ANR_CRASH.wireName())
        assertEquals("JAVA_OOM", CrashType.JAVA_OOM.wireName())
    }

    @Test
    fun exceptionDeduperMatchesCauseChain() {
        val inner = IllegalStateException("inner")
        val outer = RuntimeException("outer", inner)
        com.yj.crashkit.internal.CrashKitExceptionDeduper.mark(outer)
        assertTrue(com.yj.crashkit.internal.CrashKitExceptionDeduper.wasMarked(outer))
        assertTrue(com.yj.crashkit.internal.CrashKitExceptionDeduper.wasMarked(inner))
        assertFalse(
            com.yj.crashkit.internal.CrashKitExceptionDeduper.wasMarked(RuntimeException("other")),
        )
    }

    @Test
    fun onlinePolicyClampsFastSamplingWhenLabOff() {
        val policy = com.yj.crashkit.internal.CrashKitOnlinePolicy
        assertEquals(200L, policy.sampleInterval(53L, false))
        assertEquals(53L, policy.sampleInterval(53L, true))
        assertEquals(Long.MAX_VALUE, policy.sampleInterval(Long.MAX_VALUE, false))
        assertEquals(1000L, policy.sampleInterval(5L, false))
        assertFalse(policy.allowInlinePolling(true, false))
        assertTrue(policy.allowInlinePolling(true, true))
        assertEquals(0, policy.logcatLines(CrashType.JAVA_OOM, false))
        assertEquals(0, policy.logcatLines(CrashType.ANR_CRASH, false))
        assertEquals(500, policy.logcatLines(CrashType.JAVA_CRASH, false))
        assertEquals(200, policy.logcatLines(CrashType.JAVA_ERROR, false))
        assertEquals(2000, policy.logcatLines(CrashType.JAVA_CRASH, true))
        assertFalse(policy.shouldSampleMainThread(Long.MAX_VALUE))
        assertTrue(policy.shouldSampleMainThread(1000L))
        assertEquals(2000, policy.blockerWaitMs(CrashType.JAVA_CRASH, true))
        assertEquals(2000, policy.blockerWaitMs(CrashType.JAVA_OOM, false))
        assertEquals(0, policy.blockerWaitMs(CrashType.ANR_CRASH, false))
        assertEquals(-2000L, policy.ANR_FOREGROUND_MSG_THRESHOLD_MS)
        assertEquals(40, policy.ANR_CHECK_ERROR_COUNT)
    }

    @Test
    fun anrMainQueueOverdueMatchesMatrixThreshold() {
        assertTrue(com.yj.crashkit.anr.AnrMainQueue.isOverdue(-2500L, true))
        assertFalse(com.yj.crashkit.anr.AnrMainQueue.isOverdue(-1500L, true))
        assertTrue(com.yj.crashkit.anr.AnrMainQueue.isOverdue(-11000L, false))
        assertFalse(com.yj.crashkit.anr.AnrMainQueue.isOverdue(-5000L, false))
    }

    @Test
    fun anrMatcherRequiresSamePidAndNotResponding() {
        val state = android.app.ActivityManager.ProcessErrorStateInfo()
        state.pid = 1
        state.processName = "com.example.app"
        state.condition = android.app.ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING
        assertTrue(com.yj.crashkit.anr.AnrDetector.isAnr(state, 1, "com.example.app"))
        state.pid = 999
        assertFalse(com.yj.crashkit.anr.AnrDetector.isAnr(state, 1, "com.example.app"))
        state.pid = 1
        state.condition = android.app.ActivityManager.ProcessErrorStateInfo.CRASHED
        assertFalse(com.yj.crashkit.anr.AnrDetector.isAnr(state, 1, "com.example.app"))
    }

    @Test
    fun oomLiteSnapshotHasCountsWithoutStacks() {
        val text = com.yj.crashkit.resource.RecordInfo.liteOomSnapshot()
        assertTrue(text.contains("heap_used="))
        assertTrue(text.contains("heap_max="))
        assertTrue(text.contains("heap_pct="))
        assertFalse(text.contains("  at "))
    }

    /** OOM 现场的 dump 走预分配缓冲，仍要带上异常链和计数。 */
    @Test
    fun oomLiteWritesStackAndCounters() {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "crashkit-oomlite-${System.nanoTime()}",
        )
        assertTrue(dir.mkdirs())
        try {
            val boom = OutOfMemoryError("Failed to allocate 8MB")
            val dump = com.yj.crashkit.internal.OomLite.writeStack(dir, "cid-1", boom)
            val text = dump.readText()
            assertTrue(text.contains("java.lang.OutOfMemoryError"))
            assertTrue(text.contains("Failed to allocate 8MB"))
            assertTrue(text.contains("  at "))
            assertTrue(text.contains("heap_used="))

            val counters = com.yj.crashkit.internal.OomLite.writeCounters(dir)
            assertEquals("oom_lite.txt", counters.name)
            assertTrue(counters.readText().contains("heap_max="))
            assertFalse(counters.readText().contains("  at "))
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    /** 对齐 KOOM：占比要处于上涨阶段才算命中，回落即不计。 */
    @Test
    fun oomHeapJudgeRequiresRisingRatio() {
        val monitor = com.yj.crashkit.oom.JavaOomMonitor
        assertTrue(monitor.heapRising(0.90f, 0f))
        assertTrue(monitor.heapRising(0.92f, 0.90f))
        assertTrue(monitor.heapRising(0.88f, 0.90f))
        assertFalse(monitor.heapRising(0.80f, 0.90f))
    }

    @Test
    fun oomVssJudgeIgnoresUnknownAndSmallVss() {
        val monitor = com.yj.crashkit.oom.JavaOomMonitor
        assertFalse(monitor.vssOverLimit(-1L))
        assertFalse(monitor.vssOverLimit(1_000_000L))
        assertTrue(monitor.vssOverLimit(3_800_000L))
    }

    /** 限次要落盘：换版本或过期才重置，进程重启不清零。 */
    @Test
    fun reportQuotaLimitsPerVersionAndResetsOnNewVersion() {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "crashkit-quota-${System.nanoTime()}",
        )
        assertTrue(dir.mkdirs())
        val file = java.io.File(dir, "quota.txt")
        try {
            val now = 1_000_000L
            val quota = com.yj.crashkit.internal.ReportQuota(file, 2, 1000L)
            assertTrue(quota.tryAcquire("1.0.0", now))
            assertTrue(quota.tryAcquire("1.0.0", now))
            assertFalse(quota.tryAcquire("1.0.0", now))

            // 换版本重置
            assertTrue(quota.tryAcquire("1.0.1", now))
            // 新进程读同一份落盘状态，计数不清零
            val reborn = com.yj.crashkit.internal.ReportQuota(file, 2, 1000L)
            assertTrue(reborn.tryAcquire("1.0.1", now))
            assertFalse(reborn.tryAcquire("1.0.1", now))
            // 过期重置
            assertTrue(reborn.tryAcquire("1.0.1", now + 2000L))
        } finally {
            file.delete()
            dir.delete()
        }
    }

    /** ApplicationExitInfo 补报要和现场上报互斥，避免同一次 ANR 打两遍。 */
    @Test
    fun anrMarkCoversExitInfoWithinWindow() {
        val mark = com.yj.crashkit.anr.AnrReportMark
        val marks = listOf(1_000_000L)
        assertTrue(mark.coveredBy(marks, 1_000_000L))
        assertTrue(mark.coveredBy(marks, 1_030_000L))
        assertFalse(mark.coveredBy(marks, 1_120_000L))
        assertFalse(mark.coveredBy(emptyList(), 1_000_000L))
    }

    @Test
    fun anrMarkKeepsRecentTimestamps() {
        val dir = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "crashkit-anrmark-${System.nanoTime()}",
        )
        assertTrue(dir.mkdirs())
        try {
            for (i in 1..12) {
                com.yj.crashkit.anr.AnrReportMark.mark(dir, i.toLong())
            }
            val marks = com.yj.crashkit.anr.AnrReportMark.read(dir)
            assertEquals(8, marks.size)
            assertEquals(12L, marks.last())
        } finally {
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    /** dump 目录按进程分段，段名要能安全当目录名。 */
    @Test
    fun processNameSegmentIsFilesystemSafe() {
        val p = com.yj.crashkit.internal.ProcessName
        assertEquals("push", p.sanitize("push"))
        assertEquals("web_view", p.sanitize("web view"))
        assertEquals("a_b", p.sanitize("a/b"))
        assertEquals("p", p.sanitize(""))
        assertEquals("p", p.sanitize("..."))
    }

    @Test
    fun pendingStorePrunesOldAndExcessFiles() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "crashkit-pending-test-${System.nanoTime()}")
        val pending = java.io.File(dir, "pending")
        assertTrue(pending.mkdirs())
        try {
            val old = java.io.File(pending, "old.json")
            old.writeText("{}")
            old.setLastModified(1L)
            for (i in 0 until 25) {
                val f = java.io.File(pending, "n$i.json")
                f.writeText("{}")
                f.setLastModified(System.currentTimeMillis())
            }
            com.yj.crashkit.internal.PendingStore.prune(dir)
            val left = pending.listFiles()?.size ?: 0
            assertTrue(left <= 20)
            assertFalse(old.exists())
        } finally {
            pending.listFiles()?.forEach { it.delete() }
            pending.delete()
            dir.delete()
        }
    }

    /**
     * pending 记录要能写进去再读回来，并且能按 crashId 删掉。
     * `1.4.2` 之前只有 save 没有 loadAll / remove，落盘的记录永远发不出去也清不掉。
     */
    @Test
    fun pendingStoreRoundTripsAndRemoves() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "crashkit-pending-rt-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val dump = java.io.File(dir, "main_stack.txt").apply { writeText("----- main\n  at Foo.bar\n") }
        val gone = java.io.File(dir, "already-pruned.txt")
        try {
            val store = com.yj.crashkit.internal.PendingStore
            store.save(
                dir,
                CrashRecord("id-1", CrashType.ANR_CRASH, "{\"report_id\":\"id-1\"}", listOf(dump), listOf(gone)),
            )

            val loaded = store.loadAll(dir)
            assertEquals(1, loaded.size)
            assertEquals("id-1", loaded[0].crashId)
            assertEquals(CrashType.ANR_CRASH, loaded[0].type)
            assertTrue(loaded[0].metaJson.contains("id-1"))
            assertEquals(listOf(dump), loaded[0].dumpFiles)
            // 附件可能已被 prune 清掉，读回来时要跳过不存在的
            assertTrue(loaded[0].logFiles.isEmpty())

            store.remove(dir, "id-1")
            assertTrue(store.loadAll(dir).isEmpty())
        } finally {
            java.io.File(dir, "pending").listFiles()?.forEach { it.delete() }
            java.io.File(dir, "pending").delete()
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    /**
     * `waitForUnblock(0)` 必须立刻返回。`Object.wait(0)` 是无限等，而 ANR 的
     * `blockerWaitMs` 正好是 0 —— 宿主 reporter 异步时会把 ANR dump 线程永久挂住。
     */
    @Test
    fun blockerZeroWaitReturnsImmediately() {
        val blocker = com.yj.crashkit.internal.CrashBlocker()
        blocker.preBlock(3) // 三段都没 unblock，模拟异步 reporter 还没回调
        val start = System.currentTimeMillis()
        assertTrue(blocker.waitForUnblock(0))
        assertTrue("waitForUnblock(0) 阻塞了", System.currentTimeMillis() - start < 1000)
    }

    /**
     * 普通 sink 给不出结果只能记成功；ack sink 返回 false 要如实传给 pipeline，
     * 否则 pending 会被删掉，重投兜底就没了。
     */
    @Test
    fun telemetryAckSinkPropagatesFailure() {
        val record = CrashRecord("ack-1", CrashType.ANR_CRASH, "{}", emptyList(), emptyList())

        val plainResults = ArrayList<Boolean>()
        com.yj.crashkit.reporter.TelemetryCrashReporter { _, _ -> }
            .report(record, ReportStage.META) { plainResults.add(it) }
        assertEquals(listOf(true), plainResults)

        val failing = ArrayList<Boolean>()
        val ackSink = com.yj.crashkit.CrashTelemetryAckSink { _, _ -> false }
        com.yj.crashkit.reporter.TelemetryCrashReporter(ackSink)
            .report(record, ReportStage.META) { failing.add(it) }
        assertEquals(listOf(false), failing)

        val ok = ArrayList<Boolean>()
        val okSink = com.yj.crashkit.CrashTelemetryAckSink { _, _ -> true }
        com.yj.crashkit.reporter.TelemetryCrashReporter(okSink)
            .report(record, ReportStage.META) { ok.add(it) }
        assertEquals(listOf(true), ok)
    }

    /** 坏掉的 json 不能一直占着重投名额。 */
    @Test
    fun pendingStoreDropsUnparsableRecords() {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "crashkit-pending-bad-${System.nanoTime()}")
        val pending = java.io.File(dir, "pending")
        assertTrue(pending.mkdirs())
        try {
            val bad = java.io.File(pending, "bad.json").apply { writeText("not json at all") }
            assertTrue(com.yj.crashkit.internal.PendingStore.loadAll(dir).isEmpty())
            assertFalse(bad.exists())
        } finally {
            pending.listFiles()?.forEach { it.delete() }
            pending.delete()
            dir.delete()
        }
    }
}
