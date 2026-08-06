# MineServe Mobile

<div align="center">

# ✅ 可以正常使用

**在安卓手机上直接运行 Minecraft Java 版服务端——无需 root。**

[English](./README.md) · [架构文档](./ARCHITECTURE.md)

</div>

MineServe Mobile 是一款 Android 原生应用，可在手机上直接运行 Minecraft Java 版服务端，支持 **9 类服务端核心**、多核心切换管理、插件与模组管理、Modrinth 对接、内置内网穿透、自动备份、崩溃报告、后台保活，以及免 Root 数据桥接（MT 管理器）。

## ✨ 特性

- **无需 root**：内置 Termux bootstrap rootfs + JDK 25，全部在应用内运行
- **9 类服务端核心**：Paper、Purpur、Fabric、Forge、NeoForge、Quilt、Vanilla、Velocity、BungeeCord（NeoForge/Quilt 自动执行 installer）
- **多核心管理**：多个核心并存于隔离目录，概览页一键切换激活核心，各核心独立配置/世界/插件/模组
- **插件管理**：扫描 plugins/，`-` 前缀启停、删除（可选清理数据目录）、SAF 上传、URL 安装（失败自动回滚）
- **模组管理**：扫描 mods/（Fabric/Forge），`.jar.disabled` 启停、删除、上传、URL 安装
- **Modrinth 对接**：搜索模组（多加载器筛选 + 下载量/相关性/最新排序）、模组图标、一键安装（自动匹配 MC 版本 + 加载器，不兼容核心给出警告）
- **玩家管理**：在线玩家列表（踢出/切换模式/OP/给予经验）、进服离服历史（精确到秒，持久化 500 条上限）、OP/白名单/封禁列表（限时封禁、OP 等级、搜索与详情）
- **内网穿透**：frp（粘贴完整 `frpc.toml`，autoTLS 自动过滤，多镜像并行下载）+ bore（纯 Kotlin，无需下载二进制）
- **备份还原**：主世界/地狱/末地三维度快照，创建/列表/还原/删除/SAF 导出，zip-slip 防护，按数量自动清理
- **崩溃报告**：自建崩溃日志（`latest.log` 最后 200 行）+ MC 原生 `crash-reports/`，列表/查看/删除/清空
- **免 Root 访问 data 目录**：内置 `MTDataFilesProvider`（完整 CRUD DocumentsProvider），MT 管理器可直接读写应用私有数据（含图文教程 + 教学视频）
- **后台保活**：前台服务（START_STICKY + special-use 类型 + 唤醒/WiFi 锁）+ 开机自启 + WorkManager 15 分钟周期保活 + onTaskRemoved 闹钟重启
- **设备与网络监控**：内存/存储/电池（含充电状态）+ 实时网络流量与速度，3 秒轮询
- **完整 server.properties 编辑**：75 个参数，基础/高级分组，数据驱动渲染，未收录参数兜底编辑
- **概览地址卡片**：局域网 + 公网穿透地址带复制按钮，一键打开 MC 终端
- **彩色 MC 终端**：日志分级着色（ERROR/WARN/隧道/崩溃/启动），智能自动滚动，快捷指令，一键复制，抽象命名空间 Socket 多客户端广播
- **Material 3 UI**：Jetpack Compose 构建，沉浸式全屏，9-tab 底部导航，输入防抖

## 📋 系统要求

- Android 8.0 (API 26) 及以上
- arm64-v8a 架构（正式版仅构建 arm64）
- 建议至少 4GB RAM（运行 MC 服务端）
- 需要网络连接（首次初始化下载 Termux 环境和 JDK 25）

## 📥 下载安装

### 方式一：直接下载 APK

从 [Releases 页面](../../releases) 下载：

- `app-arm64-v8a-release.apk` — 绝大多数手机


### 方式二：自行编译

```bash
git clone <repo-url>
cd MineServeMobile
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

## 📖 功能详解

### 🧩 服务端核心（9 类）
- **Paper**：高性能、插件兼容，生产推荐
- **Purpur**：Paper 分支，更多原版特性开关
- **Fabric / Forge**：经典模组加载器，自动检测最新版本
- **NeoForge / Quilt**：自动执行 installer（`--installServer` / `quilt install server --download-server`），再用生成的启动文件启动
- **Vanilla**：Mojang 官方服务端（来自版本清单）
- **Velocity / BungeeCord**：代理端（PaperMC v3 API / Jenkins）
- 版本列表自动从官方 API 拉取（PaperMC、Purpur、Mojang、Fabric meta、Forge promotions、NeoForge maven、Quilt meta）；支持自定义版本字符串
- HTTP 请求 3 次重试、超时递增；下载进度/速度每 500ms 回报

### 🎮 服务器控制
- 一键启停，启动状态（运行时长 + TPS/在线/内存统计）
- 崩溃自动重启（可配置，最多 3 次重试，间隔 3 秒，默认关闭）
- 异常退出时自动捕获崩溃报告
- MC 终端，含快捷指令与彩色日志
- 启动设置弹窗（控制卡片齿轮图标）——JVM 堆内存、自动重启开关、核心选择
- 实时 TPS 解析（Paper）+ 真实 RSS 内存监控（遍历 `/proc`）

### 🖥️ MC 终端（日志）
- **彩色日志**：`ERROR`/`FATAL` 红 / `WARN` 黄 / `[tunnel]` 蓝 / `[crash]` 橙 / `[bootstrap]` 绿
- **智能自动滚动**：仅在位于底部附近时跟随新日志，上翻阅读不被打断
- **快捷指令**：一键 `/list` `/tps` `/say` `/kick` `/help`，支持一键复制全部日志（提示行数）
- 1000 行环形缓冲，100ms 批量刷新 UI 以减少重组
- **ConsoleSocketServer**：抽象命名空间 `LocalServerSocket("mc-console")` 与 MC 进程共享，多客户端广播，自动清理死连接
- 概览页「打开 MC 终端」按钮直达日志页

### 📊 概览地址卡片
- 局域网地址（`127.0.0.1:port` / `192.168.x.x:port`）与内网穿透公网地址，各带复制按钮
- 服务器核心/插件/玩家状态一览
- 一键「打开 MC 终端」
- 设备状态卡片：内存/存储/电池（含充电状态）/总上传下载/实时上下行速度，3 秒轮询

### 👥 玩家管理
- **在线玩家列表**：实时来自 `list` 指令 + 日志解析，支持复制 / 踢出 / 切换游戏模式 / OP / 给予经验
- **进服离服历史**：精确到秒，持久化至 `player_history.json`（500 条上限，互斥锁写入，启动时去重合并）
- OP / 白名单 / 封禁列表（含限时封禁 7 档：永久/30分/1时/6时/1天/7天/30天，OP 等级 1–4，分页搜索与详情弹窗）
- 游戏模式双语法派发（兼容新旧版 MC）

### 🔌 插件管理
- 已安装插件列表（并行扫描 + `plugin.yml` / `paper-plugin.yml` 元数据，按 lastModified 缓存），`-` 前缀启停、删除（可选清理数据目录）
- 本地 SAF 上传 & URL 安装（备份 → 下载 → 失败回滚，≥1024 字节校验）
- 资源站目录（SpigotMC / Hangar / Modrinth / BuiltByBit / CurseForge）
- 热重载按钮，搜索 + 4 筛选标签（全部/启用/禁用/本地）
- 核心兼容性指示

### 🧪 模组管理
- `mods/` 目录扫描（Fabric/Forge），`.jar.disabled` 启停、删除、上传、URL 安装（含回滚）
- **Modrinth 对接**：搜索（多加载器筛选、下载量/相关性/最新排序）、图标展示、一键安装（自动匹配 MC 版本 + 加载器，不兼容核心给出警告，下载量格式化展示）

### 📁 文件管理（免 Root）
- 浏览服务器目录、SAF 上传、新建文件夹、删除（递归，带确认）
- **向上 / 刷新**按钮快速导航
- **导出**：单文件 / 文件夹 zip / 整服 zip，SAF 存储到任意本地路径
- **MT 管理器集成**：内置 `MTDataFilesProvider`（完整 CRUD `DocumentsProvider`，含 MT 专属 `mt:setLastModified` / `mt:setPermissions` / `mt:createSymlink` 调用），在 MT 管理器中「添加本地存储 → 选择本应用」即可读写 `data` 目录；应用内提供下载链接 + 5 步图文教程 + 教学视频（进入 `data → files → home → home → servers` 查看服务器文件）
- 常见服务器文件/文件夹自动标注说明（19+ 条：`server.jar`、`server.properties`、`ops.json`、`whitelist.json`、`banned-players.json`、`eula.txt`、`bukkit.yml`、`spigot.yml`、`paper.yml`、`world`、`world_nether`、`world_the_end`、`logs`、`plugins` 等）

### 🌐 内网穿透
- **frp**：粘贴完整 `frpc.toml` 配置文本（服务端地址、token、代理规则一次配好）；自动过滤 `autoTLS` 字段以兼容旧版 frpc；frpc 缺失时自动下载（固定 v0.61.2，**5 个镜像并行尝试**——直连 / gh-proxy.com / mirror.ghproxy.com / ghproxy.net / ghfast.top——首个成功即用，apt 兜底）；针对性 `diagnoseFailure` 提示（未知字段 / 连接拒绝 / token / 端口占用 / 超时）
- **bore**：纯 Kotlin 客户端，直接实现 ekzang/bore 协议，无需下载二进制，默认控制端口 7835，连接任意 `bore server` VPS 即可
- 公网地址同步显示在概览卡片与网络页，均带复制按钮
- 免费 FRP 平台目录（OpenFrp / ChmlFrp / StarryFrp / SakuraFrp）一键跳转

### 💾 备份与还原
- 主世界/地狱/末地三维度快照（zip），保留目录层级
- 创建（自动 `save-all`）、列表、还原（自动停服并备份当前世界至 `*.bak.<ts>`）、删除、SAF 导出
- 还原时 zip-slip / 路径穿越防护
- 旧快照按数量自动清理（`maxSnapshots`，默认 10）

### 🛠️ 崩溃报告
- **自建报告**：`home/crash-logs/crash_yyyyMMdd_HHmmss.txt`，捕获 `latest.log` 最后 200 行 + 最新原生崩溃报告
- **MC 原生报告**：`home/servers/{dirName}/crash-reports/`
- 列表 / 查看（含预览）/ 删除 / 一键清空

### ⚙️ 配置
- 完整 `server.properties` 编辑器：全部 **75 个参数**，基础（37）/ 高级（38）分组，数据驱动渲染（Bool/Int/Text/Enum），未收录参数兜底自由编辑，服务器运行时保存提示需重启
- JVM 堆内存限制、下载镜像（7 选）、APT 镜像（5 选）、自动重启开关、WiFi 锁、CPU 唤醒锁

### 📊 设备与网络监控
- 设备内存 / 存储 / 电池（含充电状态），无需任何权限
- 实时网络流量：总上传下载 + 当前速度，3 秒轮询
- 将真实 MC 进程 RSS 写入 `usedMemoryMb`

### 🛡️ 后台保活

纯 Android 方案（无需 NDK）：
- 前台服务 START_STICKY + 常驻通知 + PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF，Android 13+ 使用 special-use 前台类型
- 开机自启动（BOOT_COMPLETED 广播）
- WorkManager 每 15 分钟周期保活
- onTaskRemoved → 2 秒后非精确闹钟重启（`setAndAllowWhileIdle`，无需精确闹钟权限）
- 30 秒健康看门狗——每 60 秒发送 `list`（全核心）+ `tps`（Paper）刷新在线人数与 TPS
- 专属保活页面：3 个开关独立配置（开机自启 / 周期保活 / 崩溃自动重启）+ 详细功能说明

> 提示：Android 系统对后台进程有严格限制，保活效果受系统版本与厂商策略影响；请勿在系统设置中限制本应用后台运行，并授予自启动权限。

### 🎨 UI & UX
- Material 3，沉浸式全屏，**9-tab 底部导航**（概览 / 下载 / 玩家 / 插件 / 文件 / 网络 / 备份 / 配置 / 设置）
- 输入防抖（300ms 写回，失焦立即同步，仅数字 sanitize）——输入无卡顿、无重排
- 返回键逐页返回而非退出应用（日志浮层 → 子页面 → 非概览 Tab → 概览拦截返回）
- 配置持久化 300ms 防抖（DataStore 经 conflated channel，CAS 风格原子更新）

### 📥 下载帮助
- 专属页面应对 GitHub 下载慢：切换镜像（7 个内置）、切换 APT 源、切换网络、VPN/代理、PC 手动下载
- 速度参考卡（5+ MB/s 快 / 1–5 良好 / 0.5–1 正常 / <0.5 慢）

## 📄 许可证

详见仓库 LICENSE 文件。
