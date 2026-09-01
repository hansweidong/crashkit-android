package com.yj.crashkit.anr

import android.app.ActivityManager

fun interface AnrListener {
    fun onANRDetected(errorState: ActivityManager.ProcessErrorStateInfo?)
}
