# MCServerManager

一个 Android 原生的 Minecraft Java 版服务端管理器，在手机上直接运行 Paper / Fabric / Forge / Vanilla 服务端，并内置三种内网穿透方案（frp / Cloudflare Tunnel / ngrok），让朋友通过公网地址加入你的服务器。

## 特性

- **纯 Android 原生运行**：内置 Termux bootstrap rootfs，无需 root，无需 Termux App，应用内直接运行 Java 服务端
- **多核心管理**：支持 Paper / Fabric / Forge / Vanilla，每个核心独立目录互不干扰
- **PaperMC 自动下载**：集成 PaperMC v3 API，自动获取最新稳定版核心
- **三种内网穿透**：
  - **frp**：自建服务器，功能最全，支持自定义域名
  - **Cloudflare Tunnel**：零配置 Quick Tunnel，或绑定 Cloudflare 域名
  - **ngrok**：快速分享，免费层有随机域名
- **进程托管**：前台服务 + Wi-Fi/CPU 唤醒锁，灭屏不断连
- **实时日志**：FileObserver 监视日志文件，带 ANSI 着色
- **玩家管理**：在线列表、踢人、白名单
- **自动备份**：定时快照，保留最近 N 份
- **插件管理**：扫描 plugins/ 目录，一键启用/禁用
- **配置编辑**：server.properties 在线编辑
- **Material 3 UI**：Jetpack Compose 构建，支持深色模式

## 系统要求

- Android 8.0 (API 26) 及以上
- arm64-v8a 或 x86_64 架构
- 建议至少 4GB RAM（运行 MC 服务端）
- 需要网络连接（首次初始化下载 Termux 环境和 JDK）

## 下载安装

### 方式一：直接下载 APK

从 [Releases 页面](../../releases) 下载对应架构的 APK：

- `app-arm64-v8a-release.apk` — 绝大多数手机
- `app-x86_64-release.apk` — 模拟器

### 方式二：自行编译

```bash
git clone <repo-url>
cd MCServerManager
./gradlew.bat assembleRelease
```

生成的 APK 在 `app/build/outputs/apk/release/`。

## 快速开始

1. **初始化环境**：首次打开应用，点击「初始化」按钮，自动下载 Termux rootfs + JDK 25
2. **下载核心**：在「下载」页面选择 Paper / Fabric / Forge / Vanilla 和 MC 版本，点击下载
3. **启动服务**：回到「仪表盘」点击启动按钮
4. **公网联机**（可选）：进入「网络」页面，选择穿透方式并启动
5. **分享地址**：将公网地址分享给朋友，在 Minecraft「多人游戏」→「直接连接」中粘贴

## 内网穿透配置指南

### frp（推荐，需自建服务器）

1. 在有公网 IP 的云服务器上部署 frps：
   ```toml
   bindPort = 7000
   auth.method = "token"
   auth.token = "your-password"
   ```
2. 在应用中填入服务器地址、端口（7000）、Token
3. 点「启动穿透」

### Cloudflare Tunnel（零配置最省事）

1. 保持 Quick Tunnel 开关打开
2. 点「启动穿透」，自动获得 `*.trycloudflare.com` 地址
3. 地址每次重启会变；固定域名需绑定 Cloudflare 托管的域名

### ngrok

1. 在 [ngrok.com](https://ngrok.com) 注册并获取 Authtoken
2. 填入 Authtoken，选择 TCP 模式
3. 点「启动穿透」，获得 `0.tcp.ngrok.io:端口` 地址

## 项目结构

```
MCServerManager/
├── app/
│   ├── src/main/java/com/mcserver/manager/
│   │   ├── data/            # 数据模型与配置
│   │   ├── runtime/         # Termux 运行时
│   │   ├── server/          # 服务端管理（MC/隧道/备份/插件）
│   │   ├── service/         # Android 前台服务
│   │   ├── ui/              # Compose UI
│   │   ├── MainActivity.kt
│   │   └── McApplication.kt
│   ├── src/main/res/        # 资源文件
│   └── build.gradle.kts
├── gradle/
│   └── libs.versions.toml   # 依赖版本目录
├── build.gradle.kts
└── settings.gradle.kts
```

## 技术栈

- **Kotlin** + **Jetpack Compose**（Material 3）
- **Android targetSdk 28**（规避 Android 10+ 的 W^X 执行限制）
- **Termux bootstrap rootfs**（提供 bash / apt / java 运行环境）
- **openjdk-25**（Paper 26.x / MC 26.1+ 要求）
- **Coroutines + Flow**（异步与响应式）
- **DataStore**（配置持久化）

## 常见问题

### 初始化失败怎么办？

- 检查网络连接
- 在设置中切换下载源（GitHub 直连 / 镜像站）
- 删除 Termux 环境后重新初始化

### 服务器启动后无法连接？

- 确认手机和客户端在同一局域网
- 在「网络」页面查看局域网 IP 和端口
- 防火墙放行对应端口

### 内网穿透启动失败？

- **frp**：检查服务端是否运行、Token 是否一致、端口是否放行
- **cloudflared**：apt 安装版 DNS 正常；GitHub 下载版用 `--edge` 参数绕过 DNS
- **ngrok**：检查 Authtoken 是否正确

### 为什么 targetSdk 是 28？

Android 10+ 引入 W^X 策略，禁止从 app data 目录执行二进制。targetSdk 28 规避此限制，让 Termux 环境的二进制能直接执行。

## 开源协议

本项目仅供学习和个人使用。

## 致谢

- [Termux](https://termux.dev/) — 提供 rootfs 和包管理
- [fatedier/frp](https://github.com/fatedier/frp) — 内网穿透
- [cloudflare/cloudflared](https://github.com/cloudflare/cloudflared) — Cloudflare Tunnel
- [PaperMC](https://papermc.io/) — Paper 服务端核心
- [AceDroidX/frp-Android](https://github.com/AceDroidX/frp-Android) — Android 执行 Go 二进制的参考
