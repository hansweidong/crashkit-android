# CrashKit

Android 崩溃 / ANR / OOM **采集** SDK（Kotlin + `libcrashkit.so`）。

- 仓库：https://github.com/hansweidong/crashkit-android.git
- 包名：`com.yj.crashkit`
- 版本：`1.4.5-SNAPSHOT`（调试覆盖同一坐标；正式发版关掉 `crashkit.snapshot`）
- `libcrashkit.so` 按 **16KB** 页对齐（`arm64-v8a` / `armeabi-v7a`）
- 无快手 KOOM / xhook
- **不含 HTTP 上报**。埋点宿主实现 `CrashTelemetrySink`；自建文件通道实现 `CrashReporter`

能力按包体拆成两档：**线上只走 `CrashKit`**；测试包额外 `CrashKitLab.enable()`。

---

## 代码架构

单模块 `crash-core`（Kotlin + `libcrashkit.so`）。公开面只有 `CrashKit` / `CrashKitConfig` / `CrashKitLab` 和 `log` 包的会话类型。

```
CrashKit 公开 API
    → 采集适配（JavaCrashHandler / NativeCrashBridge / AnrDetector / ExitInfoAnrCollector）
        → CrashPipeline（落盘 + pending）
            → TelemetryCrashReporter（只在 META 回调一次）
                → CrashKitLogCrashSink 组 JSON
                    → 宿主 CrashKitLogTransport.send（即 LogModel.submitCrashKitJson）
```

| 层 | 包 / 文件 | 职责 |
|---|---|---|
| 公开入口 | `CrashKit.kt` / `CrashKitConfig.kt` | `init`、`setCrashKitLogUpload`、`setTelemetrySink`、`setReporter`、`setCrashCallback`、`uploadCustomCrash` |
| 采集适配 | `JavaCrashHandler` / `NativeCrashBridge` + `crashkit_native.cpp` / `AnrDetector` / `ExitInfoAnrCollector` | 装 UEH（会 rewrap）、信号 handler 只做 async-signal-safe、SIGQUIT 旁路、冷启动 ExitInfo |
| 运行时 | `internal/CrashKitRuntime.kt` | dumpDir、进程名、reporter 延迟就绪、uid / ext / userLogList |
| 统一管线 | `internal/CrashPipeline.kt` | preCallback → 落盘 → crashCallback → pending.json → META → DUMP/LOGS → afterCallback → Blocker |
| 现场数据 | `DumpWriter` / `OomLite` / `MetaJson` / `MemSnapshot` / `CrashLogcat` / `PendingStore` | 栈与 meta 落盘；内存按崩溃现场写入；pending 成败由 reporter 回调决定 |
| 上报 SPI | `reporter/TelemetryCrashReporter.kt` | 只在 META 回调一次 sink；DUMP/LOGS 立刻成功。`AckSink` 返回 `false` 则保留 pending |
| CrashKit JSON | `log/CrashKitLogCrashSink.kt` + `CrashKitLogEvent.kt` + `CrashKitLogSession.kt` | 组采集 JSON；`log_type` / `subtype` / `behavior` 可由宿主覆盖，未传用 `CrashKitLogSession.Default`；`send(body)` 交给宿主 |
| 诊断隔离 | `CrashKitLab.kt` / `JavaOomMonitor` / `ResourceMonitor` / `MainThreadSampler` | 正式包默认关 |

宿主会调的 API 见下文「线上可开的开关」和「按 CrashKit LogModel 上报」。

---

## 两档对照

| | 线上正式包 | 测试 / 内部包 |
|---|---|---|
| 入口 | 只调 `CrashKit` | `CrashKit` + `CrashKitLab.enable()` |
| 默认策略 | 安全档，加重诊断被钳制或跳过 | 打开 Lab 后可用高频采样、inline 轮询、主动崩溃 |
| 切记 | 不要调用 `CrashKitLab.enable()` | 仅 `BuildConfig.DEBUG` 或内部渠道调用 |

未 `enable()` 时：主线程采样 `<200ms` 会被钳到 200ms；`inline=true` 不轮询；`CrashKitLab` 上的测试 API 直接 return。

hprof 采集**整体下线**，Lab 也拿不到：`Debug.dumpHprofData` 会 suspend 整个 VM 约 20s，几乎必然自己触发一次真 ANR。KOOM 的 `suspend/fork/resume` 子进程 dump 需要非平凡 native 与 ART 版本适配，本 SDK 不实现。方案取舍见 [`docs/anr-oom-collection-plan.md`](docs/anr-oom-collection-plan.md)。

---

## 线上支持的功能

`CrashKit.init` 之后即可用。这些是正式包应收的采集能力。

### 崩溃采集

| 类型 | 何时进管线 | 说明 |
|---|---|---|
| `JAVA_CRASH` | 线程未捕获异常 | init 后若宿主再装 UEH，会重新包到最外层再回调宿主 |
| `NATIVE_CRASH` | SIGSEGV / ABRT / BUS / FPE / ILL / TRAP | `libcrashkit.so` dump 后 JNI 进同一条管线 |
| `JAVA_ERROR` | `uploadCustomCrash`，或根协程未处理异常 | 协程 **try/catch 吃掉的、async 未 await 的不会上报** |
| `JAVA_OOM` | UEH 收到 `OutOfMemoryError`，或 `openJavaOom` 预检触发 | 现场走预分配缓冲落盘，不再申请内存；**从不** dump hprof |
| `ANR_CRASH` | `CrashKit.init` 之后 | 对齐 Matrix：SIGQUIT 旁路；队头超期、主线程非 Looper 空转、或 `NOT_RESPONDING` 后上报。现场抓主线程栈 + **全线程 Java 栈**（锁/Binder/后台卡死才能定位），埋点压进 `sys` / `main` / `threads`。**只采不杀** |
| `ANR_CRASH`（补报） | 下次冷启动，且宿主 reporter/sink 已注册 | API 30+ 读 `ApplicationExitInfo(REASON_ANR)`，补齐进程被系统直接杀掉、SIGQUIT 走不完的 fatal ANR。带系统完整 traces；和现场上报按时间戳互斥。**不触发 `CrashCallback` 三钩子**，只走 reporter/sink |

统一管线（顺序不可调）：`preCallback` → 落盘 → `crashCallback` → `pending/{id}.json` → META / DUMP / LOGS → `afterCallback` → Blocker。

**三段全部上报成功才删 `pending/{id}.json`**；任一段失败、或 reporter 不回调，记录就留在盘上，下次启动等宿主装好 reporter 后自动重投（只走 reporter/sink，不触发 `CrashCallback` 三钩子）。

### 怎样才算「一定送到后台」

投递结果由**宿主**告诉 SDK，SDK 按这个结果决定删不删 `pending`。三种接法的保证强度不同：

| 接法 | 能否重投 | 说明 |
|---|---|---|
| `setTelemetrySink(CrashTelemetrySink)` | **不能** | `onTelemetry` 没有返回值，SDK 只能一律记成功并删掉 pending |
| `setTelemetrySink(CrashTelemetryAckSink)` | 能 | 返回 `false` 即保留记录，下次冷启动自动重投 |
| `setReporter(CrashReporter)` | 能 | 上报失败时 `callback.onResult(false)` |

`true` 的含义是「**已确认送达，或已落到你自己的持久化队列**」。在内存入队时就返回 `true`，
进程随后被杀同样丢数据，而且 pending 已经被删了——这时 SDK 帮不了你。

ANR 的 `blockerWaitMs` 是 0（不等待）：ANR 不是 SDK 在杀进程，等待换不来任何安全边际。
真正的兜底是 pending 重投。API 30+ 还有 `ApplicationExitInfo` 补报，**API 24–29 没有**。

> `CrashKit.init` 请在**主线程**调用。ANR 旁路要在主线程解除 SIGQUIT 屏蔽，不在主线程时 SDK 会 post 回主线程，但会晚一个消息循环。

附件：`.dmp` 文本栈、Java/Native 崩溃时 `logcat -t 500`（OOM/ANR 不采 logcat）、OOM 只写计数快照、可选 `/proc`（显式打开 FD/Mem/Thread 时；全线程栈仅 Lab）、userLogList、Activity history。

dump 目录按进程隔离：主进程用 `cacheDir/crash`，子进程用 `cacheDir/crash/{进程段}`（`com.foo.app:push` → `push`）。`native_crash.dmp` / `anr_error.log` / `main_stack.txt` 是固定名，不分目录的话多进程会互相覆盖，`pending/` 也会串。

### 线上可开的开关

| API | 线上行为 |
|---|---|
| `startAnrDetecting(context, 200+)` | 可选。init 已开 SIGQUIT ANR 采集；本 API 只加主线程采样，间隔 **≥ 200ms** |
| `openFdInfo` / `openMemInfo` / `openThreadInfo` | 崩溃瞬间读 `/proc`；线上轻量（计数/线程名），全栈/`getPss` 仅 Lab |
| `openJavaOom(app)` | 5s 看堆占比 / FD / 线程 / VSS；堆判据要求**仍在上涨**（对齐 KOOM 的 gap 判据），连续 3 次命中才上报计数快照。上报前查磁盘余量，次数按版本落盘限次（3 次 / 15 天）；**不写 hprof** |
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
    setLogger(object : com.yj.crashkit.util.KitLog.ILog {
        override fun i(tag: String, msg: String) { /* MLog.info(tag, msg) */ }
        override fun e(tag: String, msg: String, t: Throwable?) { /* MLog.error(tag, msg, t) */ }
    })
    setTelemetrySink { payload, record ->
        // 必须在当前线程打日志/写埋点。不要 post 到主线程：ANR 时主线程已冻，日志会拖到进程被杀后才出现。
        hiidoExtra["crash"] = payload.wireText
    }
}
// ANR 已随 init 打开。若要采主线程栈：CrashKit.startAnrDetecting(context, 1000L)
```

`init` 之后宿主再 `setDefaultUncaughtExceptionHandler` 可以。CrashKit 会在当前 `onCreate` 消息结束时、以及后续 Activity 生命周期里重新包到最外层，先采集再回调宿主 handler。不要要求宿主删除自己的 UEH。

从旧 `CrashReport` 迁过来时，包名改成 `com.yj.crashkit`，其余尽量同名：`CrashKit.init(new CrashKit.CrashReportBuilder()...)`（不用 `.build()`）、`setANRListener` / `startANRDetecting` / `configCrashReport` / `openSignalReport` / `addExtraInfo` / `setAppVersion`。`ILog` 用 `KitLog.ILog`；`ANRDetector.ANRListener` 改成 `AnrListener`（不要建 `ANRDetector` 类，和 `AnrDetector` 文件名冲突）。`CatonChecker.getIns().start(53)` 必须先 `CrashKitLab.enable()`。

### 发布到本地 Maven

调试期 **不要升小版本号**。`crashkit.version` 固定，`crashkit.snapshot=true` 时产物永远是
`1.4.5-SNAPSHOT`，反复 `publishToLocalMaven` 只覆盖同一坐标。正式发版把 `crashkit.snapshot` 改成 `false`。

```bash
./gradlew publishToLocalMaven
```

产物：`~/.m2/repository/com/yj/crashkit/crash-core/1.4.5-SNAPSHOT/`。

宿主 `settings.gradle`：

```gradle
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

```gradle
implementation "com.yj.crashkit:crash-core:1.4.5-SNAPSHOT"
```

### 宿主如何拿到采集结果

SDK 只采集和落盘。上报有三条路，选一条（或 sink + CrashKit 上传同时开）：

| | 埋点扩展字段 | CrashKit 日志协议 | 自建 HTTP / 文件通道 |
|---|---|---|---|
| 注册 | `setTelemetrySink { ... }` | `setCrashKitLogUpload({ body -> hostSend(body) }) { CrashKitLogSession(...) }` | `setReporter { ... }` |
| 数据 | `CrashTelemetryPayload.wireText` | 对齐 `LogModel.submitCrash` 的 V3 JSON | `CrashRecord` |
| 地址 | 宿主自己的埋点 SDK | **宿主网络栈发送**，CrashKit 不开连接 | 宿主自己发 |

### 按 CrashKit LogModel 上报崩溃 / ANR

信封分类字段（`log_type` / `subtype` / `behavior`）可由宿主覆盖；未传时用 `CrashKitLogSession.Default`：

- data 带 `sdk`、`sdk_ver`、`crash_id`、`crash_type`（JAVA_CRASH / ANR_CRASH / …）
- data：`stack_trace`、`ext_data1-5`（类名 / message / cause / 首帧）、内存、`lan_id` / `sec_id`

`userId`、设备 id、`lanId` 每次上报时由宿主 lambda 现取。宿主 [CrashKitLogTransport] 返回 `false` 时 pending 下次启动重投。

CrashKit **不发起 HTTP**。明文 `/log/live-chat` 用 `bodyAsListWrapper = false`；走宿主 `submitLogV3` 加密网关时用 `bodyAsListWrapper = true`。

**CrashKit 现行两拍接入**（init 时 Koin / LogModel 还没起来，不能并成一次 `init`）：

| 步 | 工程文件 | 动作 |
|---|---|---|
| 0 | crashkit-android | `./gradlew publishToLocalMaven`（JDK 17），产物 `1.4.5-SNAPSHOT` |
| 1 | `settings.gradle.kts` | `dependencyResolutionManagement` 含 `mavenLocal()` |
| 2 | `appbase/build.gradle.kts` | `implementation("com.yj.crashkit:crash-core:1.4.5-SNAPSHOT")` |
| 3 | `IApplication.onCreate`（主线程） | `CrashKit.init { setAppId("your-app-id") }`，此时 reporter 是 NoOp，只落盘 |
| 4 | `CrashKitLogUpload.kt` | `setCrashKitLogUpload({ body -> logModel.submitCrashKitJson(body) }) { CrashKitLogSession(..., bodyAsListWrapper = true) }` |
| 5 | `AppInitBizTask.AfterLaunch` | `CrashKitLogUpload.install()`，随后 ExitInfo 补报 + pending 重投 |
| 6 | `LogModel.submitCrashKitJson` | `runBlocking` + `logService.submitLogV3`；走完 `tryGetData` 才返回 `true` |

```kotlin
// IApplication.onCreate（主线程）
CrashKit.init(this) {
    setAppId("your-app-id")
}

// AppInitBizTask.AfterLaunch：Koin / 登录态起来后再装
CrashKit.setCrashKitLogUpload({ body -> logModel.submitCrashKitJson(body) }) {
    CrashKitLogSession(
        pkg = effectivePkg,
        ver = versionName,
        deviceId = deviceId,
        userId = userId,
        lanId = lanId,
        secId = secId,
        logType = hostLogType,
        subtype = hostSubtype,
        crashBehavior = hostCrashBehavior,
        anrBehavior = hostAnrBehavior,
        bodyAsListWrapper = true,
    )
}
// 不覆盖分类字段时直接用本地默认：
// CrashKit.setCrashKitLogUpload({ body -> logModel.submitCrashKitJson(body) }) { CrashKitLogSession.Default }
```

---

`CrashTelemetryPayload` 里宿主直接能用的字段：

- `crashId` / `type` / `exception` / `stack`
- ANR 的 `stack` 含系统 `longMsg`（`sys:`）、系统 traces（`traces:`，仅 `ApplicationExitInfo` 补报有）以及主线程快照（`main:`）。弹窗出现时在 ANR dump 线程同步回调。SDK **不杀进程**；关闭后黑屏属系统/产品侧问题，不在采集职责内
- `wireText`：短键 JSON，≤ 9000 字符，**直接作为埋点扩展信息**
- `record.dumpFiles` / `record.logFiles`：仅本地排障，不要打进埋点

同时设置 `setReporter` 与 `setTelemetrySink` 时，以 `setReporter` 为准。init 之后仍可 `CrashKit.setTelemetrySink { ... }`——ANR 历史补报会挂起等到那一刻，不会被 `NoOpCrashReporter` 吃掉。

```kotlin
CrashKit.init(context) {
    setAppId("your-app-id")
    setTelemetrySink { payload, record ->
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
| `testJavaCrash()` / `testNativeCrash()` | 主动制造崩溃 | 绝不能进正式包逻辑 |

示例：

```kotlin
import com.yj.crashkit.CrashKit
import com.yj.crashkit.CrashKitLab

if (BuildConfig.DEBUG) {
    CrashKitLab.enable()
    CrashKitLab.startAggressiveAnrSampling()
}
```

---

## 明确不做

- 内置域名、密钥或加密网关（CrashKit 上传只发明文 JSON，地址由宿主设置）
- hprof 采集（含 KOOM 的 `suspend/fork/resume` 子进程 dump）与堆引用链分析
- 快手 KOOM / xhook / 运行时 PLT hook
- 拦截业务 `try/catch` 已消化的异常
- 未 `await` 的 `async` 异常

协程：根协程未处理异常由 `CrashKitCoroutineExceptionHandler`（ServiceLoader）自动进管线；`kotlinx-coroutines-core` 仅 `compileOnly`，不打进 AAR。
