package com.yj.crashkit.history

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.yj.crashkit.internal.JavaCrashHandler
import java.util.ArrayList

/** 记录前后台 Activity，写入崩溃 JSON 的 history 字段。 */
class ActivityTracker private constructor() : Application.ActivityLifecycleCallbacks {
    private val spans = ArrayList<ActivityHistoryFormat.Span>()
    private var started = 0
    @Volatile var isForeground: Boolean = false
        private set

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
    }

    @Synchronized
    fun getHistory(): String {
        return ActivityHistoryFormat.render(spans)
    }

    @Synchronized
    private fun push(activity: Activity, letter: Char) {
        ActivityHistoryFormat.absorb(spans, activity.javaClass.simpleName, letter)
        while (spans.size > MAX_PAGES) {
            spans.removeAt(0)
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        JavaCrashHandler.ensureOuter()
        push(activity, 'C')
    }

    override fun onActivityStarted(activity: Activity) {
        JavaCrashHandler.ensureOuter()
        started++
        isForeground = started > 0
        push(activity, 'S')
    }

    override fun onActivityResumed(activity: Activity) {
        push(activity, 'R')
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        started = maxOf(0, started - 1)
        isForeground = started > 0
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        push(activity, 'D')
    }

    companion object {
        private const val MAX_PAGES = 20
        private val INSTANCE = ActivityTracker()

        @JvmStatic
        fun get(): ActivityTracker = INSTANCE
    }
}
