# 一键创建本次会话的全部版本节点提交（需要 git）
# 用法：powershell -ExecutionPolicy Bypass -File commit-nodes.ps1
$ErrorActionPreference = "Stop"
try { & git --version *> $null } catch { Write-Host "未找到 git，请先安装 Git 或使用 Android Studio 内置 git 后重试。"; exit 1 }
& git --version *> $null
if ($LASTEXITCODE -ne 0) { Write-Host "未找到 git，请先安装 Git 或使用 Android Studio 内置 git。"; exit 1 }

$nodeFiles = @(
  @("app/src/main/java/com/mineserve/mobile/server/ServerCoreDetector.kt", "app/src/main/java/com/mineserve/mobile/server/ServerImporter.kt", "app/src/main/java/com/mineserve/mobile/ui/McViewModel.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/BackupScreen.kt"),
  @("app/src/main/java/com/mineserve/mobile/ui/screens/TerminalScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/TerminalLogTranslator.kt"),
  @("app/src/main/java/com/mineserve/mobile/ui/screens/LogsScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/DashboardScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/McApp.kt"),
  @("app/build.gradle.kts", ".gitignore", "app/src/main/java/com/mineserve/mobile/server/BackupManager.kt", "app/src/main/java/com/mineserve/mobile/service/McForegroundService.kt", "app/src/main/java/com/mineserve/mobile/server/PluginManager.kt", "app/src/main/java/com/mineserve/mobile/runtime/TermuxRuntime.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/DiagnosticsScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/UpdateDialog.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/MoreScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/DownloadScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/PluginsScreen.kt"),
  @("app/src/main/java/com/mineserve/mobile/server/ServerImportLayout.kt", "app/src/main/java/com/mineserve/mobile/server/McServerController.kt", "app/src/test/java/com/mineserve/mobile/server/ServerImportLayoutTest.kt", "app/src/test/java/com/mineserve/mobile/server/ServerCoreDetectorTest.kt", "app/src/test/java/com/mineserve/mobile/ui/TerminalLogTranslatorTest.kt", ".github/workflows/build.yml"),
  @("gradle/libs.versions.toml", "app/src/main/res/values/strings.xml", "app/src/main/java/com/mineserve/mobile/ui/screens/PropertiesScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/SettingsScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/CrashReportsScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/PlayersScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/ServerManagementScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/ServerIconScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/TextFileEditorScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/components/SharedComponents.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/FileManagerScreen.kt", "app/src/main/java/com/mineserve/mobile/ui/screens/KeepAliveScreen.kt", "docs/UPGRADE-2024-12.md"),
  @("CHANGELOG.md", "docs/GIT-COMMITS.md", "commit-nodes.ps1", "commit-nodes.sh")
)

$messages = @(
  "feat(import): 新增服务器文件夹/压缩包导入，自动剥离包裹目录并识别核心版本"
  "fix(terminal): 修复输入框溢出；快捷指令加 emoji；默认开启日志汉化"
  "feat(terminal): 回车发送/占位提示/执行中反馈；日志页汉化扩充；概览导入入口"
  "feat(v1.2.0): targetSdk 34、崩溃日志、导入确认+进度、备份校验/重命名/错峰、终端历史/清空/聚焦、核心修复、Modrinth 分页、插件冲突"
  "test(lint/ci): 27 个单元测试、AutoMirrored/lint 清理、GitHub Actions、纯逻辑提取"
  "refactor(i18n): 全部硬编码中文迁入资源；chore(deps): AGP 8.7.3/Kotlin 2.0.21/Compose 2024.12 升级"
  "docs: 更新日志、提交计划与一键提交脚本"
)

for ($i = 0; $i -lt $nodeFiles.Count; $i++) {
  Write-Host "==> 节点 $($i + 1)/$($nodeFiles.Count): $($messages[$i])"
  git reset -q 2>$null
  git add -- $nodeFiles[$i]
  if ($LASTEXITCODE -ne 0) { Write-Host "git add 失败，终止"; exit 1 }
  $staged = git diff --cached --name-only
  if (-not $staged) { Write-Host "节点 $($i + 1) 无文件可提交，跳过"; continue }
  git commit -q -m $messages[$i]
  if ($LASTEXITCODE -ne 0) { Write-Host "git commit 失败，终止"; exit 1 }
}
Write-Host "全部节点提交完成："; git log --oneline -8