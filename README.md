# JavaMC GO

<div align="center">

# ✅ Now Working · 可以正常使用

**Run a Minecraft Java server directly on your Android phone — no root required.**

[中文文档](./README.zh-CN.md) · [Architecture](./ARCHITECTURE.md)

</div>

JavaMC GO is a native Android app that runs a Minecraft Java Edition server on your phone, with support for **9 server cores**, multi-core switching, plugin & mod management, Modrinth integration, built-in tunneling, automatic backups, crash reports, keep-alive protection, and a no-root data bridge for MT Manager.

## ✨ Features

- **No root required** — built-in Termux bootstrap rootfs + JDK 25, runs entirely inside the app
- **9 server cores** — Paper, Purpur, Fabric, Forge, NeoForge, Quilt, Vanilla, Velocity, BungeeCord (NeoForge/Quilt auto-run installer)
- **Multi-core management** — install multiple cores side-by-side in isolated directories, switch the active core from the dashboard, per-core config/world/plugins/mods
- **Plugin management** — scan `plugins/`, enable/disable (`-` prefix), delete with optional data-dir cleanup, upload (SAF), install from URL (with rollback)
- **Mod management** — scan `mods/` (Fabric/Forge), enable/disable via `.jar.disabled`, delete, upload, URL install
- **Modrinth integration** — search mods (multi-loader filter + sort by downloads/relevance/newest), mod icons, one-click install with MC-version + loader matching, incompatible-core warning
- **Player management** — online player list with kick/gamemode/OP/XP actions, join/leave history (second precision, persisted 500-entry cap), OP/whitelist/ban lists (tempban, OP levels, search & detail dialogs)
- **Tunneling** — frp (paste full `frpc.toml`, autoTLS sanitization, multi-mirror parallel download) + bore (pure-Kotlin, no binaries)
- **Backups** — snapshots of all 3 dimensions (world / world_nether / world_the_end), restore & export to local via SAF, zip-slip protection, auto-cleanup by count
- **Crash reports** — self-built crash logs (last 200 lines of `latest.log`) + MC native `crash-reports/`, list/read/delete/clear
- **No-root data access** — bundled `MTDataFilesProvider` (full CRUD DocumentsProvider) lets MT Manager read/write the app's `data` dir (with in-app guide + tutorial video)
- **Keep-alive** — foreground service (START_STICKY + special-use type + wake/WiFi locks) + boot auto-start + WorkManager 15-min periodic + onTaskRemoved inexact-alarm restart
- **Device & network monitor** — memory/storage/battery (with charging state) + real-time network traffic & speed, 3-second polling
- **Full server.properties editor** — 75 parameters, basic/advanced groups, data-driven rendering, fallback editor for unlisted keys
- **Dashboard address card** — LAN + tunnel public URL with copy buttons, one-tap MC console
- **Colored MC console** — color-coded logs (ERROR/WARN/tunnel/crash/bootstrap), smart auto-scroll, quick commands, one-click copy, abstract-namespace socket multi-client broadcast
- **Material 3 UI** — Jetpack Compose, edge-to-edge immersive design, 9-tab bottom navigation, debounced inputs

## 📖 Features in Detail

### 🧩 Server Cores (9 supported)
- **Paper** — high-performance, plugin-compatible, production recommended
- **Purpur** — Paper fork with extra vanilla feature toggles
- **Fabric / Forge** — classic mod loaders, latest versions auto-detected
- **NeoForge / Quilt** — auto-run installer (`--installServer` / `quilt install server --download-server`) then start with generated launch files
- **Vanilla** — official Mojang server (from version manifest)
- **Velocity / BungeeCord** — proxy servers (PaperMC v3 API / Jenkins)
- Version lists auto-fetched from official APIs (PaperMC, Purpur, Mojang, Fabric meta, Forge promotions, NeoForge maven, Quilt meta); custom version string supported
- HTTP fetching has 3-retry with increasing timeouts; download progress/speed reported every 500ms

### 🎮 Server Control
- One-tap start / stop, start-up status (with duration & TPS/online/memory stats)
- Crash auto-restart (configurable, max 3 retries with 3s delay, default off)
- Crash report capture on abnormal exit
- MC console terminal with quick commands & colored logs
- Server launch settings popup (gear icon on the control card) — JVM heap, auto-restart toggle, core selection
- Real-time TPS parsing (Paper) + real RSS memory monitoring (walks `/proc`)

### 👥 Player Management
- **Online player list** — real-time from `list` command + log parse, with copy / kick / switch gamemode / OP / give XP actions
- **Join/leave history** — recorded to the second, persisted to `player_history.json` (500-entry cap, mutex-protected writes, dedupe-merge on load)
- OP / whitelist / ban lists (with tempban 7 presets: forever/30m/1h/6h/1d/7d/30d, OP levels 1–4, per-tab search & detail dialogs)
- Gamemode dual-syntax dispatch (old + new MC compatibility)

### 🔌 Plugin Management
- Installed plugin list (parallel scan + `plugin.yml` / `paper-plugin.yml` metadata, lastModified-cached), enable/disable (`-` prefix), delete with optional data-dir cleanup
- Local upload (SAF) & install from URL (backup → download → rollback on failure, ≥1024-byte validation)
- Resource site directory (SpigotMC / Hangar / Modrinth / BuiltByBit / CurseForge)
- Hot reload button, search + 4 filter chips (All / Enabled / Disabled / Local)
- Core compatibility indicators

### 🧪 Mod Management
- `mods/` directory scan (Fabric/Forge), enable/disable via `.jar.disabled`, delete, upload, URL install (with rollback)
- **Modrinth integration** — search (multi-loader filter, sort by downloads/relevance/newest), mod icons, one-click install with MC-version + loader matching, incompatible-core warning, download-count formatting

### 📁 File Manager
- Browse server directories, upload (SAF), create folders, delete (recursive, with confirmation)
- **Up / refresh** buttons in the path bar for quick navigation
- **Export** — single file / folder (zip) / entire server (zip) to any local path via SAF
- **MT Manager integration (no root)** — bundled `MTDataFilesProvider` (full CRUD `DocumentsProvider` with MT-specific `mt:setLastModified` / `mt:setPermissions` / `mt:createSymlink` calls) exposes the app's `data` dir; in-app guide covers download link + 5-step setup + tutorial video (browse `data/files/home/home/servers`)
- Auto annotations for common server files/folders (19+ entries: `server.jar`, `server.properties`, `ops.json`, `whitelist.json`, `banned-players.json`, `eula.txt`, `bukkit.yml`, `spigot.yml`, `paper.yml`, `world`, `world_nether`, `world_the_end`, `logs`, `plugins`, etc.)

### 🌐 Tunneling
- **frp** — paste the full `frpc.toml` text (server addr, token, proxy rules all in one); auto-filters `autoTLS` field for old-frpc compat; frpc auto-downloaded when missing (fixed v0.61.2, **5 mirrors tried in parallel** — direct / gh-proxy.com / mirror.ghproxy.com / ghproxy.net / ghfast.top — first success wins, apt fallback); tailored `diagnoseFailure` messages (unknown field / connection refused / token / bind / timeout)
- **bore** — pure-Kotlin client implementing the ekzang/bore protocol, no binary download, default control port 7835, connect to any `bore server` VPS
- Public URL shown on both the dashboard address card and the network page (with copy buttons)
- Free-FRP platform directory (OpenFrp / ChmlFrp / StarryFrp / SakuraFrp) with quick links

### 🖥️ MC Console (Logs)
- **Color-coded logs** — `ERROR`/`FATAL` red, `WARN` yellow, `[tunnel]` blue, `[crash]` orange, `[bootstrap]` green
- **Smart auto-scroll** — only follows new lines when you're near the bottom, so scrolling up to read is not interrupted
- **Quick commands** — one-tap `/list`, `/tps`, `/say`, `/kick`, `/help`
- One-click copy the whole log (toasts line count)
- 1000-line ring buffer, 100ms batched UI flush to reduce recomposition
- **ConsoleSocketServer** — abstract-namespace `LocalServerSocket("mc-console")` shared with the MC process, multi-client broadcast, dead-client auto-cleanup
- Dashboard「Open MC Terminal」button jumps straight here

### 📊 Dashboard Address Card
- LAN address (`127.0.0.1:port` / `192.168.x.x:port`) + tunnel public URL, each with a copy button
- One-tap「Open MC Terminal」launches the console
- Device-stats card: memory / storage / battery (with charging state) / total RX+TX / live RX+TX speed, 3-second polling

### 💾 Backup & Restore
- Snapshots of all 3 dimensions (world / world_nether / world_the_end), preserving directory hierarchy
- Create (with `save-all`), list, restore (auto-stops server, backs up current worlds to `*.bak.<ts>`), delete, export snapshot to local via SAF
- Zip-slip / path-traversal protection on restore
- Auto-cleanup of old snapshots by count (`maxSnapshots`, default 10)

### 🛠️ Crash Reports
- **Self-built reports** at `home/crash-logs/crash_yyyyMMdd_HHmmss.txt` — captures last 200 lines of `latest.log` + latest native crash report
- **MC native reports** at `home/servers/{dirName}/crash-reports/`
- List / read (with preview) / delete / clear-all from the UI

### ⚙️ Configuration
- Full `server.properties` editor — all **75 parameters**, grouped Basic (37) / Advanced (38), data-driven rendering (Bool/Int/Text/Enum), fallback free-text editor for unlisted keys, restart warning when server is running
- JVM heap limit, download mirror (7 options), APT mirror (5 options), auto-restart toggle, WiFi lock, CPU wakelock

### 📊 Device & Network Monitor
- Device memory / storage / battery (with charging state), no permissions needed
- Real-time network traffic: total upload/download + current speeds, 3-second polling
- Patches real MC process RSS into `usedMemoryMb`

### 🛡️ Keep-Alive
- Foreground service (START_STICKY + persistent notification + PARTIAL_WAKE_LOCK + WIFI_MODE_FULL_HIGH_PERF, special-use foreground type on Android 13+)
- Boot auto-start (BOOT_COMPLETED receiver)
- WorkManager periodic keep-alive every 15 minutes
- onTaskRemoved → inexact alarm restart (2s, `setAndAllowWhileIdle`, no exact-alarm permission needed)
- 30s health watchdog — every 60s sends `list` (all cores) + `tps` (Paper) to refresh player count & TPS
- Dedicated keep-alive page with 3 per-feature switches (boot auto-start / periodic keep-alive / auto-restart on crash) & explanations

### 🎨 UI & UX
- Material 3, immersive edge-to-edge full screen, **9-tab bottom navigation** (Dashboard / Download / Players / Plugins / Files / Network / Backup / Config / Settings)
- Debounced text inputs (300ms write-back, focus-loss immediate sync, digits-only sanitize) — no lag / no reordering while typing
- Back-key returns page by page instead of exiting the app (logs overlay → sub-page → non-Dashboard tab → Dashboard consumes back)
- Config persistence with 300ms debounce (DataStore via conflated channel, CAS-style atomic updates)

### 📥 Download Help
- Dedicated page for slow GitHub downloads: switch mirror (7 built-in), switch APT source, switch network, VPN/proxy, manual PC download
- Speed reference card (5+ MB/s fast / 1–5 good / 0.5–1 normal / <0.5 slow)

## 📋 Requirements

- Android 8.0 (API 26) or higher
- arm64-v8a architecture (releases build only arm64)
- At least 4GB RAM recommended (to run the MC server)
- Internet connection (first launch downloads Termux environment + JDK 25)

## 📥 Download & Install

### Option 1: Download APK

From the [Releases page](../../releases):

- `app-arm64-v8a-release.apk` — most phones


### Option 2: Build from source

```bash
git clone <repo-url>
cd MCServerManager
./gradlew.bat assembleRelease
```

APK output at `app/build/outputs/apk/release/`.

## 🚀 Quick Start

1. **Initialize environment** — on first launch, tap "Initialize" to download the Termux rootfs + JDK 25
2. **Download a core** — go to the "Download" tab, pick a core (Paper recommended) and a version
3. **Start the server** — first launch downloads runtime files, please be patient
4. **Join** — connect via LAN address shown on the dashboard, or use built-in tunneling for public access

## 🧩 Supported Cores

| Core | Plugins | Mods | Notes |
|---|---|---|---|
| Paper | ✅ | ❌ | High-performance, production recommended |
| Purpur | ✅ | ❌ | Paper fork with extra feature toggles |
| Fabric | ❌ | ✅ | Lightweight mod loader |
| Forge | ❌ | ✅ | Classic mod loader |
| NeoForge | ❌ | ✅ | Forge successor (installer flow) |
| Quilt | ❌ | ✅ | Fabric fork (installer flow) |
| Vanilla | ❌ | ❌ | Official vanilla |
| Velocity | ❌ | ❌ | Proxy server |
| BungeeCord | ❌ | ❌ | Proxy server |

## 🛡️ Keep-Alive

Pure Android solution (no NDK):
- Foreground service with START_STICKY + persistent notification + wake/WiFi locks
- Boot auto-start (BOOT_COMPLETED receiver)
- WorkManager keep-alive every 15 minutes
- onTaskRemoved inexact-alarm restart
- Dedicated keep-alive page with per-feature switches & explanations

> Note: Android restricts background processes; keep-alive effectiveness varies by system version and vendor policy. Grant auto-start permission in system settings.

## 📄 License

See the repository for license details.
