# CrashKit 采集方案梳理（对齐 Matrix + KOOM）

> 状态：**已全部落地于 `1.4.0`**，`1.4.1` 修补历史补报的两处缺陷（第 6 节），
> `1.4.2` 修掉真机实测暴露出的四个采集缺陷（第 7 节），`1.4.5` 修掉确认空转被杀丢现场（第 8 节）。
> 现行（`1.4.5-SNAPSHOT` 工作区）：pending **不再**随 `init` 自动重投，改由宿主 `CrashKit.retryPending()`；
> `init` 必须在主线程且每进程只成功一次；Activity 历史上报为 `Page(C:S:R)`（第 9 节）。
> 评审基线为 `1.3.7`。
> 决策已定项：hprof 采集在线上与 Lab **全部下线**，OOM 只保留计数快照，不实现 fork dump。
>
> 下面第 2 节保留评审时的问题清单（诊断依据），第 5 节记录实际落地方式与两处偏离。

---

## 1. 两条基线的分工

| | Tencent Matrix | Kwai KOOM |
|---|---|---|
| 覆盖目标 | ANR / 主线程卡顿的**采集与确认** | Java 内存泄漏 / OOM 的**镜像采集** |
| 核心手法 | SIGQUIT 旁路 → 尽快交回 Signal Catcher；`MessageQueue` 队头 + `ProcessErrorStateInfo` 双重确认 | 阈值轮询触发 → `suspend VM / fork / resume VM` 子进程 dump hprof |
| 不可违反的硬约束 | 信号处理路径必须极轻、绝不阻塞系统 dump | dump 绝不能冻结主进程 20s |
| 对进程生命周期的态度 | **只采不杀** | **只采不杀** |

CrashKit 现状：ANR 已经对齐 Matrix 的**判定逻辑**（1.3.7 完成），但**信号层实现细节不安全**；OOM 只搬了 KOOM 的「轮询阈值」外壳，真正值钱的 fork dump 与触发收敛都缺失。

---

## 2. 问题清单

优先级口径：P0 = 会挂死或功能实际不可用；P1 = 漏报或大量无效上报；P2 = 稳定性与数据质量。

### P0-1　signal handler 内调用 `pthread_create`

- 位置：`crash-core/src/main/cpp/crashkit_native.cpp:386`（`anr_handler`）→ `:399`
- 问题：`pthread_create` 内部会 `malloc`，**不是 async-signal-safe**。ANR 发生时主线程若正卡在 malloc 锁上，信号上下文里再申请堆内存可能直接死锁，把一次可恢复的 ANR 变成进程僵死。
- 基线做法：Matrix / xCrash 在**初始化阶段预创建**处理线程，handler 内只做 `sem_post`（POSIX 明确列为 async-signal-safe）。
- 修复方向：init 时创建一个常驻线程阻塞在 `sem_wait`；`anr_handler` 内只 `sem_post` + 写原子标记后立即返回。
- 附带收益：现有 `anr_listen_main`（`:409`）只为「让本线程 UNBLOCK SIGQUIT」而常驻 `pause()`，可直接复用为这个 sem 等待线程，线程总数不增加。

### P0-2　SIGQUIT 交回 Signal Catcher 过晚

`crash-core/src/main/cpp/crashkit_native.cpp:380-384`

```cpp
void* anr_callback(void*) {
    call_java_void("onAnrSignal");
    send_sigquit_to_signal_catcher();
    return nullptr;
}
```

- Java 侧 `AnrDetector.onAnrSignal` 会等快照 latch，上限 `ANR_SIGNAL_CAPTURE_WAIT_MS = 500ms`（`AnrDetector.kt:82`，`CrashKitOnlinePolicy.kt:24`）。
- 后果：系统 traces 落盘、ANR 弹窗被整体推迟约 500ms。**我们并不 hook 系统写 traces 的过程**，没有任何理由让自己的快照挡在前面。
- 修复方向：调换顺序——先 `send_sigquit_to_signal_catcher()` 交回，再异步做主线程快照。快照晚 500ms 对数据质量无影响，但系统链路不再被拖慢。

### P0-3　`Debug.dumpHprofData` 冻结整个 VM 约 20 秒

- 位置：`crash-core/src/main/java/com/yj/crashkit/oom/JavaOomMonitor.kt:144`
- 这正是 KOOM 存在的理由。即便当前只在 `CrashKitLab` 开启，20s 冻结几乎必然自己触发一次真 ANR，属于「采集动作制造事故」。
- **决策：直接下线。** 线上与 Lab 均不再调用 `Debug.dumpHprofData`，OOM 只产出计数快照（heap / fd / thread / VSS 文本）。不实现 fork dump——`suspend/fork/resume` + hprof strip 需要非平凡 native 且要做 ART 版本适配，收益不足以支撑当前工程量。
- 连带清理：`dumpHprof` 开关、`CrashKitOnlinePolicy.allowHprofDump`、`MAX_DUMPS` 的语义都需要重新定义为「快照次数」而非「hprof 次数」。

### P0-4　OOM 现场走完整上报管线

- 路径：UEH 收 `OutOfMemoryError` → `CrashPipeline.handleJava` → 拼 `MetaJson` / `StringBuilder` / 起 logcat 子进程。
- 问题：**堆已耗尽时继续大量分配**，极易二次 OOM，或者拿到一份被截断的无效数据。
- 基线做法：Matrix / KOOM 对 OOM 都走独立轻量路径，关键缓冲在 init 阶段预分配。
- 修复方向：`CrashType.JAVA_OOM` 单独分支，只输出预分配 buffer 内的定长文本（heap used/max、fd、thread、VSS、栈顶若干帧），跳过 logcat 抓取与完整 JSON 组装。

### P1-1　缺 KOOM 的「内存仍在上升」判据

`crash-core/src/main/java/com/yj/crashkit/oom/JavaOomMonitor.kt:118-121`

```kotlin
        val parts = ArrayList<String>()
        if (ratio >= HEAP_RATIO) {
            parts.add("heap=${"%.2f".format(ratio)} used=$used max=$max")
        }
```

- 现状：仅「`ratio >= 0.85` 连续 3 次」（`HEAP_RATIO` `:24`，`OVER_NEEDED` `:27`）。
- KOOM 判据：`heapRatio > threshold && heapRatio >= lastHeapRatio - HEAP_RATIO_THRESHOLD_GAP`（gap ≈ 0.05），即**必须处于持续上涨阶段**，一旦回落就把计数清零。
- 后果：当前实现会把「稳定高位但无泄漏」的大内存应用大量误判为 OOM 风险。
- 修复方向：记录 `lastHeapRatio`，加入 gap 判据。

### P1-2　dump 次数限制不持久化

- `dumpCount`（`JavaOomMonitor.kt:36`）是纯内存变量，进程重启即清零。
- KOOM：`analysisMaxTimesPerVersion`（默认 3）+ `analysisPeriodPerVersion`（默认 15 天），按**版本号**落盘限次。
- 后果：一台持续高内存的设备可能每次冷启都触发一次采集上报。
- 修复方向：按 `versionName` 落盘计数与首次时间戳，超限或过期后静默停采。

### P1-3　采集前不检查剩余磁盘空间

- KOOM 有明确的 available-space 前置判断。即使 hprof 下线，快照与 pending 队列仍会占盘。
- 修复方向：`report()` 入口前检查 `dumpDir` 所在分区可用空间，低于阈值直接跳过并记一条日志。

### P1-4　缺 VSS 阈值

- `overReason()`（`:111`）只看 heap ratio / fd / thread。
- 32 位进程地址空间耗尽型 OOM（`mmap` 失败、`pthread_create` 失败）完全看不到；KOOM 有 `setVssSizeThreshold`。
- 修复方向：读 `/proc/self/status` 的 `VmSize` 加入判据。

### P1-5　轮询间隔过粗

- `LOOP_MS = 15_000`（`:22`），KOOM 默认 5s。
- 后果：突发型内存增长可能整段落在两次采样之间被漏掉。
- 修复方向：降到 5s。配合 P1-1 的 gap 判据，误报不会因此上升。

### P1-6　缺 `ApplicationExitInfo` 兜底（API 30+）

- 现状：进程被系统直接杀掉、SIGQUIT 路径没跑完的 fatal ANR 是**纯漏报**。
- 业界做法：下次冷启动读 `ActivityManager.getHistoricalProcessExitReasons`，筛 `REASON_ANR`。这也是唯一能和 Play Console ANR 口径对齐的途径。
- 修复方向：init 后异步读取一次，按 `timestamp` 去重（避免与 SIGQUIT 路径重复上报同一次 ANR），生成 `CrashType.ANR_CRASH` 记录。

### P2-1　多进程 dump 文件名冲突

- dump 目录默认 `cacheDir/crash`，**全进程共用**。
- 固定文件名：native `native_crash.dmp`（`crashkit_native.cpp:135`、`:242`）、ANR `anr_error.log`（`AnrDetector.kt:148`）、`main_stack.txt`（`:165`）。`pending/` 队列同样共享。
- 后果：多进程同时出问题会互相覆盖。`native_crash.dmp` 虽然事后由 `CrashPipeline.renameDump` 改成 `{crashId}.dmp`，但从 native 写入到 Java 改名之间存在窗口。
- **注意耦合**：`TelemetryCompact` 是按**字面文件名精确匹配**取文件的——

`crash-core/src/main/java/com/yj/crashkit/internal/TelemetryCompact.kt:469-476`

```kotlin
    private fun named(files: List<File>, name: String): File? {
        for (f in files) {
            if (f.name == name && f.exists()) {
                return f
            }
        }
        return null
    }
```

  所以文件名一旦带上 pid，`findNamed(record, "anr_error.log")`（`:77`、`:363`）会全部失配。做进程隔离时必须同步改成后缀匹配，或改为在 `CrashRecord` 里显式标记文件角色（更推荐后者，去掉这层按名字猜语义的耦合）。
- 修复方向：dump 目录按 `cacheDir/crash/{processName}` 隔离，配合上面的角色标记改造。

### P2-2　`signal_catcher_tid()` 的 SigBlk 判断可疑

- 位置：`crashkit_native.cpp:356`，`if (sigblk == 0x1000UL)`。
- `/proc/*/status` 的 `SigBlk` 中，signal *n* 对应 bit *n-1*。SIGQUIT = 3 → bit 2 → `0x4`。而 `0x1000` 是 bit 12，对应 signal 13（SIGPIPE）。
- 现在没出事是因为末尾有 `return matched >= 0 ? matched : first;`，靠 comm 名匹配的 `first` 兜底，属于**潜在坑**。
- 修复方向：以 `comm == "Signal Catcher"` 为主判据；SigBlk 若保留则改为按位测试 `(sigblk & (1UL << (SIGQUIT - 1))) != 0`。

### P2-3　采样器周期漂移

- `AbstractSampler` 用 `Handler.postDelayed` 自循环（`AbstractSampler.kt:11-18`），每轮把 `doSample()` 的耗时叠加进间隔，长时间运行后采样时间轴不准，影响 ANR 上报里「卡死前栈演化」的时间对齐。
- 修复方向：改为基于 `SystemClock.uptimeMillis()` 的固定节拍补偿。

---

## 3. 收敛后的目标形态

### ANR

保留 1.3.7 已完成的 Matrix 判定逻辑（队头超期 / 本进程 `NOT_RESPONDING` 双重确认、单次上报、**只采不杀**），只补信号安全：

1. init 预创建线程阻塞 `sem_wait`；handler 内只 `sem_post`
2. **先** tgkill 交回 Signal Catcher，**再**做主线程快照
3. Signal Catcher 定位以 comm 为主，SigBlk 改按位
4. 新增 `ApplicationExitInfo(REASON_ANR)` 冷启动补报，与 SIGQUIT 路径按时间戳互斥

### OOM

按 KOOM 重做触发侧，采集侧降级为纯快照：

- 触发：5s 轮询 + 上涨 gap 判据 + VSS 阈值 + 按版本持久化限次 + 磁盘空间前置检查
- 采集：**不做 hprof**，只输出定长计数快照
- 上报：`JAVA_OOM` 走预分配缓冲的轻量路径，不起 logcat、不组装完整 JSON

### 明确不做

- fork dump / hprof strip（工程量与 ART 适配成本过高）
- 任何形式的进程终止、任务栈干预、拉起拦截（1.3.7 已移除 `AnrRelaunchGuard`，不再回退）
- 自己写 `traces.txt`（交由系统 Signal Catcher）

---

## 4. 落地顺序

| 批次 | 内容 | 风险 |
|---|---|---|
| 1 | P0-3 / P0-4：hprof 下线 + OOM 轻量路径 | 低，纯删减与分支 |
| 2 | P0-1 / P0-2：信号层 sem 握手 + 交回顺序 | 中，需真机 ANR 复现验证 |
| 3 | P1 全量：触发收敛 + `ApplicationExitInfo` | 中，需灰度观察上报量变化 |
| 4 | P2 全量：多进程隔离（含 `TelemetryCompact` 解耦）+ SigBlk + 采样节拍 | 中，改动面较宽 |

---

## 5. `1.4.0` 实际落地记录

四个批次一次性完成，`lintRelease` 与 `testReleaseUnitTest` 均通过。

### 新增文件

| 文件 | 职责 |
|---|---|
| `internal/OomLite.kt` | OOM 现场落盘：init 预分配 StringBuilder / 输出 ByteArray / `/proc` 读缓冲，手写 UTF-8 编码 |
| `internal/ProcStatus.kt` | 读一次 `/proc/self/status` 取 VSS / RSS / 线程数 |
| `internal/ReportQuota.kt` | 按版本落盘的上报限次，对应 KOOM `analysisMaxTimesPerVersion` |
| `internal/ProcessName.kt` | 进程名解析与 dump 目录分段 |
| `internal/CrashFiles.kt` | dump 文件名常量，跨 Kotlin / C++ 的唯一出处 |
| `anr/ExitInfoAnrCollector.kt` | `ApplicationExitInfo(REASON_ANR)` 冷启动补报 |
| `anr/AnrReportMark.kt` | 现场上报的时间戳标记，供补报去重 |

### 逐条对应

| 编号 | 落地方式 |
|---|---|
| P0-1 | `install_anr_signal` 里 `sem_init` + 建监听线程后才装 handler；`anr_handler` 只做 `tgkill` + `sem_post`，`pthread_create` 从信号路径彻底移除 |
| P0-2 | `anr_handler` 内先 `tgkill` 交回 Signal Catcher；`AnrDetector.onAnrSignal` 不再用 `CountDownLatch` 等快照，`ANR_SIGNAL_CAPTURE_WAIT_MS` 随之删除 |
| P0-3 | `JavaOomMonitor` 重写，`Debug.dumpHprofData` 与 `dumpHprof` 分支全部移除；`CrashKitOnlinePolicy.allowHprofDump` 删除；`CrashKitLab.openJavaOomDumpHprof` 标 `@Deprecated` 并降级为计数快照 |
| P0-4 | `CrashPipeline.handleJava` 在 `JAVA_OOM` 分支改走 `OomLite.writeStack`；`RecordInfo.dumpForCrash` 的 OOM 分支改走 `OomLite.writeCounters` |
| P1-1 | `JavaOomMonitor.heapRising`，`OOM_HEAP_RATIO_GAP = 0.05f` |
| P1-2 | `ReportQuota`，3 次 / 15 天，落盘在 `dumpDir/oom_quota.txt` |
| P1-3 | `report()` 里 `dumpDir.usableSpace` 前置检查，阈值 16MB |
| P1-4 | `JavaOomMonitor.vssOverLimit`，阈值 3_650_000 KB，`Process.is64Bit()` 为真时跳过 |
| P1-5 | `OOM_LOOP_INTERVAL_MS = 5_000L` |
| P1-6 | `ExitInfoAnrCollector` + `AnrReportMark`，60s 互斥窗口，游标落盘在 `dumpDir/exitinfo_cursor.txt`；管线侧用独立的 `exitInfoOnce` 闸，不占用现场 ANR 的 `anrOnce` |
| P2-1 | `CrashKitRuntime` 按 `ProcessName.dirSegment` 分目录。主进程留在原目录，升级后旧 `pending/` 仍可读 |
| P2-2 | `signal_catcher_tid()` 改按位判 SigBlk（**方向在 `1.4.2` 又反转了一次，见 7.2**） |
| P2-3 | `AbstractSampler` 改 `postAtTime` + `SystemClock.uptimeMillis()` 绝对时刻 |

### 两处偏离评审稿

**1. ANR 仍是一个专用线程，不是「监听 + worker」两个。**

评审稿设想「复用 `pause()` 线程做 `sem_wait`」。实际做法更省：监听线程把 `pause()` 换成 `sem_wait`。SIGQUIT 在该线程 UNBLOCK，所以 handler 就在它上面执行；handler 返回后 `sem_wait` 因 `EINTR` 重入一次即拿到令牌，此时已回到普通线程上下文，才做 JNI。相比 1.3.7 的「常驻监听线程 + 每次 ANR `pthread_create` 一个」，线程数从 2 降到 1。

**2. 文件名保持不带 pid，只做目录隔离 + 常量收敛。**

评审稿提了「文件名带 pid」和「`CrashRecord` 显式标记文件角色」两个方向。目录隔离已经消除了冲突，加 pid 反而要改 `TelemetryCompact` 的全部取用点；把角色改成 `CrashRecord` 字段则要动公开 API 与 `PendingStore` 序列化格式，收益只是可读性。最终只把散落的字面名收进 `CrashFiles`，C++ 侧加 `kNativeCrashDump` 常量并注明须与 Kotlin 侧一致。

### 顺手清掉的两处隐患

- `MetaJson.applicationProcessName` 里有个 `TODO("VERSION.SDK_INT < P")`，在 API 28 以下会抛 `NotImplementedError`（被外层 `catch` 吞掉，静默返回空进程名）。改为读 `CrashKitRuntime.processName`
- `CrashKitOnlinePolicy.blockerWaitMs` 里 `SDK_INT < 22` 的分支在 minSdk 24 下恒不成立，lint `ObsoleteSdkInt` 已报警，直接去掉

### 仍未覆盖

- **hprof 与堆引用链分析**：按决策不做。OOM 只能定位到「谁触发」，定不到「谁泄漏」
- **`ApplicationExitInfo` 只覆盖 API 30+**，24–29 的 fatal ANR 仍是漏报
- **`AnrMainQueue` 反射 `MessageQueue.mMessages`**：lint `DiscouragedPrivateApi` 警告，与 Matrix 同源风险，无公开替代
- ~~**P0-1 / P0-2 需真机复现验证**~~：已在 `1.4.2` 补上，见第 7 节。这一条当初没做就发版，直接导致 `1.4.0` / `1.4.1` 两个版本的 ANR 采集全程是坏的

---

## 6. `1.4.1` 修补：历史补报的两处缺陷

`1.4.0` 上线后现场反馈「ANR 之后重启没有任何采集通知」。定位到两个缺陷，都在 `ExitInfoAnrCollector`。

### 6.1 游标先于上报推进 → 永久丢数据

原实现在选出待报条目之后、`report()` 之前就 `writeCursor(maxTs)`。只要这一次上报没成功，
这条 ANR 既不会被本次上报，也不会被下次启动重试，直接永久丢失。

修法：游标只在 `report()` 返回成功之后才推进。`handleHistoricalAnr` 改为返回 `Boolean`
（被 `exitInfoOnce` 或 `ReportGate` 拦下时返回 `false`），失败就保留原游标等下次启动。

### 6.2 与宿主注册 sink 的竞态

`ExitInfoAnrCollector.start` 原来在 `CrashKit.init` 里立刻起线程。宿主如果不是在 init 的配置块里
`setTelemetrySink`，而是 init 之后调 `CrashKit.setTelemetrySink(...)`，那一刻 reporter 还是
`NoOpCrashReporter`——补报走完整条管线却没人收，再叠加 6.1 就是彻底丢失。

修法：`CrashKitRuntime.whenReporterReady(action)`。reporter 已是真实实现就立即执行，
否则挂起，等 `setReporter` / `setTelemetrySink` 装上非 `NoOpCrashReporter` 时再触发。
宿主始终不注册的话，补报就一直不跑，游标也不推，下次启动继续等——不丢数据。

### 6.3 历史补报不再重放宿主 `CrashCallback`

`preCrashCallback` / `crashCallback` / `afterCrashCallback` 的语义是「此刻正在崩溃」，宿主实现里
可能有收尾、结束页面、甚至主动结束进程的动作。历史补报是上一次进程的记录，在**冷启动阶段**重放
这三个钩子会干扰宿主的启动流程——这也是排查「重启黑屏」时必须先排除的一条路径。

`runPipeline` 新增 `invokeHostCallbacks` 参数，`handleHistoricalAnr` 传 `false`：
历史补报只走 reporter / telemetrySink，不碰宿主的崩溃钩子。

### 6.4 关于「弹窗关闭应用后重启黑屏」

结论：**不是 CrashKit 造成的**，`1.3.7` 的「只采不杀」改造之后 SDK 已经不碰进程生命周期和任务栈。

> 这一节原先猜的是「AMS 保留任务栈、重启恢复到深层 Activity 跳过启动页」。
> 真机日志否掉了这个猜测，真实原因见 7.5：**重启后又发生了一次广播 ANR**。

---

## 7. `1.4.2`：真机实测暴露的四个采集缺陷

`1.4.0` / `1.4.1` 发出去之后现场反馈「ANR 之后没有任何采集通知」。这次不再靠读代码推断，
直接在连着的设备上验证。测试机 **HUAWEI SEA-AL00 / Android 10 (API 29)**，接入方 `xyz.kenterk.test.android`。

### 7.0 先纠正一条：`1.4.1` 的修复在这台设备上根本不生效

`ExitInfoAnrCollector` 需要 `ApplicationExitInfo`（API 30+），这台设备是 API 29，`start()` 直接
return。所以第 6 节修的两个缺陷虽然本身是对的，**和现场看到的现象无关**。
现象只可能出在现场 SIGQUIT 这条路径上。

### 7.1 SIGQUIT 从未落到旁路（P0，根因）

实测：用应用自己的 uid 发 SIGQUIT，日志里只有 ART 的 Signal Catcher 响应，CrashKit 零日志。

```
$ adb shell run-as <pkg> kill -3 19807
Thread[7,tid=19818,WaitingInMainSignalCatcherLoop,...,"Signal Catcher"]: reacting to signal 3
Wrote stack traces to tombstoned
（没有任何 AnrDetector / NativeCrashBridge 日志）
```

原因：全文件唯一的 `pthread_sigmask(SIG_UNBLOCK)` 在 `anr_listen_main` 里，**主线程从没解除过
SIGQUIT 屏蔽**。内核 `complete_signal()` 派发进程定向信号时主线程优先：

```c
/* If the main thread wants the signal, it gets first crack. */
if (wants_signal(sig, p))          /* p 为线程组组长 = 主线程 */
    t = p;
else { t = signal->curr_target; while (!wants_signal(sig, t)) t = next_thread(t); }
```

主线程还屏蔽着，就退到轮询分支，而 Signal Catcher 此刻停在 `sigwait` 上（`blocked` 里没有
SIGQUIT，`wants_signal` 为真），于是它先被选中，旁路一次都不触发。

Matrix 的 `AnrDumper` 构造函数里就是在主线程做这一步，注释写得很直白：

```cpp
// must unblock SIGQUIT, otherwise the signal handler can not capture SIGQUIT
pthread_sigmask(SIG_UNBLOCK, &sigSet, &old_sigSet);
```

修法：`install_anr_signal()` 在装好 handler、起好工作线程之后，在**调用线程**上
`pthread_sigmask(SIG_UNBLOCK, {SIGQUIT})`；`AnrDetector.start()` 负责保证这一步落在主线程
（宿主不在主线程 init 就 post 回去）。handler 因此跑在主线程上，里面只有 `tgkill` + `sem_post`，
都是 async-signal-safe。

顺带修掉一个顺序隐患：原来先起线程（线程内自己 UNBLOCK）再装 handler，中间那个窗口里
来一发 SIGQUIT 就是默认动作 terminate + core。改成先装 handler 再起线程。

### 7.2 `signal_catcher_tid()` 的 SigBlk 判定方向是反的（P0 隐患）

实测同一进程的线程掩码：

```
tid=19818  Signal Catcher   SigBlk=0000000000001000   ← 不含 SIGQUIT
tid=19875  AnrWatchDog      SigBlk=0000000080001204   ← 含 SIGQUIT
```

原因：线程停在 `sigwait` / `rt_sigtimedwait` 时，内核 `do_sigtimedwait()` 会执行
`sigandnsets(&tsk->blocked, &tsk->blocked, &mask)`，把它**正在等待的**信号从 blocked 里临时摘掉。
所以真正在等 SIGQUIT 的那个线程，SigBlk 反而不含 SIGQUIT。

`1.4.0` 按「SigBlk 含 SIGQUIT」去认它，条件恒不成立 —— 只是靠「首个名为 Signal Catcher 的
线程」兜底才拿到了正确 tid，属于隐患而非现症。Matrix 的取法是 `comm == "Signal Catcher"` 且
`SigBlk == 0x1000`；这里放宽成「SIGQUIT 位为 0」，避免各 ROM 上其余位不一致导致匹配不上，
名字兜底保留（它同时兜住了「刚 dump 完还没重新进 sigwait」的瞬态）。

### 7.3 主线程 Java 栈从来没抓过（P0）

`AnrJavaDump.capture(skipJavaStack)` 里 `if (!skipJavaStack) appendJavaStack(...)`，而
`AnrDetector` 的两个主调用点传的都是 `true`。`dumpKernelStack()` 读 `/proc/self/task/<tid>/stack`
应用无权限，恒为空。设备上落盘的 `main_stack.txt` 因此只有 **38 字节**一行表头：

```
----- main "main" state=TIMED_WAITING
```

而且 `snapshot` 永远非空（表头就占了 38 字节），`fire()` 里 `capture(false)` 那个兜底分支是死代码。
即使 7.1 修好，采到的也只是这行表头。

修法：`capture()` 去掉开关，永远抓主线程栈；`fire()` 简化为「优先用 SIGQUIT 刚落地时的
snapshot，为空才现抓」，删掉 `hasJavaFrames` 分支。

### 7.4 `pending/` 只写不读（P0）

`PendingStore` 只有 `save` / `prune`，**没有删除也没有重投递**：`runPipeline` 无条件写入 pending，
上报成功不删，失败也不补。设备上躺着 11 条 10:21–12:01 的记录，只会等 7 天过期或超 20 条被淘汰。

修法：
- `PendingStore.remove(dumpDir, crashId)` / `loadAll(dumpDir)`
- `CrashPipeline.emitAll`：META / DUMP / LOGS **三段全成功才删** pending 记录；任一段失败、
  或 reporter 压根不回调，就留在盘上
- `CrashPipeline.resendPending()`：下次启动重投，只走 reporter / telemetrySink，不碰宿主
  `CrashCallback`（理由同 6.3），也不碰 `blocker`
- `CrashKit.init` 里挂 `whenReporterReady { resendPendingAsync(p) }`，在后台线程跑

同时修掉 `1.4.1` 引入的一个latent bug：`whenReporterReady` 用单个 `AtomicReference` 存回调，
加第二个订阅者会把第一个覆盖掉。改成列表，并让 `setReporter`（先置 reporter 再取锁）与
`whenReporterReady`（先取锁再判）的加锁顺序保证不丢注册。

### 7.5 黑屏的真实原因：重启后又 ANR 了一次

设备日志里两次独立复现同一个模式：

```
14:45:59  Force finishing activity .../DebugActivity        ← 点「关闭应用」
14:46:01  CrashKit init (pid 18131)                          ← 重启
14:47:59  am_anr [18131, Broadcast of Intent ... io.rong.imlib.HeartbeatReceiver]
14:48:08  ANR in xyz.kenterk.test.android
14:51:28  Force finishing activity .../SplashActivity        ← 启动页被系统强制结束 = 黑屏
```

12:18 那次完全一样。是宿主集成的**融云 IM `HeartbeatReceiver` 广播在冷启动阶段 ANR**，
AMS 走 `finishTopCrashedActivityLocked` 把 `SplashActivity` 强制结束。

与 CrashKit 无关，修在宿主：把心跳广播的处理挪出主线程。这也解释了为什么
`1.3.7` 改「只采不杀」没能修掉它 —— SDK 本来就不是原因。

### 7.6 补上真机回归测试

`crash-core/src/androidTest/.../SignalAnrBypassTest.kt`。信号路径在 JVM 单测里完全测不到
（没有真实信号），7.1 那种 bug 只有真机能发现——而 `1.4.0` 就是漏了这一步才带着坏的采集发了两个版本。

三个用例：

| 用例 | 守什么 |
|---|---|
| `mainThreadUnblocksSigquit` | 装完旁路后主线程 SigBlk 的 SIGQUIT 位必须为 0（7.1） |
| `sigquitReachesBypassNotOnlySignalCatcher` | 发一发 SIGQUIT，`onAnrSignal` 必须被回调（7.1 端到端） |
| `signalCatcherParksWithSigquitUnblocked` | Signal Catcher 停在 sigwait 时 SigBlk 不含 SIGQUIT（7.2 的前提） |

实测 3/3 通过，端到端那条 11ms 触发。

为了让第二条测得了，`anr_handler` 的自发信号防护也收窄了：原来判 `si_pid == getpid()` 就返回，
把本进程内任何来源的 SIGQUIT 都丢掉；改成只忽略「本进程发出、且此刻正跑在 Signal Catcher 上」
的那一发。这个过宽的防护还有个副作用：它吞掉信号又不转交，ART 不 dump，华为 PowerGenie
（日志 tag `PG_ash`）就把进程冻在 `__refrigerator` 上不放，表现为测试挂死。

### 7.7 这次的教训

`1.4.0` 的问题不在方案，在于**信号路径没有任何真机验证就发版**：编译通过、单测通过、lint 通过，
四个 P0 一个都没拦住，其中三个是「代码写了但整条链路根本不通」。
凡是碰 signal / `/proc` / 线程掩码的改动，必须过 `connectedAndroidTest`。

## 8. `1.4.5`：确认空转时进程被杀，现场丢失

debug 页会在 AMS 5s 超时之前就自己发 SIGQUIT。`1.4.4` 只认「队头超期」或
`processesInErrorState`，前台超期门槛是 2s，华为上 AM 状态经常一直是空的。结果是：

1. SIGQUIT 进了旁路，dump 线程也起来了（有 `mMessages` 反射日志）
2. 确认空转去等 AM，最多 20s，中间一行成功日志都没有
3. 用户关掉 ANR 弹窗，进程被 SIGKILL（实测 8.7s），`fire()` 还没跑，pending 没写
4. 这台是 Android 10，没有 `ApplicationExitInfo` 补报
5. 下次启动只剩 `reporter=` / `signal ANR install`，看起来像「ANR 没采集」

修复：主线程第一帧不是 `MessageQueue` / `Looper.loop` 空转就立刻上报；AM 轮询每一轮重新抓栈。
空闲主线程经常是 `WAITING`（卡在 `nativePollOnce`），不能拿 `Thread.state` 当 ANR。

## 9. `1.4.5` 之后：重投时机、init 约束、Activity 历史

第 7.4 节当时的修法是 `init` 里 `whenReporterReady { resendPendingAsync }`。现行代码已经改掉：

- **pending 重投**：`CrashKit.retryPending()`，由宿主在鉴权 / 网络就绪后调用，可多次。正在重投时后来的调用跳过。没有真实 reporter 不清 pending。`init` 不再自动重投。ExitInfo 补报仍等 reporter 到位再跑。
- **`init`**：必须主线程、每进程只成功一次。非主线程或重复调用返回 `false`，**不会** post 到主线程补做（晚一个 loop 可能旁路不到 SIGQUIT）。
- **Activity history**：`ActivityHistoryFormat` 把相邻同页生命周期收成 `DebugActivity(C:S:R)`。内部仍是早→晚；上报 `fromTop` 从栈顶往回写，埋点最多 6 页 / 192 字，超长丢栈底、不截断当前页类名。日志 JSON 字段是 `activity`。`ext_data1–5` 仍不写出。
