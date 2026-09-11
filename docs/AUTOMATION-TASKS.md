# MineServe-Mobile 自动任务全量梳理

> 分析对象：MineServe-Mobile 主仓库
> 代码规模：102 个 Kotlin 文件 / 约 33,800 行
> 梳理范围：**所有非用户点击触发的自动逻辑**（不含 onClick / 按钮回调）
> 结论依据：全部经源码逐行核实（含行号），已剔除推测项

---

## 一、总览：自动任务按风险分级

| 级别 | 数量 | 特征 | 典型代表 |
|---|---|---|---|
| **P0 极高** | 4 | 常驻循环，只要进程活着就永久运行 | 日志解析、200ms 刷新循环、30s watchdog |
| **P1 高** | 6 | 定时轮询 / 每次启动执行的重 IO | 资源采集、启动自检修复链 |
| **P2 中** | 8 | WorkManager 周期、事件驱动 | 15 分钟保活、自动备份 |
| **P3 低** | 6 | 一次性 / 每日限频 | 更新检查、使用统计 |

---

## 二、P0 极高风险任务（常驻循环）

### 1. 控制台日志逐行解析

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 对 MC 输出的**每一行**日志执行：启动阶段判定、Java 版本兼容校验、玩家进出识别、在线人数正则、TPS 正则，并写回 `ServerState` |
| **功能用途** | 实时驱动 UI 状态（在线玩家、TPS、启动进度），是状态数据的唯一来源 |
| **执行时机** | MC 进程运行期间，产生任意日志行时 |
| **触发条件** | `consoleFlow` 发射 — 即 MC 每写一行日志 |
| **性能影响** | ⚠️ **最高**。MC 运行时可产生数十行/秒，每行跑全套正则。**且 `McViewModel` 与 `McForegroundService` 各解析一遍，App 前台时 CPU 翻倍** |

```kotlin
// McViewModel.kt:3915-3920
viewModelScope.launch(Dispatchers.Default) {
    repo.termuxRuntime.consoleFlow.collect { line ->
        consoleBuffer.add(line)
        pendingConsoleBuffer.add(line)
        parseConsoleLine(line)      // 每行都跑全套解析
    }
}
```

---

### 2. 控制台批量刷新循环（主循环）

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 快照待处理缓冲 → 对每行跑 `TerminalLogProcessor.process()`（内含 150+ 条 `contains` 规则 + 多组正则做终端汉化）→ 发布 StateFlow |
| **功能用途** | 把原始日志转成中文可读终端输出，并做帧友好的批量刷新（避免逐行触发重组） |
| **执行时机** | ViewModel 创建即常驻 |
| **触发条件** | 有 UI 订阅时每 200ms 一轮；**无 UI 订阅时降为每 2s** |
| **性能影响** | ⚠️ 高。200ms = 每秒 5 轮；若日志累积，每轮要处理整批。`TerminalLogProcessor` 的 150+ 条字符串匹配是主要 CPU 成本 |

```kotlin
// McViewModel.kt:3924-3941
while (true) {
    if (_consoleLines.subscriptionCount.value <= 0 && ...) { delay(2000); continue }
    delay(200)                                    // ← 有 UI 时 200ms
    val batch = pendingConsoleBuffer.snapshotAndClear()
    ...
    consoleBuffer.snapshot().map { TerminalLogProcessor.process(it, ...) }
}
```

---

### 3. 日志环形缓冲刷新循环（×2）

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 两个独立循环：`legacyTermuxBuffer` 与 `terminalOutputBuffers`，各自快照并重建不可变列表（`takeLast(1500)`） |
| **功能用途** | 维护终端历史日志（上限 1500 行），供多会话终端显示 |
| **执行时机** | ViewModel 创建即常驻 |
| **触发条件** | **固定每 200ms，无论有无 UI 订阅**（无空闲降频） |
| **性能影响** | ⚠️ 中高。即使 App 在后台、无人看终端，仍每 200ms 醒一次做 map/filter |

```kotlin
// McViewModel.kt:3967-3976 & 3977-3991
while (true) {
    delay(LOG_FLUSH_MS)          // 200L
    val batch = legacyTermuxBuffer.snapshotAndClear()
    if (batch.isNotEmpty()) {
        _termuxLines.value = (current + batch).takeLast(MAX_LOG_LINES).toImmutableList()
    }
}
```

---

### 4. 前台服务健康 Watchdog

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | ① `isMcRunning()` 检查 MC 进程存活并同步 `ServerState.isRunning`；② 每 2 tick 发 `list` 命令（Paper 额外发 `tps`）；③ 调 `maybeAutoBackup()`；④ 每次 tick 刷新桌面组件 |
| **功能用途** | 保证服务状态与真实进程一致；采集玩家数/TPS；驱动自动备份；更新桌面组件 |
| **执行时机** | 前台服务启动时自动开启（App 冷启动延迟 3s 自动拉起服务） |
| **触发条件** | 服务存活期间无条件循环 |
| **性能影响** | ⚠️ 中。每 30s 一轮。`isMcRunning()` 是内存查询（`Process.isAlive`）**并非扫进程**，开销极小；主要成本在 60s 一次的 `WidgetUpdater.refresh`（4 个组件 RemoteViews 重建 + 2 次列表通知） |

```kotlin
// McForegroundService.kt:262-302
while (true) {
    val alive = try { termux.isMcRunning() } catch (e: Exception) { false }
    app.repository.updateServerState { if (!alive) it.copy(isRunning = false, ...) else it.copy(isRunning = true) }
    if (alive && tick % 2 == 0) {
        maybeAutoBackup(config)
        termux.sendCommand("list")
        if (config.selectedCore == ServerCore.Paper) termux.sendCommand("tps")
    }
    tick++
    WidgetUpdater.refresh(this@McForegroundService)    // 每 30s
    kotlinx.coroutines.delay(30_000L)
}
```

---

## 三、P1 高风险任务

### 5. 服务器资源采集轮询

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 采集磁盘可用空间（`StatFs`）、服务器目录总字节、MC 进程内存（读 `/proc`）、CPU 占用、Java 是否可用 |
| **功能用途** | 概览页资源卡片数据源；目录膨胀预警 |
| **执行时机** | ViewModel 创建后延迟 15s 首跑，之后常驻 |
| **触发条件** | 每 15 秒一轮；**内部有三重降频**：目录扫描 60s 缓存、内存采样 30s 缓存、Java 校验 60s 缓存 |
| **性能影响** | ⚠️ 中。真正的重 IO（`walkTopDown()` 递归统计目录字节）被 60s 缓存限制；`StatFs` 开销极小。**值无变化时不发布新快照**，避免无谓重组 |

```kotlin
// McViewModel.kt:694-703
delay(15_000)
while (true) {
    withContext(Dispatchers.IO) { collectServerResourcesOnce() }
    delay(15_000)
}

// 725-742：目录扫描 60s 节流
if (cachedResourceDir != serverDir.path || now - cachedDirectoryBytesAtMs >= 60_000L) {
    cachedDirectoryBytes = serverDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
```

---

### 6. 启动前运行时自检与自动修复链

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | `fixRootfsPermissions()` 修权限 + `autoRepairRuntime()` 内串行执行：修复 dpkg wrapper、usr/bin 归位、rootfs 可执行位、脚本、apt 源、apt 配置、`ensureJvmComplete()` 校验 JDK 完整性（缺失则 `apt-get --reinstall` 重装）、Java 符号链接、字体运行库（缺失则 apt 安装 + `fc-cache`） |
| **功能用途** | 防止因文件权限/符号链接/JDK 不完整导致的启动失败，是 Termux 环境自愈的核心 |
| **执行时机** | 每次启动服务端前 |
| **触发条件** | 无条件执行；各项内部有 `isJavaComplete()` 等前置判断，已完整则跳过 |
| **性能影响** | ⚠️ 中高（但仅一次）。最重的是 `apt-get --reinstall` 与 `fc-cache -f`，耗时可达数十秒；正常情况下各项检查快速返回 |

```kotlin
// TermuxRuntime.kt:1356-1405
var repaired = fixDpkgWrapper() + fixUsrBin() + ensureRootfsExecutable()
repaired += fixScriptsOnce() + fixAptSources() + ensureAptConfigs()
repaired += ensureJvmComplete(javaVersion)
fixJavaSymlinks(javaVersion)
```

---

### 7. Bootstrap 环境初始化

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 下载并解压 rootfs、apt 安装 JDK/wget、`fixRootfsPermissions`、`fixJavaSymlinks`、`repairProotLibraries` |
| **功能用途** | 首次运行或环境损坏时构建完整运行环境 |
| **执行时机** | App 冷启动，检测到环境未就绪时 |
| **触发条件** | `isReady()` 为 false；已就绪则跳过 |
| **性能影响** | ⚠️ 高（仅首次/损坏时）。大文件下载 + 解压，有明显的磁盘 IO 与网络占用 |

```kotlin
// McApplication.kt:207-216
if (!app.isBootstrapped.value) { ... startBootstrap() }
```

---

### 8. 崩溃检测与自动重启

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | MC 进程退出回调中读取 exit code → 生成崩溃报告（含关键片段提取）→ 若开启自动重启且未达上限，3s 后重新拉起 |
| **功能用途** | 服务意外崩溃后自动恢复可用性；留存崩溃证据 |
| **执行时机** | MC 进程退出瞬间 |
| **触发条件** | `autoRestartOnCrash = true` 且 `restartAttempts < maxRestartAttempts` |
| **性能影响** | ⚠️ 低（事件驱动）。但**若崩溃成循环**（配置错误导致秒退），会形成 3s 一次的启动风暴，这是最需要警惕的场景 |

```kotlin
// McServerController.kt:1520-1596（createExitHandler）
crashReportManager.captureCrash(exitCode, wasRunningBefore, dirName)
if (config.autoRestartOnCrash && restartAttempts < maxRestartAttempts) {
    Thread.sleep(3000)
    launchMc(config, dirName, jarPath)
}
```

---

### 9. 启动时配置文件自动改写

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | PowerNukkitX：每次启动重写 `server.properties`；Allay：强制 `enablev6: false` 并同步端口；目录名非 ASCII 时 `renameTo` 迁移 + 回写配置 |
| **功能用途** | 规避 Android 平台缺陷（如 IPv6 绑定崩溃）与文件系统编码问题 |
| **执行时机** | 每次启动 |
| **触发条件** | 按核心类型无条件执行/条件执行 |
| **性能影响** | 低。小文件读写 |

---

### 10. Java 版本自动切换

| 维度 | 内容 |
|---|---|
| **检测/修复内容** | 按 MC 版本 + 核心类型自动选择所需 JDK，当前选择不足时升级并 `saveConfig` |
| **功能用途** | 避免"Java 版本过低"导致的启动失败 |
| **执行时机** | 每次启动前 |
| **触发条件** | `selectedJavaVersion.ordinal < recommended.ordinal`（如 Paper 26.1+ 要求 Java 25） |
| **性能影响** | 低（判断），但落盘为异步队列 |

```kotlin
// McServerController.kt:1414-1425
val launchJava = if (recommended != null && config.selectedJavaVersion.ordinal < recommended.ordinal) {
    termux.emitLog("[startMc] ... 已自动切换")
    runCatching { repo.saveConfig(config.copy(selectedJavaVersion = recommended)) }
    recommended
} else config.selectedJavaVersion
```

---

## 四、P2 中风险任务

| # | 任务 | 位置 | 时机 | 条件 | 性能影响 |
|---|---|---|---|---|---|
| 11 | **保活 Worker** | `KeepAliveWorker.kt:14-30`<br>注册 `McApplication.kt:195-201` | 每 **15 分钟** | `isRunning == false` 才动作 | 低。仅拉起前台服务 |
| 12 | **组件兜底刷新 Worker** | `WidgetRefreshWorker.kt:17-35`<br>注册 `McApplication.kt:272-278` | 每 **15 分钟**，App 进程死后仍调度 | 扫 `/proc` 判断孤儿进程 | 低，但**进程已死时唤醒** |
| 13 | **自动备份** | `McForegroundService.kt:307-352` | watchdog 每 60s **检查**，按配置间隔**执行** | `autoBackupIntervalMin > 0`<br>+ 外部存储权限<br>+ 距上次超间隔 | ⚠️ 中高（执行时）。打包大世界的磁盘 IO 峰值明显；已有 0–10 分钟错峰偏移 |
| 14 | **每日定时开/停服** | `ScheduleManager.kt:23-51`<br>`ScheduleAlarmReceiver.kt:22-53` | 每日定点（`setExactAndAllowWhileIdle`） | 用户已开启 + 已注册闹钟 | 低。执行后重注册次日 |
| 15 | **开机广播自启** | `BootReceiver.kt:21-52` | 每次开机 | `ACTION_BOOT_COMPLETED` | 低。重注册闹钟 + 刷组件 + 条件拉起服务 |
| 16 | **任务划掉后闹钟重启** | `McForegroundService.kt:398-425` | 划掉任务后 2s | 服务非正常退出 | 低 |
| 17 | **MCP 服务器自动启停** | `McpServerManager.kt:76-80` | 配置变化时 | `configFlow` 变更 | 低。启停本地 HTTP 服务 |
| 18 | **状态变更 → 组件刷新** | `ServerRepository.kt:82-95` | 每次 `updateServerState` | 6 个关键字段 diff 命中 | ⚠️ 中。**被日志解析高频驱动**，靠 `WidgetUpdater` 的 300ms 防抖合并 |

---

## 五、P3 低频任务

| # | 任务 | 位置 | 时机 | 性能影响 |
|---|---|---|---|---|
| 19 | **启动检查更新** | `McApp.kt:84-88` → `McViewModel.kt:521` | 冷启动延迟 1s，一次 | 低。GitHub API 请求（3 镜像 fallback），失败静默 |
| 20 | **每日使用统计上报** | `UsageTracker.kt:24-51` | 启动时检查，**每天最多 1 次** | 低。1 次 HTTPS POST |
| 21 | **崩溃日志启动上报** | `McApplication.kt:153-161` | 冷启动且 `crash_log.txt` 非空 | 低。一次性 |
| 22 | **服务器目录扫描登记** | `McViewModel.kt:1247-1281` | VM 创建时 / 进入管理页 | 低。`listFiles` + 核心探测，有 `scanningServers` 防重入 |
| 23 | **进入页面自动加载** | 10+ 个 Screen 的 `LaunchedEffect(Unit)` | 每次进入页面 | ⚠️ 中。含网络请求（PluginsScreen 拉 Modrinth）、目录扫描（BackupScreen/FileManager/CrashReports）、网卡枚举（NetworkScreen 等 4 处）；`DiagnosticsScreen` **打开即全量自检** |
| 24 | **日志文件 FileObserver** | `CommandExecutor.kt:254-289` | 文件写入事件 | 低。增量读取推送 |

---

## 六、关键发现与建议

### 🔴 发现 1：日志双份解析（可优化 50%）

`McViewModel.kt:3916` 与 `McForegroundService.kt:88` **各订阅一遍 `consoleFlow` 并各自解析**。App 在前台时同一条日志被解析两次。建议统一到一处（如 Service 解析后由 VM 订阅结果）。

### 🔴 发现 2：两个 200ms 循环无空闲降频

`McViewModel.kt:3967` 和 `3977` 的两个循环**即使无 UI 订阅也保持 200ms 频率**（对比 3924 那个有 `delay(2000)` 降级）。App 在后台待机时仍每秒唤醒 10 次，建议加同样的订阅数判断。

### 🟡 发现 3：`WidgetUpdater.refresh` 每 30s 全量重建

`McForegroundService.kt:298` 无条件每 30s 刷新 4 个组件（RemoteViews 构建 + 2 次 `notifyListChanged`），且注释说"停止/崩溃等事件由状态 diff 钩子驱动"——**说明这里可以只在运行中刷新**，静止状态可跳过。

### 🟡 发现 4：崩溃重启存在风暴风险

`createExitHandler` 的 3s 重试间隔，遇到配置错误导致的秒退会形成启动风暴。虽有 `maxRestartAttempts` 上限，但建议加**指数退避**（3s → 6s → 12s…）。

### ✅ 已做得好的地方

- 资源采集有三重缓存节流（60s/30s/60s），且**值无变化不发布快照**
- 自动备份有多服务器错峰偏移（0–10 分钟哈希偏移）
- `WidgetUpdater` 有 300ms 防抖
- `updateConfig` 有 800ms 合并窗口（本次会话新加）
- 无网络广播监听、无 JDK `Timer`、无 `ContentObserver`，机制相对克制

---

## 七、纠正两处常见误判

| 误判 | 实际情况 |
|---|---|
| "watchdog 每 30s **扫进程**" | ❌ 错。`isMcRunning()` = `mcProcess?.isAlive`（`TermuxRuntime.kt:2536`），是内存查询，**开销极小** |
| "资源采集每 15s **递归扫描目录**" | ❌ 不准确。`walkTopDown()` 受 60s 缓存限制（`McViewModel.kt:733`），并非每 15s 都扫 |
