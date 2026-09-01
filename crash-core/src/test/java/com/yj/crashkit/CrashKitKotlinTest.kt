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
}
