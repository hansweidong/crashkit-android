package com.yj.crashkit.anr

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yj.crashkit.nativecrash.NativeCrashBridge
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SIGQUIT 旁路的真机回归测试。
 *
 * 守的是 `1.4.2` 修掉的那个 P0：SIGQUIT 必须在**主线程**上 UNBLOCK。内核派发进程定向信号
 * 时主线程优先，主线程还屏蔽着的话信号会被 ART 的 Signal Catcher 全部吃掉，旁路一次都不触发。
 * 这个 bug 在单测里看不见（没有真实信号），只能跑在设备上。
 */
@RunWith(AndroidJUnit4::class)
class SignalAnrBypassTest {

    @Test
    fun mainThreadUnblocksSigquit() {
        val blocked = sigBlkOf(Process.myPid())
        assertTrue("读不到主线程 SigBlk", blocked >= 0)
        assertEquals(
            "主线程仍屏蔽 SIGQUIT，旁路收不到信号（SigBlk=${java.lang.Long.toHexString(blocked)}）",
            0L,
            blocked and SIGQUIT_BIT,
        )
    }

    @Test
    fun sigquitReachesBypassNotOnlySignalCatcher() {
        val fired = CountDownLatch(1)
        NativeCrashBridge.setAnrSignalSink { fired.countDown() }
        try {
            Process.sendSignal(Process.myPid(), SIGQUIT)
            assertTrue(
                "SIGQUIT 没有落到 CrashKit 旁路，被 ART Signal Catcher 独吞了",
                fired.await(10, TimeUnit.SECONDS),
            )
        } finally {
            NativeCrashBridge.setAnrSignalSink(null)
        }
    }

    /**
     * Signal Catcher 停在 sigwait 上时，内核 do_sigtimedwait() 会把它等待中的信号从
     * blocked 里临时摘掉，所以它的 SigBlk **不含** SIGQUIT —— 旧实现按「含 SIGQUIT」
     * 去认它，方向是反的。
     *
     * 只在它真的停在 sigwait 上时成立：刚被叫醒、dump 完还没重新进去的那一小段里
     * 掩码是恢复的（本类的信号用例跑在前面时就会撞上）。native 侧对这段瞬态是靠
     * 「按线程名兜底」兜住的，这里给它时间回到稳态再断言。
     */
    @Test
    fun signalCatcherParksWithSigquitUnblocked() {
        val tid = findThread("Signal Catcher")
        assertTrue("没找到 Signal Catcher 线程", tid > 0)
        val deadline = SystemClock.uptimeMillis() + 5_000
        var blocked = sigBlkOf(tid)
        while (blocked and SIGQUIT_BIT != 0L && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(50)
            blocked = sigBlkOf(tid)
        }
        assertEquals(
            "Signal Catcher 始终没回到 sigwait（SigBlk 一直含 SIGQUIT）",
            0L,
            blocked and SIGQUIT_BIT,
        )
    }

    private fun findThread(name: String): Int {
        val dir = File("/proc/self/task")
        for (t in dir.listFiles().orEmpty()) {
            val comm = try {
                File(t, "comm").readText().trim()
            } catch (_: Throwable) {
                continue
            }
            if (comm == name) {
                return t.name.toIntOrNull() ?: continue
            }
        }
        return -1
    }

    private fun sigBlkOf(tid: Int): Long {
        val status = File("/proc/self/task/$tid/status")
        return try {
            for (line in status.readLines()) {
                if (line.startsWith("SigBlk:")) {
                    return java.lang.Long.parseUnsignedLong(line.substringAfter(':').trim(), 16)
                }
            }
            -1
        } catch (_: Throwable) {
            -1
        }
    }

    companion object {
        private const val SIGQUIT = 3

        /** SigBlk 里 signal n 占 bit n-1。 */
        private const val SIGQUIT_BIT = 1L shl (SIGQUIT - 1)

        @BeforeClass
        @JvmStatic
        fun installBypass() {
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val dumpDir = File(ctx.cacheDir, "crash-test").apply { mkdirs() }
            // 和线上一致：native 先 init，旁路必须在主线程上装
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                check(NativeCrashBridge.install(dumpDir.absolutePath, null)) { "nativeInit failed" }
                check(NativeCrashBridge.installAnrSignal()) { "installAnrSignal failed" }
            }
        }
    }
}
