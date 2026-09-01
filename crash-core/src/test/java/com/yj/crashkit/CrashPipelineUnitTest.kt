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
        assertFalse(policy.allowHprofDump(true, false))
        assertTrue(policy.allowHprofDump(true, true))
        assertEquals(0, policy.logcatLines(CrashType.JAVA_OOM, false))
        assertEquals(0, policy.logcatLines(CrashType.ANR_CRASH, false))
        assertEquals(500, policy.logcatLines(CrashType.JAVA_CRASH, false))
        assertEquals(200, policy.logcatLines(CrashType.JAVA_ERROR, false))
        assertEquals(2000, policy.logcatLines(CrashType.JAVA_CRASH, true))
        assertFalse(policy.shouldSampleMainThread(Long.MAX_VALUE))
        assertTrue(policy.shouldSampleMainThread(1000L))
        assertEquals(2000, policy.blockerWaitMs(CrashType.JAVA_CRASH, true))
        assertEquals(2000, policy.blockerWaitMs(CrashType.JAVA_OOM, false))
    }

    @Test
    fun oomLiteSnapshotHasCountsWithoutStacks() {
        val text = com.yj.crashkit.resource.RecordInfo.liteOomSnapshot()
        assertTrue(text.contains("heap_used="))
        assertTrue(text.contains("fd_count="))
        assertTrue(text.contains("task_count="))
        assertFalse(text.contains("  at "))
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
}
