# Git 提交节点计划（本次会话全部改动）

本机未安装 git 二进制，无法直接执行提交；以下按版本节点给出精确命令。
任意装有 git 的机器（或安装 git / 使用 Android Studio 内置 git）运行 `commit-nodes.ps1`（Windows）
或 `commit-nodes.sh`（Linux/macOS/CI）即可自动按序创建全部提交节点。

> 说明：提交按「版本节点」对最终工作区文件分组。部分文件在多个节点被修改，
> 其最终内容随**首要节点**一并提交，因此中间节点的快照为里程碑分组而非逐字节还原，
> 最终状态在最后一个节点完整、可构建。提交信息已覆盖各节点的全部功能。

## 节点总览

| 节点 | 版本 | 主题 | 提交信息 |
|---|---|---|---|
| 1 | v1.1.4-α | 服务器导入 | feat(import): 新增服务器文件夹/压缩包导入，自动剥离包裹目录并识别核心版本 |
| 2 | v1.1.4-β | 终端体验 | fix(terminal): 修复输入框溢出；快捷指令加 emoji；默认开启日志汉化 |
| 3 | v1.1.4-γ | 细节完善 | feat(terminal): 回车发送/占位提示/执行中反馈；日志页汉化扩充；概览导入入口 |
| 4 | v1.2.0 | 备份与体验大版本 | feat(v1.2.0): targetSdk 28 定版（受限 ROM 执行策略）、崩溃日志、导入确认+进度、备份校验/重命名/错峰、终端历史/清空/聚焦、核心修复、Modrinth 分页、插件冲突 |
| 5 | v1.2.1 | 工程化 | test(lint/ci): 27 个单元测试、AutoMirrored/lint 清理、GitHub Actions、纯逻辑提取 |
| 6 | v1.2.2 | 字符串收尾+依赖升级 | refactor(i18n): 全部硬编码中文迁入资源；chore(deps): AGP 8.7.3/Kotlin 2.0.21/Compose 2024.12 升级 |
| 7 | 发布文档 | 更新日志与脚本 | docs: 更新日志、提交计划与一键提交脚本 |

## 各节点文件清单（脚本内部使用，供核对）

### 节点 1 — 服务器导入
```
app/src/main/java/com/mineserve/mobile/server/ServerCoreDetector.kt
app/src/main/java/com/mineserve/mobile/server/ServerImporter.kt
app/src/main/java/com/mineserve/mobile/ui/McViewModel.kt
app/src/main/java/com/mineserve/mobile/ui/screens/BackupScreen.kt
```
### 节点 2 — 终端体验
```
app/src/main/java/com/mineserve/mobile/ui/screens/TerminalScreen.kt
app/src/main/java/com/mineserve/mobile/ui/TerminalLogTranslator.kt
```
### 节点 3 — 细节完善
```
app/src/main/java/com/mineserve/mobile/ui/screens/LogsScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/DashboardScreen.kt
app/src/main/java/com/mineserve/mobile/ui/McApp.kt
```
### 节点 4 — P0+P1 大版本
```
app/build.gradle.kts
.gitignore
app/src/main/java/com/mineserve/mobile/server/BackupManager.kt
app/src/main/java/com/mineserve/mobile/service/McForegroundService.kt
app/src/main/java/com/mineserve/mobile/server/PluginManager.kt
app/src/main/java/com/mineserve/mobile/ui/screens/DiagnosticsScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/UpdateDialog.kt
app/src/main/java/com/mineserve/mobile/ui/screens/MoreScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/DownloadScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/PluginsScreen.kt
```
### 节点 5 — 工程化
```
app/src/main/java/com/mineserve/mobile/server/ServerImportLayout.kt
app/src/main/java/com/mineserve/mobile/server/McServerController.kt
app/src/test/java/com/mineserve/mobile/server/ServerImportLayoutTest.kt
app/src/test/java/com/mineserve/mobile/server/ServerCoreDetectorTest.kt
app/src/test/java/com/mineserve/mobile/ui/TerminalLogTranslatorTest.kt
.github/workflows/build.yml
```
### 节点 6 — 字符串收尾 + 依赖升级
```
gradle/libs.versions.toml
app/src/main/res/values/strings.xml
app/src/main/java/com/mineserve/mobile/ui/screens/PropertiesScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/SettingsScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/CrashReportsScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/PlayersScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/ServerManagementScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/ServerIconScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/TextFileEditorScreen.kt
app/src/main/java/com/mineserve/mobile/ui/components/SharedComponents.kt
app/src/main/java/com/mineserve/mobile/ui/screens/FileManagerScreen.kt
app/src/main/java/com/mineserve/mobile/ui/screens/KeepAliveScreen.kt
docs/UPGRADE-2024-12.md
```
### 节点 7 — 发布文档
```
CHANGELOG.md
docs/GIT-COMMITS.md
commit-nodes.ps1
commit-nodes.sh
```

## 手动执行（等效于脚本）

```bash
git add <节点1文件> && git commit -m "feat(import): ..."
git add <节点2文件> && git commit -m "fix(terminal): ..."
# ...依次执行 7 个节点
```

## 执行后

- `git status` 应为干净（所有改动均已提交）。
- 如曾暂存过文件，先 `git reset` 再运行脚本。
- 首次提交前请确认 `git config user.name/email`（本仓库已配置）。