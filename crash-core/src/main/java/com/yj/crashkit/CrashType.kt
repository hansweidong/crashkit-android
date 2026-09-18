package com.yj.crashkit

enum class CrashType(val value: Int) {
    JAVA_CRASH(0),
    NATIVE_CRASH(1),
    ANR_CRASH(2),
    JAVA_ERROR(3),
    JAVA_OOM(4);

    fun wireName(): String = name

    /** Java / Native / OOM 未捕获崩溃会杀进程；ANR 和自定义错误进程还活着。 */
    fun isFatal(): Boolean {
        return this == JAVA_CRASH || this == NATIVE_CRASH || this == JAVA_OOM
    }
}
