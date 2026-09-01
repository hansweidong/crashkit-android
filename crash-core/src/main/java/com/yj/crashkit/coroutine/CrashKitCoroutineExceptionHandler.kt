package com.yj.crashkit.coroutine

import com.yj.crashkit.CrashKit
import com.yj.crashkit.CrashType
import com.yj.crashkit.internal.CrashKitExceptionDeduper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * kotlinx 对根协程未处理异常会 ServiceLoader 发现本类，再视情况交给 UEH。
 * try/catch 或 async 未 await 的异常不会到这里。
 *
 * 必须保留无参构造，供 ServiceLoader 实例化。
 */
class CrashKitCoroutineExceptionHandler :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is CancellationException) {
            return
        }
        CrashKitExceptionDeduper.mark(exception)
        CrashKit.uploadCustomCrash(CrashType.JAVA_ERROR, exception)
    }
}
