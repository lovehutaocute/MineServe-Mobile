# 技术架构文档

## 1. 总体架构

MCServerManager 是一个 Android 原生应用，核心目标是在**无 root** 的 Android 手机上运行 Minecraft Java 版服务端。关键技术挑战是 Android 10+ 的 W^X（Write XOR Execute）策略禁止从 app data 目录执行二进制文件，而 Minecraft 服务端需要 Java 运行环境。

### 架构分层

```
┌─────────────────────────────────────────────────┐
│                  UI 层 (Compose)                  │
│  Dashboard / Network / Players / Plugins / ...  │
├─────────────────────────────────────────────────┤
│                  ViewModel                        │
│  McViewModel + StateFlow + DataStore             │
├─────────────────────────────────────────────────┤
│                业务服务层                         │
│  McServerController / TunnelManager /            │
│  BackupManager / PlayerManager / PluginManager   │
├─────────────────────────────────────────────────┤
│               Android 系统服务                    │
│  McForegroundService + WorkManager               │
├─────────────────────────────────────────────────┤
│              Termux 运行时                        │
│  BootstrapInstaller + CommandExecutor +          │
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
/data/user/0/com.mcserver.manager/files/home/
├── bin/                    # 可执行命令（符号链接或 wrapper 脚本）
├── etc/                    # 配置文件（apt.conf, resolv.conf, dpkg/）
├── lib/                    # 动态库
├── home/
│   ├── servers/            # 多核心目录（每个核心独立）
│   │   └── {dirName}/
│   │       ├── server.jar
│   │       ├── eula.txt
│   │       ├── server.properties
│   │       ├── plugins/
│   │       └── logs/latest.log
│   └── tunnel/             # 内网穿透二进制和配置
│       └── bin/
├── tmp/
└── var/lib/dpkg/           # dpkg 数据库
```

### 2.2 命令执行机制

所有 Termux 命令通过 Android 系统的 `/system/bin/sh` 执行，避免 app_data_file 执行限制：

```kotlin
// CommandExecutor.buildExecCommand()
val envSetup = "export PATH='$prefix/bin:...'; " +
    "export LD_LIBRARY_PATH='$prefix/lib:...'; " +
    "export PREFIX='$prefix'; export HOME='$prefix/home'; ..."
listOf("/system/bin/sh", "-c", envSetup + cmdStr)
```

`LD_LIBRARY_PATH` 包含 `$prefix/data/data/com.termux/files/usr/lib`，因为 dpkg-wrapper 跳过了 configure 步骤，包被解压到 compat 路径而非标准 `$prefix/lib/`。

### 2.3 dpkg-wrapper

Termux 的 apt 通过自定义 dpkg-wrapper 安装包，但 configure 步骤是 no-op，导致 post-install 脚本未执行。这带来两个问题：

1. **二进制不在 `$PREFIX/bin/`**：实际在 `$PREFIX/data/data/com.termux/files/usr/bin/`，需要 `findAptBinary()` 多路径查找并创建符号链接
2. **Java wrapper 脚本**：`$PREFIX/bin/java` 不是符号链接而是 wrapper 脚本，设置 `LD_LIBRARY_PATH` 包含 jvm/lib 目录后 exec 原始 java 二进制

### 2.4 Java 运行环境

- **openjdk-25**：Paper 26.x / MC 26.1+ 要求 Java 25+
- **wrapper 脚本**：`$PREFIX/bin/java` 内容示例：
  ```sh
  #!/system/bin/sh
  export LD_LIBRARY_PATH='jvm/lib:jvm/lib/server:jvm/lib/jli:compat/lib'
  export JAVA_HOME='jvm目录'
  exec '原始java二进制' "$@"
  ```
- **依赖解析**：`libandroid-shmem.so` 和 `libjli.so` 通过 LD_LIBRARY_PATH 解析

## 3. 服务端管理

### 3.1 多核心架构

每个服务端核心独立存放在 `home/servers/{dirName}/`，支持 Paper / Fabric / Forge / Vanilla。`dirName` 由用户名称 sanitized 生成，互不干扰。

```kotlin
fun serverDirFor(dirName: String): File =
    File(installer.rootDir, "home/servers/$dirName")
```

### 3.2 PaperMC 下载

使用 PaperMC v3 API（`fill.papermc.io`），v2 已 sunset（HTTP 410）：

1. 获取版本列表 → 选择 STABLE > RECOMMENDED > BETA > ALPHA 通道
2. 获取最新 build 的下载 URL
3. Java HttpURLConnection 下载（避免 wget 依赖），3 次重试，64KB 缓冲
4. 校验文件大小 >1KB

### 3.3 进程管理

MC 进程通过 `ProcessBuilder` 直接启动，不依赖 tmux：

```kotlin
val pb = ProcessBuilder("/system/bin/sh", "-c", javaCmd).apply {
    redirectErrorStream(true)
    directory(serverDir)
    environment().putAll(executor.termuxEnv())
}
```

- **stdout 读取**：后台线程读取，推送到 `consoleFlow` 并写入 `logs/latest.log`
- **日志监视**：`FileObserver` 监视日志文件变化，实现实时日志展示
- **命令发送**：向 MC 进程 stdin 写入 `stop` 等命令
- **退出处理**：`onExit` 回调更新状态，可选自动重启

### 3.4 前台服务保活

`McForegroundService` 持有：
- 前台通知（Android 13+ 需 `POST_NOTIFICATIONS` 权限）
- `WAKE_LOCK` 防止 CPU 休眠
- `WIFI_MODE_FULL_HIGH_PERF` Wi-Fi 锁防止网络断开
- `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` 类型

## 4. 内网穿透

### 4.1 架构设计

`TunnelManager` 统一管理三种穿透方式，暴露 `TunnelState` StateFlow 供 UI 订阅：

```kotlin
data class TunnelState(
    val isRunning: Boolean,
    val publicUrl: String,      // 公网地址
    val status: TunnelStatus,  // Idle/Starting/Running/Failed/Stopped
    val errorMessage: String,
    val activeType: TunnelType?
)
```

### 4.2 frp

- **安装**：`apt install frp`，通过 `findAptBinary()` 查找 frpc 路径
- **配置**：生成 TOML 配置文件，包含 server_addr / server_port / token / proxy
- **运行**：直接执行 frpc，通过 `loginFailExit = false` 实现重试

### 4.3 Cloudflare Tunnel

**两种安装策略**（关键设计，解决 Android DNS 限制）：

1. **优先 apt 安装**：`apt install cloudflared`
   - Termux 包用 `GOOS=android` 编译，使用 Android 原生 DNS 机制
   - 通过 `termux-exec` 机制绕过 W^X 执行限制
   - DNS 正常，无需额外处理

2. **回退 GitHub 下载**：从 GitHub releases 下载 `GOOS=linux` 编译的二进制
   - 存在 DNS 问题：Go runtime 读 `/etc/resolv.conf`，Android 无此文件
   - 解决方案：用 `--edge` 参数指定 Cloudflare 边缘节点 IP，绕过 DNS 解析
   - 通过 binary 路径前缀判断来源：apt 版在 `$PREFIX/bin/`，GitHub 版在 `home/tunnel/bin/`

```kotlin
val isAptVersion = binary.startsWith("${termux.installer.rootDir.absolutePath}/bin/")
val needEdge = !isAptVersion  // GitHub 版需要 --edge 绕过 DNS
```

### 4.4 ngrok

- **安装**：从 equinox.io 下载 tgz，用系统 tar 解压
- **配置**：`ngrok config add-authtoken` 设置 token
- **DNS 问题**：同 cloudflared GitHub 版，但无 `--edge` 类似参数，可能因 DNS 失败

### 4.5 监控线程

每个隧道进程启动后，`startMonitorThread` 后台线程：
- 读取 stdout 推送到 `consoleFlow`
- 正则匹配公网地址（`*.trycloudflare.com` / `Forwarding tcp://`）
- 30 秒超时 watchdog：未获得公网地址则强制终止
- 进程退出时更新状态为 Failed

### 4.6 为什么放弃 proot？

proot 在 Android 上无法作为 `ld-linux` 替代：
- Go PIE 二进制有 `PT_INTERP=/lib/ld-linux-aarch64.so.1`，Android 无此文件
- 绑定 Android 的 `linker64` 到该路径无效：bionic linker 与 glibc ld-linux 行为不同
- proot 无法创建临时目录（`PROOT_TMP_DIR` 权限问题）

最终方案：**优先 apt 安装**（根本解决 DNS 和执行权限），**回退 GitHub 下载 + `--edge` 参数**（绕过 DNS）。

## 5. UI 架构

### 5.1 Compose + Material 3

- 单 Activity + Compose Navigation
- `McViewModel` 持有所有状态，通过 `StateFlow` 暴露给 UI
- `McApp.kt` 实现底部导航栏（仪表盘 / 网络 / 下载 / 日志 / 设置）

### 5.2 主要页面

| 页面 | 功能 |
|---|---|
| DashboardScreen | 服务器启停、状态监控、TPS/内存/玩家数 |
| NetworkScreen | 本地/局域网地址、内网穿透配置、隧道日志 |
| DownloadScreen | PaperMC 版本选择和下载 |
| LogsScreen | 实时日志流（FileObserver） |
| PlayersScreen | 在线玩家、踢人、白名单 |
| PluginsScreen | 插件列表、启用/禁用 |
| BackupScreen | 快照管理、自动备份 |
| PropertiesScreen | server.properties 编辑 |
| SettingsScreen | 下载源、APT 镜像、Termux 环境管理 |

## 6. 数据持久化

### 6.1 DataStore

`McConfig` 通过 DataStore Preferences 持久化，包含：
- 服务器配置（端口、堆内存、自动重启）
- 隧道配置（frp/cloudflared/ngrok 参数）
- 下载源和 APT 镜像设置
- 已安装核心列表（`installedCores`）

### 6.2 服务器状态

`ServerState` 是内存态，由 `McForegroundService` 推送：
- 运行状态、TPS、在线玩家数、内存占用
- 安装步骤进度（JDK/Wget/Frp/Proot）

## 7. 关键技术决策

### 7.1 targetSdk = 28

Android 10+ 的 SELinux W^X 策略禁止从 app data 目录执行二进制。targetSdk 28 规避此限制，让 Termux 环境的二进制（bash/java/frpc/cloudflared）能直接通过 `execve` 执行。

### 7.2 不依赖 Termux App

应用内置完整的 Termux rootfs 和包管理器，不需要用户安装 Termux App，降低使用门槛。

### 7.3 去 tmux 化

早期版本依赖 tmux 管理 MC 进程，但 tmux 未安装时整个应用不可用。改用 Android 原生 `ProcessBuilder` 直接管理进程，更简单可靠。

### 7.4 多镜像源

Termux rootfs 和隧道二进制下载支持 7 个镜像源（GitHub 直连 + 6 个镜像站），按顺序尝试，解决国内网络问题。APT 源支持 5 个镜像（清华/阿里云/中科大/南大/官方）。

## 8. 构建配置

### 8.1 ABI 分包

```kotlin
splits {
    abi {
        isEnable = true
        include("arm64-v8a", "x86_64")
        isUniversalApk = false
    }
}
```

仅打包 arm64-v8a 和 x86_64，避免 bootstrap 多架构冗余。

### 8.2 签名

使用 debug keystore 签名 release 包，便于直接安装：

```kotlin
signingConfig = signingConfigs.getByName("debug")
```

## 9. 已知限制

- **cloudflared GitHub 下载版**：DNS 可能失败，需 `--edge` 参数绕过（apt 安装版无此问题）
- **ngrok GitHub 下载版**：DNS 可能失败，无完美解决方案（建议用 frp 或 cloudflared apt 版）
- **Android 10+ 执行限制**：通过 targetSdk 28 规避，但未来 Android 版本可能进一步限制
- **内存占用**：MC 服务端 + Java 运行时，建议至少 4GB RAM

## 10. 模块依赖关系

```
McApp (UI)
  └── McViewModel
        ├── ServerRepository
        │     └── TermuxRuntime
        │           ├── BootstrapInstaller
        │           └── CommandExecutor
        ├── McServerController ── (依赖) ── TermuxRuntime
        ├── TunnelManager ────── (依赖) ── TermuxRuntime
        ├── BackupManager
        ├── PlayerManager
        └── PluginManager

McForegroundService
  ├── McServerController
  └── TermuxRuntime
```

## 11. 关键类职责

| 类 | 职责 |
|---|---|
| `BootstrapInstaller` | 下载和解压 Termux rootfs，管理 SHA256 校验和镜像源 |
| `CommandExecutor` | 执行 Termux 命令，管理环境变量和日志流 |
| `TermuxRuntime` | 进程管理（MC/隧道），Java wrapper 脚本修复 |
| `McServerController` | MC 服务端生命周期，启动参数构造 |
| `TunnelManager` | 三种穿透方式统一管理，公网地址提取 |
| `McForegroundService` | 前台服务保活，唤醒锁和通知 |
| `McViewModel` | UI 状态管理，业务逻辑协调 |
| `CrashReportManager` | 崩溃日志收集和分享 |
