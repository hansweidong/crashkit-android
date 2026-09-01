-keep class com.yj.crashkit.nativecrash.NativeCrashBridge { *; }
-keep class com.yj.crashkit.resource.RecordInfo { *; }
-keep class com.yj.crashkit.coroutine.CrashKitCoroutineExceptionHandler { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
