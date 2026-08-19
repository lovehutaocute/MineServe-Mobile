# MineServe Mobile

<div align="center">

# ✅ 可以正常使用

**在安卓手机上直接运行 Minecraft 服务端，无需 root。**

[English](./README.md) · [架构文档](./ARCHITECTURE.md) · [更新日志](./CHANGELOG.md)

</div>

MineServe Mobile 是一款 Android 原生应用，可在手机上运行 Minecraft Java 版服务端，也支持 PowerNukkitX 基岩版核心。它提供多核心管理、插件与模组管理、Modrinth 对接、内网穿透、自动备份、崩溃报告、后台保活，以及免 Root 数据访问。

## ✨ 特性

- **无需 root**：内置 Termux bootstrap 运行时；Java 17/21/25 直接运行，旧版 Java 8 服务端使用 ARM64 Ubuntu PRoot。
- **10 类核心**：Paper、Purpur、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord、PowerNukkitX。
- **多核心管理**：多个服务器隔离存放，一键切换；世界、配置、插件和模组互不干扰。
- **导入服务器**：文件夹、压缩包、单个 JAR 与 Modrinth `.mrpack` 独立导入入口，自动识别核心和版本。
- **插件与模组**：扫描、启停、SAF 上传、URL 安装和 Modrinth 搜索下载。
- **内网穿透**：frp 与 bore；概览页展示局域网和公网地址。
- **备份与崩溃报告**：世界/整服备份、SAF 导出；异常退出保存最近日志与原生报告，并汇总已安装服务器的原生崩溃报告。
- **后台保活**：前台服务、唤醒锁、开机自启、周期检查和可选状态悬浮条。
- **Material 3 UI**：Jetpack Compose 构建，紧凑核心选择和版本列表。

## 📋 系统要求

- Android 8.0（API 26）及以上。
- `arm64-v8a` 架构。
- 建议至少 4 GB RAM，并为运行时、世界和备份预留空间。
- 首次初始化、下载核心和整合包导入需要网络。

## 📥 下载安装

### 方式一：直接下载 APK

从 [Releases 页面](../../releases) 下载适用于手机的 `arm64-v8a` APK。

### 方式二：自行编译

```powershell
git clone https://github.com/lovehutaocute/MineServe-Mobile.git
cd MineServe-Mobile
.\gradlew.bat :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/`。

## 🚀 快速开始

1. **初始化环境**：首次打开应用，完成运行时初始化并安装所需 Java。
2. **下载或导入**：在“下载”页选择核心与版本，或打开“导入服务器”。
3. **接受 EULA**：阅读并接受服务端生成的 `eula.txt`。
4. **启动并连接**：在概览页启动；局域网玩家使用显示的手机 IP 与端口连接。

公网访问请配置 frp/bore 或自行完成路由器端口映射。

## 🧩 支持的核心

| 核心 | 插件 | 模组 | 说明 |
|---|---|---|---|
| Paper | ✅ | ❌ | 高性能 Java 插件服 |
| Purpur | ✅ | ❌ | Paper 分支，更多特性开关 |
| Fabric | ❌ | ✅ | 轻量级模组加载器 |
| Forge | ❌ | ✅ | 传统模组加载器 |
| NeoForge | ❌ | ✅ | Forge 继任者，installer 流程 |
| Quilt | ❌ | ✅ | Fabric 分支，installer 流程 |
| Vanilla | ❌ | ❌ | 官方原版 |
| Velocity | ❌ | ❌ | Java 代理端 |
| BungeeCord | ❌ | ❌ | Java 代理端 |
| PowerNukkitX | ✅ | ❌ | 基岩版 Nukkit 核心，使用 UDP |

## 📖 功能详解

### 🧩 服务端核心与下载

- 版本列表从各核心的官方 API 获取；支持自定义版本字符串。
- 下载页默认显示最近 8 个版本，可展开完整列表。
- 切换核心会立即清空旧版本，并忽略晚到的旧请求结果。
- Forge、NeoForge、Quilt 会在下载后执行对应 installer。

### 📥 导入服务器与整合包

- **文件夹 / JAR**：导入已有服务端并自动识别核心与版本。
- **压缩包**：支持 ZIP、TAR、TAR.GZ/TGZ、TAR.XZ/TXZ、TAR.BZ2/TBZ2、TAR.ZST/TZST、TAR.LZ4、7z；自动剥离单层包装目录并防止路径穿越。
- **Modrinth `.mrpack`**：下载服务端所需文件、校验 SHA-1、跳过不适用于服务端的文件，并应用 `overrides/` 与 `server-overrides/`。

> CurseForge 整合包依赖外部元数据/API，当前暂不支持直接导入。

### 🎮 服务器控制与终端

- 一键启停、JVM 堆内存设置、可选崩溃自动重启。
- 彩色 MC 终端、快捷指令、日志复制和智能自动滚动。
- 实时展示运行时长、在线玩家、TPS 与内存等服务状态。

### 🔌 插件、模组与文件

- 插件和模组扫描、启停、删除、SAF 上传、URL 安装。
- Modrinth 搜索与按 Minecraft 版本、加载器匹配下载。
- 浏览服务端文件、创建目录、编辑文本、导入导出与整服压缩备份。
- 通过内置 DocumentsProvider，兼容的文件管理器可在免 Root 条件下访问应用数据。

### 💾 备份、崩溃与网络

- 主世界、地狱、末地的快照备份、还原、删除和 SAF 导出。
- 异常退出时保存 `latest.log` 最后 200 行和最新原生报告；报告页面汇总全部服务器的原生 `crash-reports/` 并提供离线分析。
- frp 与 bore 隧道支持；局域网地址和公网地址均可一键复制。

### 🛡️ 后台保活

- 前台服务、常驻通知、CPU/Wi-Fi 唤醒锁、开机自启和 WorkManager 周期检查。
- 可选可拖动状态悬浮条，展示运行状态、CPU 和内存。

> Android 与厂商省电策略仍可能终止后台进程；请关闭本应用的电池优化并保持供电。CPU 读数受 Android/PRoot 的 `/proc` 限制，部分设备上可能不可用。

## 📱 手机是如何运行 MC 服务端的？

首次初始化时，应用会在私有目录部署 Termux 兼容运行时和 Java。Java 17/21/25 服务端通过 Android shell 启动；需要 Java 8 的旧服务端则在 ARM64 Ubuntu PRoot 中运行。服务端始终是应用管理的普通 Java 子进程，不需要 Root。

控制台命令写入进程 stdin，输出实时显示并保存为 `logs/latest.log`。服务端监听手机正常网络端口：局域网使用手机 IP 连接；公网则依赖隧道或端口映射，无法绕过 NAT、CGNAT 和防火墙。

详细实现见 [架构文档](./ARCHITECTURE.md)。

## 📄 许可证

本项目采用 [GNU GPL v3.0 或更高版本](LICENSE)。TCP STUN 穿透和部分后台保活实现改编自同为 GPL-3.0 的 [EdgeCube](https://github.com/venti1112/EdgeCube)，详见 [NOTICE](NOTICE)。
