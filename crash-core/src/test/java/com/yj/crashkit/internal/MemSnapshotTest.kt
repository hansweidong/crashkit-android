package com.yj.crashkit.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemSnapshotTest {
    @Test
    fun kbToMbKeepsTenth() {
        assertEquals("11.2", MemSnapshot.kbToMb(11_500L))
        assertEquals("1", MemSnapshot.kbToMb(1024L))
        assertEquals("", MemSnapshot.kbToMb(0L))
    }

    @Test
    fun bytesToMbMatchesOldUeHeapScale() {
        assertEquals("256", MemSnapshot.bytesToMb(256L * 1024L * 1024L))
        assertEquals("0.5", MemSnapshot.bytesToMb(512L * 1024L))
    }

    @Test
    fun parseOomLiteAndAnrHeader() {
        val oom = """
            heap_used=${90L * 1024 * 1024}
            heap_max=${100L * 1024 * 1024}
            heap_pct=90
            VmSize=3650000 kB
            VmRSS=420000 kB
            Threads=64
        """.trimIndent()
        val snap = MemSnapshot.parseCounters(oom)
        assertEquals("90", snap.javaUsedMb)
        assertEquals("100", snap.javaMaxMb)
        assertEquals("90", snap.heapPct)
        assertEquals("3650000", snap.vmSizeKb)
        assertEquals("420000", snap.vmRssKb)
        assertEquals("64", snap.threads)

        val anr = "java_threads=12 blocked=1 waiting=3 heap_used=${40L * 1024 * 1024} heap_max=${512L * 1024 * 1024} fd=80"
        val anrSnap = MemSnapshot.parseCounters(anr)
        assertEquals("12", anrSnap.threads)
        assertEquals("40", anrSnap.javaUsedMb)
        assertEquals("512", anrSnap.javaMaxMb)
        assertEquals("80", anrSnap.fd)
    }

    @Test
    fun fromMetaPrefersMetaOverResource() {
        val meta = mapOf(
            "crash_time_ms" to "1700000000000",
            "mem_total_mb" to "7800",
            "mem_java_used_mb" to "400",
            "mem_java_alloc_mb" to "420",
            "mem_pss_mb" to "510",
            "is_in_bg" to "1",
        )
        val snap = MemSnapshot.fromMeta(meta, "heap_used=1 heap_max=2")
        assertEquals(1700000000000L, snap.crashTimeMs)
        assertEquals("7800", snap.totalMb)
        assertEquals("400", snap.javaUsedMb)
        assertEquals("510", snap.pssMb)
        assertEquals(true, snap.inBg)
        assertEquals("510", MemSnapshot.usageMb(snap))
        val head = MemSnapshot.headerLine(snap)
        assertTrue(head.contains("ram=7800"))
        assertTrue(head.contains("pss=510MB"))
        assertTrue(head.startsWith("mem:"))
    }

    @Test
    fun usageFallsBackToJavaHeapWhenPssMissing() {
        val snap = MemSnapshot.fromMeta(
            mapOf("mem_java_used_mb" to "88"),
            "",
        )
        assertEquals("88", MemSnapshot.usageMb(snap))
    }
}
