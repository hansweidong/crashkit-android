package com.yj.crashkit.util

import android.util.Log

object KitLog {
    @Volatile
    private var logger: ILog = AndroidLog()

    fun setLogger(log: ILog?) {
        if (log != null) {
            logger = log
        }
    }

    fun i(tag: String, msg: String?) {
        logger.i(tag, msg.orEmpty())
    }

    fun e(tag: String, msg: String?) {
        logger.e(tag, msg.orEmpty(), null)
    }

    fun e(tag: String, msg: String?, t: Throwable?) {
        logger.e(tag, msg.orEmpty(), t)
    }

    interface ILog {
        fun i(tag: String, msg: String)
        fun e(tag: String, msg: String, t: Throwable?)
    }

    private class AndroidLog : ILog {
        override fun i(tag: String, msg: String) {
            Log.i(tag, msg)
        }

        override fun e(tag: String, msg: String, t: Throwable?) {
            if (t == null) {
                Log.e(tag, msg)
            } else {
                Log.e(tag, msg, t)
            }
        }
    }
}
