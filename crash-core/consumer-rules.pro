-keep class com.yj.crashkit.nativecrash.NativeCrashBridge { *; }
-keep class com.yj.crashkit.resource.RecordInfo { *; }
-keep class com.yj.crashkit.coroutine.CrashKitCoroutineExceptionHandler { *; }
-keep class com.yj.crashkit.CrashCallback { *; }
-keep class com.yj.crashkit.util.KitLog { *; }
-keep interface com.yj.crashkit.util.KitLog$ILog { *; }
-keep class com.yj.crashkit.anr.CatonChecker { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
