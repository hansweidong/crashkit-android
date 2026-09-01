package com.yj.crashkit.internal

import java.util.concurrent.atomic.AtomicBoolean

class ReportGate {
    private val enabled = AtomicBoolean(true)

    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun isEnabled(): Boolean = enabled.get()
}
