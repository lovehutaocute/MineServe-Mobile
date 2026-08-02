# 技术架构文档（JavaMC GO）

> 原名 MCServerManager，现已更名为 **JavaMC GO**。

## 1. 总体架构

JavaMC GO 是一个 Android 原生应用，核心目标是在**无 root** 的 Android 手机上运行 Minecraft Java 版服务端。关键技术挑战是 Android 10+ 的 W^X（Write XOR Execute）策略禁止从 app data 目录执行二进制文件，而 Minecraft 服务端需要 Java 运行环境。

### 架构分层

```
┌─────────────────────────────────────────────────┐
│                  UI 层 (Compose)                  │
│  Dashboard / Download / Players / Plugins&Mods / │
│  Files / Network / Backup / Config / Settings     │
├─────────────────────────────────────────────────┤
│                  ViewModel                        │
│  McViewModel + StateFlow + DataStore              │
├─────────────────────────────────────────────────┤
│                业务服务层                         │
│  McServerController / TunnelManager / BackupManager│
│  PlayerManager / PluginManager / ServerProperties │
├─────────────────────────────────────────────────┤
│               Android 系统服务                    │
│  McForegroundService + WorkManager + BootReceiver │
├─────────────────────────────────────────────────┤
│              Termux 运行时                        │
│  BootstrapInstaller + CommandExecutor +           │
│  TermuxRuntime（进程管理）                       │
├─────────────────────────────────────────────────┤
│              Android 系统层                      │
│  /system/bin/sh + ProcessBuilder + FileObserver  │
└─────────────────────────────────────────────────┘
```

## 2. Termux 运行时

### 2.1 Bootstrap Rootfs

应用在首次启动时下载 Termux bootstrap rootfs（约 30MB 的 ZIP），解压到应用私有目录：

```
/data/user/0/com.javamc.go/files/home/
├── bin/                    # 可执行命令（符号链接或 wrapper 脚本）
├── etc/                    # 配置文件（apt.conf, resolv.conf, dpkg/）
├── lib/                    # 动态库
├── home/
│   ├── servers/            # 多核心目录（每个核心独立）
│   │   └── {dirName}/
│   │       ├── server.jar / installer 产物
│   │       ├── eula.txt / server.properties
│   │       ├── plugins/    # 插件目录
│   │       ├── mods/       # 模组目录
│   │       ├── world/ world_nether/ world_the_end/  # 三维度存档
│   │       └── logs/latest.log
│   ├── snapshots/          # 世界备份 zip
│   └── tunnel/             # 内网穿透二进制和配置
│       └── bin/
├── tmp/
└── var/lib/dpkg/           # dpkg 数据库
```

### 2.2 命令执行机制

所有 Termux 命令通过 Android 系统的 `/system/bin/sh` 执行，避免 app_data_file 执行限制：

```kotlin
val envSetup = "export PATH='$prefix/bin:...'; " +
    "export LD_LIBRARY_PATH='$prefix/lib:...'; " +
    "export PREFIX='$prefix'; export HOME='$prefix/home'; ..."
listOf("/system/bin/sh", "-c", envSetup + cmdStr)
```

`LD_LIBRARY_PATH` 包含 `$prefix/data/data/com.termux/files/usr/lib`，因为 dpkg-wrapper 跳过了 configure 步骤，包被解压到 compat 路径而非标准 `$prefix/lib/`。

## 3. 服务端管理

### 3.1 多核心架构

每个服务端核心独立存放在 `home/servers/{dirName}/`，`dirName` 由用户名称 sanitized 生成，互不干扰。

```kotlin
fun serverDirFor(dirName: String): File =
    File(installer.rootDir, "home/servers/$dirName")
```

### 3.2 支持的核心类型（9 类）

| 核心 | 下载方式 | 插件 | 模组 | 备注 |
|---|---|---|---|---|
| Paper | PaperMC v3 API | ✓ | ✗ | 高性能优化，生产推荐 |
| Purpur | Purpur 官方 API | ✓ | ✗ | Paper 分支，更多特性开关 |
| Fabric | Fabric meta API | ✗ | ✓ | 轻量模组加载器 |
| Forge | Forge maven-metadata | ✗ | ✓ | 老牌模组加载器 |
| NeoForge | NeoForge maven-metadata | ✗ | ✓ | installer 流程（--installServer） |
| Quilt | Quilt meta API | ✗ | ✓ | installer 流程（quilt install server） |
| Vanilla | Mojang version manifest | ✗ | ✗ | 官方原版 |
| Velocity | PaperMC v3 API | ✗ | ✗ | 代理端 |
| BungeeCord | md-5 Jenkins 直链 | ✗ | ✗ | 代理端 |

NeoForge/Quilt 下载的是 installer.jar，下载完成后自动执行安装命令生成启动环境（unix_args.txt / quilt-server-launch.jar），启动命令按核心类型适配。

### 3.3 进程管理

MC 进程通过 `ProcessBuilder` 直接启动，不依赖 tmux：

```kotlin
val pb = ProcessBuilder("/system/bin/sh", "-c", javaCmd).apply {
    redirectErrorStream(true)
    directory(serverDir)
    environment().putAll(executor.termuxEnv())
}
```

启动命令按核心类型构造：
- 普通核心：`java -Xmx{m}m -Xms{m/2}m -jar server.jar nogui`
- NeoForge：`java -Xmx{m}m @libraries/net/neoforged/neoforge/{ver}/unix_args.txt nogui`
- Quilt：`java -Xmx{m}m -jar quilt-server-launch.jar nogui`

进程真实内存通过遍历 `/proc` 匹配 `comm=java` 读取 RSS。

## 4. 内网穿透

统一由 `TunnelManager` 管理，支持两种方式（`TunnelType`）：

- **frp**：功能最全，支持自定义端口。frpc 优先使用 apt 版本（固定版本兼容），写入 `frpc.toml` 前自动移除 `autoTLS` 字段（旧版 frpc 不兼容）。
- **bore**：协议最简，纯 Kotlin 实现，无需下载二进制。

## 5. 插件与模组

### 5.1 插件管理

- 扫描 `plugins/` 目录 + 解析 plugin.yml 元信息（带 lastModified 缓存）
- 支持启用/禁用（`-` 前缀重命名）、删除（含数据目录）、本地上传、URL 安装
- 资源站点指引（SpigotMC / Hangar / Modrinth / BuiltByBit / CurseForge）

### 5.2 模组管理

- 扫描 `mods/` 目录（Fabric/Forge），支持启停（`.jar.disabled` 后缀）、删除、上传
- **Modrinth 开放 API**：`/v2/search` 搜索（多加载器 facets + 排序 downloads/relevance/newest），`/v2/project/{slug}/version` 解析指定 MC 版本+加载器的 release 直链，一键安装；模组图标网络加载；当前核心不匹配红字提醒

### 5.3 核心兼容性

`ServerCore` 定义 `supportsPlugins` / `supportsMods`，插件与模组页面按当前核心自动屏蔽不可用的分类，并显示醒目彩色提示。

## 6. 玩家管理

- **在线玩家列表**：从控制台日志实时维护（进服/离服增量 + `list` 命令全量校正），每行支持一键复制、踢出、切换游戏模式
- **进服/离服历史**：自动记录精确到时分秒的事件，JSON 持久化（上限 500 条）
- OP / 白名单 / 封禁列表：命令操作（`op` 兼容旧版 MC 无 level 参数）+ JSON 文件读取，服务器启动完成后自动刷新
- 日志解析用鲁棒正则（支持 `[IP]` 后缀、`has joined` 变体、冒号锚定防聊天误报）

## 7. 备份与还原

- 快照打包 **world + world_nether + world_the_end** 三维度目录（MC 1.16+ 平级目录），zip 保留层级
- 还原时自动停止服务器、备份当前三维度目录、解压恢复
- 备份列表支持还原、删除、**导出到本地**（SAF 自定义路径）

## 8. 保活体系

参考 HelloDaemon 思路的纯 Android 方案：

- **前台服务**（McForegroundService）：START_STICKY + 常驻通知 + 唤醒锁（WAKE_LOCK / Wi-Fi 锁）
- **开机自启动**（BootReceiver）：监听 `BOOT_COMPLETED`，开启后自动拉起服务
- **后台周期保活**（KeepAliveWorker）：WorkManager 每 15 分钟检查服务存活并重启
- 专属保活页面：服务状态 + 启动/停止 + 各开关（开机自启 / 周期保活 / 崩溃自动重启）及功能说明

## 9. 设备与网络监控

- **设备状态**（DeviceStats，3 秒定时采集）：设备内存（ActivityManager）、存储（StatFs）、电池（BatteryManager）
- **网络数据**（TrafficStats，无权限）：总上传/下载量 + 实时上传/下载速度（相邻采样差值）
- 概览页 HeroBlock：TPS / 在线 / 进程内存 / 运行时长，启动中状态展示

## 10. 配置管理

- 底部导航「配置」tab 直达 `server.properties` 完整编辑
- 73 个参数按基础/高级分组，数据驱动渲染（布尔开关 / 数字框 / 枚举选择器 / 文本输入）
- 未收录的新参数兜底展示，保证完整加载不丢失
- 全局输入框采用防抖组件（本地即时编辑 + 300ms 防抖写回 + 失焦/销毁 flush），杜绝输入延迟与乱序

## 11. 数据持久化

- **McConfig**：DataStore（JSON 序列化到单一 key），300ms CONFLATED 防抖合并写
- **ServerState**：内存态 StateFlow，原子 CAS 更新
- **玩家历史**：app filesDir 的 JSON 文件（Mutex 串行化读写）
- **保活开关**：SharedPreferences

## 12. 关键类一览

| 类 | 职责 |
|---|---|
| `McApplication` | 全局单例：TermuxRuntime / Repository / Bootstrap 状态 |
| `MainActivity` | 单 Activity + Compose，Edge-to-Edge，通知权限 |
| `McApp` | 根布局：底部 9 Tab + 子页面导航 + BackHandler 返回键 |
| `McViewModel` | UI 状态管理，业务逻辑协调（设备采集 / 玩家 / 模组 / 保活） |
| `McServerController` | 核心下载（9 类）/ installer / 启动 |
| `TermuxRuntime` | 进程管理（MC/隧道）、命令执行、快照、内存读取 |
| `PluginManager` | 插件/模组管理、Modrinth API |
| `PlayerManager` | 玩家命令、JSON 列表、日志解析 |
| `TunnelManager` | 内网穿透（frp / bore） |
| `BackupManager` | 三维度快照与还原 |
| `McForegroundService` | 前台服务保活 |
| `BootReceiver` / `KeepAliveWorker` | 开机自启 / 周期保活 |
