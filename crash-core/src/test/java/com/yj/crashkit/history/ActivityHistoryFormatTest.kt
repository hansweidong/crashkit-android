package com.yj.crashkit.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityHistoryFormatTest {
    @Test
    fun collapsesAdjacentLifecycleOfTheSamePage() {
        assertEquals(
            "SettingActivity(R)-DebugActivity(C:S:R)",
            ActivityHistoryFormat.compact(
                "R:SettingActivity>C:DebugActivity>S:DebugActivity>R:DebugActivity",
            ),
        )
    }

    @Test
    fun splitsWhenAnotherPageIsInBetween() {
        assertEquals(
            "A(C:S:R)-B(C)-A(D)-B(S:R)",
            ActivityHistoryFormat.compact(
                "C:A -> S:A -> R:A -> C:B -> D:A -> S:B -> R:B",
            ),
        )
    }

    @Test
    fun collapsesLegacyArrowSeparatedEvents() {
        assertEquals(
            "Splash(C:S:R)-Main(C:R)",
            ActivityHistoryFormat.compact(
                "C:Splash -> S:Splash -> R:Splash -> C:Main -> R:Main",
            ),
        )
    }

    @Test
    fun compactIsIdempotent() {
        val compact = "A(C:S:R)-B(C)-A(D)-B(S:R)"
        assertEquals(compact, ActivityHistoryFormat.compact(compact))
    }

    @Test
    fun fromTopStartsAtCurrentPage() {
        assertEquals(
            "DebugActivity(C:S:R)-SettingActivity(C:S:R)-SplashActivity(D)-MainActivity(C:S:R)-SplashActivity(C:S:R)",
            ActivityHistoryFormat.fromTop(
                "SplashActivity(C:S:R)-MainActivity(C:S:R)-SplashActivity(D)-SettingActivity(C:S:R)-DebugActivity(C:S:R)",
            ),
        )
    }

    @Test
    fun fromTopDropsOldestPagesInsteadOfClippingCurrentName() {
        val out = ActivityHistoryFormat.fromTop(
            "SplashActivity(C:S:R)-MainActivity(C:S:R)-SplashActivity(D)-SettingActivity(C:S:R)-DebugActivity(C:S:R)",
            maxPages = 6,
            maxChars = 96,
        )
        assertEquals(
            "DebugActivity(C:S:R)-SettingActivity(C:S:R)-SplashActivity(D)-MainActivity(C:S:R)",
            out,
        )
        assertTrue(out.startsWith("DebugActivity("))
        assertFalse(out.contains("…"))
    }
}
