package com.yj.crashkit.history

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.ArrayDeque

/** 记录前后台 Activity，写入崩溃 JSON 的 history 字段。 */
class ActivityTracker private constructor() : Application.ActivityLifecycleCallbacks {
    private val history = ArrayDeque<String>()
    private var started = 0
    @Volatile var isForeground: Boolean = false
        private set

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    @Synchronized
    fun getHistory(): String {
        val sb = StringBuilder()
        for (s in history) {
            if (sb.isNotEmpty()) {
                sb.append(" -> ")
            }
            sb.append(s)
        }
        return sb.toString()
    }

    @Synchronized
    private fun push(event: String) {
        history.addLast(event)
        while (history.size > MAX) {
            history.removeFirst()
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        push("C:" + activity.javaClass.simpleName)
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        isForeground = started > 0
        push("S:" + activity.javaClass.simpleName)
    }

    override fun onActivityResumed(activity: Activity) {
        push("R:" + activity.javaClass.simpleName)
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        isForeground = started > 0
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        push("D:" + activity.javaClass.simpleName)
    }

    companion object {
        private const val MAX = 20
        private val INSTANCE = ActivityTracker()

        @JvmStatic
        fun get(): ActivityTracker = INSTANCE
    }
}
