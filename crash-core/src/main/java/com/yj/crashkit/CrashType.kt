package com.yj.crashkit

enum class CrashType(val value: Int) {
    JAVA_CRASH(0),
    NATIVE_CRASH(1),
    ANR_CRASH(2),
    JAVA_ERROR(3),
    JAVA_OOM(4);

    fun wireName(): String = name
}
