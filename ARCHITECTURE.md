# MineServe Mobile 技术架构

## 范围

MineServe Mobile 在无 Root 的 Android 设备上运行 Minecraft 服务端进程。它不是内核容器，也不会修改 Android 系统权限：运行时、Java 包、世界和服务端进程均归应用沙盒所有。

## 运行时与进程链路

```text
Compose UI
  -> McViewModel / Repository (StateFlow + DataStore)
  -> McServerController (下载、安装、启动参数)
  -> TermuxRuntime (环境、进程 I/O、文件)
  -> /system/bin/sh -c "... exec java ..."
  -> Minecraft 服务端子进程
       -> stdin: 控制台命令
       -> stdout/stderr: console flow + logs/latest.log
```

`BootstrapInstaller` 在首次初始化时下载并校验 Termux bootstrap rootfs。`CommandExecutor` 提供 Termux 兼容环境，包括 `PATH`、`LD_LIBRARY_PATH`、`PREFIX`、`HOME` 和临时目录。应用使用 Android `ProcessBuilder`，不依赖 tmux。

Java 17、21、25 在 Termux 运行时中执行。Java 8 使用 `proot` 启动 ARM64 Ubuntu 根文件系统，将应用私有的服务器目录绑定为 `/srv/mineserve`，再在来宾文件系统中执行 `/usr/bin/java`。PRoot 是用户态兼容层，不提供 Root 权限。

## 存储布局

实际 Android 包路径会因安装而变化；以下路径相对 app `filesDir`：

```text
home/
  bin/, lib/, etc/                 Termux 兼容运行时
  home/
    servers/{dirName}/             一个隔离的服务端
      server.jar, eula.txt, server.properties
      plugins/, mods/, worlds/, logs/latest.log
    snapshots/                     备份归档
    crash-logs/                    应用生成的崩溃报告
  var/lib/mineserve/java8-ubuntu-rootfs/  Java 8 PRoot 来宾
tmp/, runtime/, native/            bootstrap/运行时工作目录
```

`TermuxRuntime.serverDirFor(dirName)` 是服务端目录的共同边界。服务端导入和解压通过规范路径校验限制写入范围；TAR 的符号链接和硬链接会被忽略，防止压缩包逃逸。

## 服务端生命周期

`McServerController` 解析所选核心和启动参数，再调用 `TermuxRuntime.startMc`。shell 通过 `exec` 被 Java 替换，便于观察进程归属和退出。`TermuxRuntime` 保留进程 stdin，异步消费合并后的输出，写入 `logs/latest.log`，并在进程退出时调用退出处理。控制器可记录异常退出并应用配置的自动重启策略。

可运行或识别的具体核心为 Paper、Purpur、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord 和 PowerNukkitX。Forge、NeoForge、Quilt 使用各自的 installer 启动准备流程。PowerNukkitX 有独立的 Bedrock 目录与配置处理并使用 UDP 网络，它不是 Java Edition 服务端。

## 下载与导入

下载页通过 `McServerController` 请求核心版本源。请求令牌会阻止旧网络响应在用户切换核心后覆盖新版本列表；界面默认显示最近 8 个版本，并可展开全部结果。

`ServerImporter` 提供互相独立的文件夹、压缩包、JAR 与 Modrinth 整合包入口：

- 压缩包支持 ZIP、TAR、TAR.GZ/TGZ、TAR.XZ/TXZ、TAR.BZ2/TBZ2、TAR.ZST/TZST、TAR.LZ4 和 7z；符合条件时会剥离一层外包装目录。
- JAR、文件夹和压缩包导入通过 `ServerCoreDetector` 根据布局、Manifest 与已知入口类推断核心和版本。
- Modrinth `.mrpack` 导入读取 `modrinth.index.json`，下载服务端必需文件，在提供时校验 SHA-1，跳过 `env.server=unsupported`，并应用 `overrides/` 和 `server-overrides/`。

CurseForge 整合包没有实现直接导入，因为它的元数据/API 流程并不等同于自包含的 `.mrpack`。

## 后台运行与状态

`McForegroundService` 管理前台通知、可选 CPU/Wi-Fi 唤醒锁和可选的可拖动状态悬浮条。服务返回 `START_STICKY`；`BootReceiver`、周期 WorkManager 和 `onTaskRemoved` 的非精确闹钟尝试提供额外恢复路径。这些都只能尽力保活，无法覆盖 Android/厂商的省电策略。

悬浮条从仓库状态获取进程 CPU 和内存。CPU 采样会在系统允许时跟踪子进程；Android 沙盒和 PRoot 对 `/proc` 的限制可能使该值不可用。

## 日志、崩溃与网络

终端输出作为 Flow 共享给 Compose 控制台，并保存到 `logs/latest.log`。发生异常退出时，`CrashReportManager` 从最后 200 行日志和最新原生报告生成应用报告。界面会扫描所有已安装核心的原生 `crash-reports/`，并由 `CrashReportAnalyzer` 在本地执行启发式分析；不会上传崩溃报告。

Minecraft 通过手机的普通网络套接字监听。局域网连接使用手机 LAN IP 和服务端口。FRP 与 bore 仅提供隧道客户端；公网可达性仍取决于所选隧道或用户配置的网络映射，应用不会绕过运营商 CGNAT 或 NAT。

## 关键类

| 类 | 职责 |
|---|---|
| `McApplication` | 应用级运行时、仓库、bootstrap 状态 |
| `McViewModel` | UI 状态与功能协调 |
| `BootstrapInstaller` | bootstrap 下载、校验和解压 |
| `CommandExecutor` | shell 环境与命令输出 Flow |
| `TermuxRuntime` | Java/PRoot 启动、进程 I/O、运行时文件 |
| `McServerController` | 核心版本、下载、installer、启动 |
| `ServerImporter` / `ServerCoreDetector` | 服务端/整合包导入和识别 |
| `CrashReportManager` / `CrashReportAnalyzer` | 崩溃捕获、汇总、本地诊断 |
| `McForegroundService` / `StatusOverlay` | 前台寿命、唤醒锁、状态显示 |
| `TunnelManager` | FRP 和 bore 隧道处理 |
