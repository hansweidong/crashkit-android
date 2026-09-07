package com.yj.crashkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashKitKotlinTest {
    @Test
    fun crashTypesAreUsableFromKotlin() {
        assertEquals("JAVA_CRASH", CrashType.JAVA_CRASH.wireName())
        assertEquals("JAVA_OOM", CrashType.JAVA_OOM.wireName())
        assertTrue(CrashKit.VERSION.isNotEmpty())
        assertTrue(!CrashKitLab.isEnabled())
    }

    @Test
    fun coroutineHandlerIsServiceLoadable() {
        val type = Class.forName("com.yj.crashkit.coroutine.CrashKitCoroutineExceptionHandler")
        val instance = type.getDeclaredConstructor().newInstance()
        assertTrue(instance is kotlinx.coroutines.CoroutineExceptionHandler)
    }

    @Test
    fun kotlinDslHelpersExist() {
        val builder = CrashKitConfig.Builder().setAppId("demo").setGUid("guid")
        val config = builder.build()
        assertEquals("demo", config.appId)
        assertEquals("guid", config.guid)
    }

    @Test
    fun kitLogMethodsAreJavaStatic() {
        val i = com.yj.crashkit.util.KitLog::class.java.getMethod("i", String::class.java, String::class.java)
        val e = com.yj.crashkit.util.KitLog::class.java.getMethod(
            "e",
            String::class.java,
            String::class.java,
            Throwable::class.java,
        )
        assertTrue(java.lang.reflect.Modifier.isStatic(i.modifiers))
        assertTrue(java.lang.reflect.Modifier.isStatic(e.modifiers))
        val adapter = object : com.yj.crashkit.util.KitLog.ILog {
            override fun i(tag: String, msg: String) {}
            override fun e(tag: String, msg: String, throwable: Throwable?) {}
        }
        com.yj.crashkit.util.KitLog.setLogger(adapter)
        com.yj.crashkit.util.KitLog.i("KitLogTest", "ok")
        com.yj.crashkit.util.KitLog.e("KitLogTest", "err", null)
        com.yj.crashkit.util.KitLog.setLogger(null)
    }

    @Test
    fun crashReportAliasesExist() {
        assertTrue(
            CrashKitConfig.Builder::class.java
                .isAssignableFrom(CrashKit.CrashReportBuilder::class.java),
        )
        val methods = CrashKit::class.java.methods.map { it.name }.toSet()
        assertTrue(methods.contains("setANRListener"))
        assertTrue(methods.contains("startANRDetecting"))
        assertTrue(methods.contains("configCrashReport"))
        assertTrue(methods.contains("openSignalReport"))
        assertTrue(methods.contains("addExtraInfo"))
        assertTrue(methods.contains("setAppVersion"))
        val initBuilder = CrashKit::class.java.getMethod(
            "init",
            CrashKitConfig.Builder::class.java,
        )
        assertTrue(java.lang.reflect.Modifier.isStatic(initBuilder.modifiers))
    }

    @Test
    fun idleLooperStackIsNotAnrWork() {
        val idle = """
            ----- main "main" state=WAITING
              at android.os.MessageQueue.nativePollOnce(Native Method)
              at android.os.MessageQueue.next(MessageQueue.java:325)
              at android.os.Looper.loop(Looper.java:142)
              at android.app.ActivityThread.main(ActivityThread.java:6494)
        """.trimIndent()
        assertTrue(!com.yj.crashkit.anr.AnrJavaDump.isExecutingWork(idle))
    }

    @Test
    fun sleepOnMainIsAnrWork() {
        val stuck = """
            ----- main "main" state=TIMED_WAITING
              at java.lang.Thread.sleep(Native Method)
              at com.wigo.liveh5dev.DebugAnrService.onStartCommand(DebugAnrService.kt:13)
              at android.app.ActivityThread.handleServiceArgs(ActivityThread.java:3315)
              at android.os.Looper.loop(Looper.java:142)
        """.trimIndent()
        assertTrue(com.yj.crashkit.anr.AnrJavaDump.isExecutingWork(stuck))
    }

    @Test
    fun blockedWorkerIsInterestingForAnr() {
        assertTrue(
            com.yj.crashkit.anr.AnrJavaDump.isInterestingThread(
                "pool-1-thread-1",
                "BLOCKED",
                listOf("at com.example.app.UserDao.insert(UserDao.java:40)"),
            ),
        )
    }

    @Test
    fun parkedPoolThreadIsNotInterestingForAnr() {
        assertTrue(
            !com.yj.crashkit.anr.AnrJavaDump.isInterestingThread(
                "DefaultDispatcher-worker-2",
                "WAITING",
                listOf("at java.util.concurrent.locks.LockSupport.park(Native Method)"),
            ),
        )
    }
}
