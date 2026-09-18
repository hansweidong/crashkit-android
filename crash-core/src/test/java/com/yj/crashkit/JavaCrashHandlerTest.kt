package com.yj.crashkit

import com.yj.crashkit.internal.JavaCrashHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class JavaCrashHandlerTest {
    private var original: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        JavaCrashHandler.resetForTest()
    }

    @After
    fun tearDown() {
        JavaCrashHandler.resetForTest()
        Thread.setDefaultUncaughtExceptionHandler(original)
    }

    @Test
    fun persistThenForwardsToSystemHandler() {
        val steps = CopyOnWriteArrayList<String>()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> steps += "system" }
        val kit = JavaCrashHandler.installForTest { _, _ -> steps += "capture" }

        kit.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals(listOf("capture", "system"), steps.toList())
    }

    @Test
    fun hostWrapperForwardsWithoutLoop() {
        val reported = AtomicInteger()
        val hostCalls = AtomicInteger()
        val systemCalls = AtomicInteger()
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> systemCalls.incrementAndGet() }
        JavaCrashHandler.installForTest { _, _ -> reported.incrementAndGet() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val host = Thread.UncaughtExceptionHandler { thread, error ->
            hostCalls.incrementAndGet()
            previous?.uncaughtException(thread, error)
        }
        Thread.setDefaultUncaughtExceptionHandler(host)

        host.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals(1, hostCalls.get())
        assertEquals(1, reported.get())
        assertEquals(1, systemCalls.get())
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === host)
    }

    @Test
    fun doesNotStealOuterWhenHostInstallsAfter() {
        val kit = JavaCrashHandler.installForTest { _, _ -> }
        val host = Thread.UncaughtExceptionHandler { _, _ -> }
        Thread.setDefaultUncaughtExceptionHandler(host)
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === host)
        kit.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === host)
    }

    @Test
    fun skipReentrantWhenHostCallsBackIntoKit() {
        val reported = AtomicInteger()
        val hostCalls = AtomicInteger()
        val kit = JavaCrashHandler.installForTest { _, _ -> reported.incrementAndGet() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val host = Thread.UncaughtExceptionHandler { thread, error ->
            hostCalls.incrementAndGet()
            previous?.uncaughtException(thread, error)
        }
        Thread.setDefaultUncaughtExceptionHandler(host)

        kit.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals(1, reported.get())
        assertEquals(0, hostCalls.get())
    }

    @Test
    fun nullDefaultHandlerExitsProcess() {
        val exited = AtomicInteger()
        Thread.setDefaultUncaughtExceptionHandler(null)
        val kit = JavaCrashHandler.installForTest(
            { _, _ -> },
            { exited.incrementAndGet() },
        )
        kit.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals(1, exited.get())
    }
}
