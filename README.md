# CrashKit

Android 崩溃 / ANR / OOM **采集** SDK（Kotlin + `libcrashkit.so`）。

- 包名：`com.yj.crashkit`
- 版本：`1.1.7`
- `libcrashkit.so` 按 **16KB** 页对齐（`arm64-v8a` / `armeabi-v7a`）
- 无快手 KOOM / xhook
- **不含 HTTP 上报**。埋点宿主实现 `CrashTelemetrySink`；自建文件通道实现 `CrashReporter`

能力按包体拆成两档：**线上只走 `CrashKit`**；测试包额外 `CrashKitLab.enable()`。

---

## 两档对照

| | 线上正式包 | 测试 / 内部包 |
|---|---|---|
| 入口 | 只调 `CrashKit` | `CrashKit` + `CrashKitLab.enable()` |
| 默认策略 | 安全档，加重诊断被钳制或跳过 | 打开 Lab 后可用高频采样、hprof、主动崩溃 |
| 切记 | 不要调用 `CrashKitLab.enable()` | 仅 `BuildConfig.DEBUG` 或内部渠道调用 |

未 `enable()` 时：主线程采样 `<200ms` 会被钳到 200ms；`inline=true` 不轮询；`dumpHprof=true` 被忽略；`CrashKitLab` 上的测试 API 直接 return。

---

## 线上支持的功能

`CrashKit.init` 之后即可用。这些是正式包应收的采集能力。

### 崩溃采集

| 类型 | 何时进管线 | 说明 |
|---|---|---|
| `JAVA_CRASH` | 线程未捕获异常 | init 后若宿主再装 UEH，会重新包到最外层再回调宿主 |
| `NATIVE_CRASH` | SIGSEGV / ABRT / BUS / FPE / ILL / TRAP | `libcrashkit.so` dump 后 JNI 进同一条管线 |
| `JAVA_ERROR` | `uploadCustomCrash`，或根协程未处理异常 | 协程 **try/catch 吃掉的、async 未 await 的不会上报** |
| `JAVA_OOM` | UEH 收到 `OutOfMemoryError`，或 `openJavaOom` 预检触发 | 线上预检**不会** dump hprof |
| `ANR_CRASH` | `CrashKit.init` 之后 | 1s 轮询 `getProcessesInErrorState`；系统 SIGQUIT 落到 `traces.txt` 则附带。不在检测线程上自发 SIGQUIT |

统一管线（顺序不可调）：`preCallback` → 落盘 → `crashCallback` → `pending/{id}.json` → META / DUMP / LOGS → `afterCallback` → Blocker。

附件：`.dmp` 文本栈、Java/Native 崩溃时 `logcat -t 500`（OOM/ANR 不采 logcat）、OOM 只写计数快照、可选 `/proc`（显式打开 FD/Mem/Thread 时；全线程栈仅 Lab）、userLogList、Activity history。

### 线上可开的开关

| API | 线上行为 |
|---|---|
| `startAnrDetecting(context, 200+)` | 可选。init 已开 AM poll；本 API 只加主线程采样，间隔 **≥ 200ms** |
| `openFdInfo` / `openMemInfo` / `openThreadInfo` | 崩溃瞬间读 `/proc`；线上轻量（计数/线程名），全栈/`getPss` 仅 Lab |
| `openJavaOom(app, false)` 或 `dumpHprof=true` 但未开 Lab | 15s 看堆占比 / FD / 线程，连续 3 次超阈值上报；**不写 hprof** |
| `setTelemetrySink` | **埋点推荐入口**。采集后同步回调一次 `CrashTelemetryPayload`（`wireText` ≤ 9000） |
| `setReporter` / `TelemetryCrashReporter` | 高级三阶段 META/DUMP/LOGS；埋点不必自己实现 |
| `setCrashCallback` / `setAnrListener` | 三钩子、ANR 通知 |
| `setUid` / `setExtInfo` / `setUserLogList` | 写入 META |
| `setReportEnabled(false)` | 只关上报，不卸 UEH |

### 线上接入示例

```kotlin
import com.yj.crashkit.CrashKit

CrashKit.init(context) {
    setAppId("your-app-id")
    setGUid("guid")
    setTelemetrySink { payload, record ->
        hiidoExtra["crash"] = payload.wireText
    }
}
// ANR 已随 init 打开。若要采主线程栈：CrashKit.startAnrDetecting(context, 1000L)
```

`init` 之后宿主再 `setDefaultUncaughtExceptionHandler` 可以。CrashKit 会在当前 `onCreate` 消息结束时、以及后续 Activity 生命周期里重新包到最外层，先采集再回调宿主 handler。不要要求宿主删除自己的 UEH。

### 宿主如何拿到采集结果

SDK 只采集和落盘，**不会联网**。给宿主的方式就两条，选一条：

| | 埋点扩展字段（当前接入） | 自建 HTTP / 文件通道 |
|---|---|---|
| 注册 | `setTelemetrySink { payload, record -> }` | `setReporter { record, stage, cb -> }` |
| 底层 | SDK 内部安装 `TelemetryCrashReporter` | 宿主自己实现 `CrashReporter` |
| 回调次数 | 每个 crash/ANR/OOM **一次** | META、DUMP、LOGS **三次**，每次必须 `cb.onResult` |
| 线程 | 崩溃线程或 ANR 检测线程，同步 | 同上 |
| 数据 | `CrashTelemetryPayload`：结构化字段 + `wireText` | `CrashRecord`（含本地文件路径） |
| 不要做 | 把 dump/logcat 拼进埋点 | 漏调 `onResult`（Blocker 会堵住崩溃线程） |

`CrashTelemetryPayload` 里宿主直接能用的字段：

- `crashId` / `type` / `exception` / `stack`
- ANR 的 `stack` 含 `anr_error.log`（华为 AppFreeze longMsg）、当场 Java 主线程栈，以及系统 traces（去 maps）。不把空的 `[]` 采样结果写进埋点
- `wireText`：短键 JSON，≤ 9000 字符，**直接作为埋点扩展信息**
- `record.dumpFiles` / `record.logFiles`：仅本地排障，不要打进埋点

同时设置 `setReporter` 与 `setTelemetrySink` 时，以 `setReporter` 为准。init 之后仍可 `CrashKit.setTelemetrySink { ... }`。

```kotlin
CrashKit.init(context) {
    setAppId("your-app-id")
    setTelemetrySink { payload, record ->
        // payload.wireText.length <= CrashTelemetry.MAX_CHARS
        hiidoExtra["crash"] = payload.wireText
    }
}
```

---

## 测试包才有的功能

必须先：

```kotlin
import com.yj.crashkit.CrashKitLab

if (BuildConfig.DEBUG) {
    CrashKitLab.enable()
}
```

正式包不要调用 `enable()`。

| API | 作用 | 为何隔离 |
|---|---|---|
| `startAggressiveAnrSampling()` | 默认 53ms 采主线程栈 | ART safepoint，会抖 UI |
| `openFdInline` / `openMemInline` / `openThreadInline` | 每 15s `getPss` + 全线程栈 + 写盘 | 稳态 CPU / 卡顿 |
| `openJavaOomDumpHprof(app)` | 超阈值 `Debug.dumpHprofData` | 暂停整个 VM |
| `testJavaCrash()` / `testNativeCrash()` | 主动制造崩溃 | 绝不能进正式包逻辑 |

示例：

```kotlin
import com.yj.crashkit.CrashKit
import com.yj.crashkit.CrashKitLab

if (BuildConfig.DEBUG) {
    CrashKitLab.enable()
    CrashKitLab.startAggressiveAnrSampling()
    CrashKitLab.openJavaOomDumpHprof(application)
}
```

---

## 明确不做

- 内置崩溃后台、加密上传
- 快手 KOOM / xhook / 运行时 PLT hook
- 拦截业务 `try/catch` 已消化的异常
- 未 `await` 的 `async` 异常

协程：根协程未处理异常由 `CrashKitCoroutineExceptionHandler`（ServiceLoader）自动进管线；`kotlinx-coroutines-core` 仅 `compileOnly`，不打进 AAR。
