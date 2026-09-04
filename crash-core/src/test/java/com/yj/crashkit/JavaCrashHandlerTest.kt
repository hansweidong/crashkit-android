package com.yj.crashkit

import com.yj.crashkit.internal.JavaCrashHandler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    fun ensureOuterRewrapsHostHandlerAndReportsFirst() {
        val reported = AtomicInteger()
        val hostCalls = AtomicInteger()
        val kit = JavaCrashHandler.installForTest { _, _ -> reported.incrementAndGet() }
        val host = Thread.UncaughtExceptionHandler { _, _ -> hostCalls.incrementAndGet() }
        Thread.setDefaultUncaughtExceptionHandler(host)
        kit.rewrapIfNeeded()

        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === kit)
        kit.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals(1, reported.get())
        assertEquals(1, hostCalls.get())
    }

    @Test
    fun hostCallingPreviousDoesNotLoop() {
        val reported = AtomicInteger()
        val hostCalls = AtomicInteger()
        val kit = JavaCrashHandler.installForTest { _, _ -> reported.incrementAndGet() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val host = Thread.UncaughtExceptionHandler { thread, error ->
            hostCalls.incrementAndGet()
            previous?.uncaughtException(thread, error)
        }
        Thread.setDefaultUncaughtExceptionHandler(host)
        kit.rewrapIfNeeded()

        kit.uncaughtException(Thread.currentThread(), RuntimeException("boom"))
        assertEquals(1, reported.get())
        assertEquals(1, hostCalls.get())
    }

    @Test
    fun ensureOuterIsNoopWhenAlreadyOuter() {
        val kit = JavaCrashHandler.installForTest { _, _ -> }
        kit.rewrapIfNeeded()
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === kit)
        kit.rewrapIfNeeded()
        assertTrue(Thread.getDefaultUncaughtExceptionHandler() === kit)
    }
}
