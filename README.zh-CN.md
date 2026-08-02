# JavaMC GO

<div align="center">

# ✅ 可以正常使用

**在安卓手机上直接运行 Minecraft Java 版服务端——无需 root。**

[English](./README.md) · [架构文档](./ARCHITECTURE.md)

</div>

JavaMC GO 是一款 Android 原生应用，可在手机上直接运行 Minecraft Java 版服务端，支持 **9 类服务端核心**、插件与模组管理、内置内网穿透、自动备份与后台保活。

## ✨ 特性

- **无需 root**：内置 Termux bootstrap rootfs + JDK，全部在应用内运行
- **9 类服务端核心**：Paper、Purpur、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord（NeoForge/Quilt 自动执行 installer）
- **插件管理**：扫描 plugins/ 目录，启用/禁用、删除、本地上传、URL 安装
- **模组管理**：扫描 mods/ 目录（Fabric/Forge），`.jar.disabled` 启停、上传
- **Modrinth 对接**：搜索模组（多加载器筛选 + 下载量/相关性/最新排序）、一键安装、模组图标展示
- **玩家管理**：在线玩家列表（踢出/切换模式/OP）、进服离服历史（精确到秒）、OP/白名单/封禁列表
- **内网穿透**：frp + bore 内置穿透
- **备份还原**：主世界/地狱/末地三维度快照，支持还原与 SAF 导出到本地
- **后台保活**：前台服务 + 开机自启 + WorkManager 周期保活
- **设备与网络监控**：内存/存储/电池 + 实时网络流量与速度
- **完整 server.properties 编辑**：73 个参数基础/高级分组，底部导航「配置」tab 直达
- **Material 3 UI**：Jetpack Compose 构建，沉浸式全屏设计

## 📋 系统要求

- Android 8.0 (API 26) 及以上
- arm64-v8a 或 x86_64 架构
- 建议至少 4GB RAM（运行 MC 服务端）
- 需要网络连接（首次初始化下载 Termux 环境和 JDK）

## 📥 下载安装

### 方式一：直接下载 APK

从 [Releases 页面](../../releases) 下载：

- `app-arm64-v8a-release.apk` — 绝大多数手机
- `app-x86_64-release.apk` — 模拟器

### 方式二：自行编译

```bash
git clone <repo-url>
cd MCServerManager
./gradlew.bat assembleRelease
```

生成的 APK 在 `app/build/outputs/apk/release/`。

## 🚀 快速开始

1. **初始化环境**：首次打开应用，点击「初始化」，自动下载 Termux rootfs + JDK 25
2. **下载核心**：切到「下载」Tab，选择核心类型（推荐 Paper）与版本
3. **启动服务器**：首次启动需下载运行文件，请耐心等待
4. **加入游戏**：通过概览页局域网地址连接，或使用内置内网穿透公网访问

## 🧩 支持的核心

| 核心 | 插件 | 模组 | 说明 |
|---|---|---|---|
| Paper | ✅ | ❌ | 高性能优化核心，生产推荐 |
| Purpur | ✅ | ❌ | Paper 分支，更多特性开关 |
| Fabric | ❌ | ✅ | 轻量级模组加载器 |
| Forge | ❌ | ✅ | 老牌模组加载器 |
| NeoForge | ❌ | ✅ | Forge 继任者（installer 流程） |
| Quilt | ❌ | ✅ | Fabric 分支（installer 流程） |
| Vanilla | ❌ | ❌ | 官方原版 |
| Velocity | ❌ | ❌ | 代理端 |
| BungeeCord | ❌ | ❌ | 代理端 |

## 🛡️ 后台保活

纯 Android 方案（无需 NDK）：
- 前台服务 START_STICKY + 常驻通知
- 开机自启动（BOOT_COMPLETED 广播）
- WorkManager 每 15 分钟周期保活
- 专属保活页面：各开关独立配置 + 详细功能说明

> 提示：Android 系统对后台进程有严格限制，保活效果受系统版本与厂商策略影响；请勿在系统设置中限制本应用后台运行，并授予自启动权限。

## 📄 许可证

详见仓库 LICENSE 文件。
