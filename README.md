# JavaMC GO

<div align="center">

# ✅ Now Working · 可以正常使用

**Run a Minecraft Java server directly on your Android phone — no root required.**

[中文文档](./README.zh-CN.md) · [Architecture](./ARCHITECTURE.md)

</div>

JavaMC GO is a native Android app that runs a Minecraft Java Edition server on your phone, with support for **9 server cores**, plugin & mod management, built-in tunneling, automatic backups, and keep-alive protection.

## ✨ Features

- **No root required** — built-in Termux bootstrap rootfs + JDK, runs entirely inside the app
- **9 server cores** — Paper, Purpur, Fabric, Forge, NeoForge, Quilt, Vanilla, Velocity, BungeeCord (NeoForge/Quilt auto-run installer)
- **Plugin management** — scan `plugins/`, enable/disable, delete, upload, install from URL
- **Mod management** — scan `mods/` (Fabric/Forge), enable/disable via `.jar.disabled`, upload
- **Modrinth integration** — search mods (multi-loader filter + sort by downloads/relevance/newest), one-click install, mod icons
- **Player management** — online player list with kick/gamemode/OP actions, join/leave history (second precision), OP/whitelist/ban lists
- **Tunneling** — frp (paste full `frpc.toml`) + bore (pure-Kotlin, no binaries)
- **Backups** — snapshots of all 3 dimensions (world / world_nether / world_the_end), restore & export to local via SAF
- **No-root data access** — bundled MTDataFilesProvider lets MT Manager read/write the app's `data` dir (with in-app guide)
- **Keep-alive** — foreground service + boot auto-start + WorkManager periodic keep-alive
- **Device & network monitor** — memory/storage/battery + real-time network traffic & speed
- **Full server.properties editor** — 73 parameters, basic/advanced groups, dedicated bottom-nav tab
- **Dashboard address card** — LAN + tunnel public URL with copy buttons, one-tap MC console
- **Colored MC console** — color-coded logs (ERROR/WARN/tunnel/crash), auto-scroll, quick commands, one-click copy
- **Material 3 UI** — Jetpack Compose, edge-to-edge immersive design, 9-tab bottom navigation

## 📖 Features in Detail

### 🧩 Server Cores (9 supported)
- **Paper** — high-performance, plugin-compatible, production recommended
- **Purpur** — Paper fork with extra vanilla feature toggles
- **Fabric / Forge** — classic mod loaders, latest versions auto-detected
- **NeoForge / Quilt** — auto-run installer (`--installServer` / `quilt install server`) then start with generated launch files
- **Vanilla** — official Mojang server (from version manifest)
- **Velocity / BungeeCord** — proxy servers (Jenkins / PaperMC v3 API)
- Multi-core support: each core has its own isolated directory, config, world, plugins & mods
- Version list auto-fetched from official APIs; custom core name supported

### 🎮 Server Control
- One-tap start / stop, start-up status (with duration & TPS/online/memory stats)
- Crash auto-restart (configurable)
- MC console terminal with quick commands & colored logs
- Server launch settings popup (gear icon on the control card)

### 👥 Player Management
- **Online player list** — real-time from logs, with copy / kick / switch gamemode actions
- **Join/leave history** — recorded to the second, persisted across restarts
- OP / whitelist / ban lists (with tempban, OP levels, search & detail dialogs)

### 🔌 Plugin Management
- Installed plugin list (scan + plugin.yml metadata), enable/disable/delete with data dir cleanup
- Local upload & install from URL
- Resource site directory (SpigotMC / Hangar / Modrinth / BuiltByBit / CurseForge)

### 🧪 Mod Management
- `mods/` directory scan (Fabric/Forge), enable/disable via `.jar.disabled`, delete, upload
- **Modrinth integration** — search (multi-loader filter, sort by downloads/relevance/newest), mod icons, one-click install with MC-version matching, incompatible-core warning

### 📁 File Manager
- Browse server directories, upload, create folders, delete
- **Up / refresh** buttons in the path bar for quick navigation
- **Export** — single file / folder (zip) / entire server (zip) to any local path via SAF
- **MT Manager integration (no root)** — bundled `MTDataFilesProvider` exposes the app's `data` dir; in-app guide covers download link + step-by-step setup (add local storage → pick the app → browse `data/files/home/home/servers`)
- Auto annotations for common server files/folders

### 🌐 Tunneling
- **frp** — paste the full `frpc.toml` text (server addr, token, proxy rules all in one); auto-fetches a recent frpc when missing, mirrors included
- **bore** — pure-Kotlin client, no binary download, connect to any `bore server` VPS
- Public URL shown on both the dashboard address card and the network page (with copy buttons)

### 🖥️ MC Console (Logs)
- **Color-coded logs** — `ERROR` red, `WARN` yellow, `[tunnel]` blue, `[crash]` orange, `[bootstrap]` green
- **Auto-scroll** to newest line (only when you're at the bottom, so scrolling up is not interrupted)
- **Quick commands** — one-tap `/list`, `/tps`, `/say`, `/kick`, `/help`
- One-click copy the whole log
- Dashboard「Open MC Terminal」button jumps straight here

### 📊 Dashboard Address Card
- LAN address (`127.0.0.1:port` / `192.168.x.x:port`) + tunnel public URL, each with a copy button
- One-tap「Open MC Terminal」launches the console

### 💾 Backup & Restore
- Snapshots of all 3 dimensions (world / world_nether / world_the_end)
- Create (with `save-all`), list, restore (auto-stops server, backs up current worlds), delete, export snapshot to local via SAF
- Auto-cleanup of old snapshots

### ⚙️ Configuration
- Full `server.properties` editor — all 73 parameters, grouped Basic/Advanced, data-driven rendering
- JVM heap limit, download mirror, APT mirror, auto-restart toggles

### 📊 Device & Network Monitor
- Device memory / storage / battery (no permissions needed)
- Real-time network traffic: total upload/download + current speeds

### 🛡️ Keep-Alive
- Foreground service (START_STICKY + persistent notification + wake locks)
- Boot auto-start (BOOT_COMPLETED) & WorkManager periodic keep-alive
- Dedicated keep-alive page with per-feature switches & explanations

### 🎨 UI & UX
- Material 3, immersive edge-to-edge full screen, 9-tab bottom navigation
- Debounced text inputs (no lag / no reordering while typing)
- Back-key returns page by page instead of exiting the app

## 📋 Requirements

- Android 8.0 (API 26) or higher
- arm64-v8a architecture (releases build only arm64)
- At least 4GB RAM recommended (to run the MC server)
- Internet connection (first launch downloads Termux environment + JDK)

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
- Foreground service with START_STICKY + persistent notification
- Boot auto-start (BOOT_COMPLETED receiver)
- WorkManager keep-alive every 15 minutes
- Dedicated keep-alive page with per-feature switches & explanations

> Note: Android restricts background processes; keep-alive effectiveness varies by system version and vendor policy. Grant auto-start permission in system settings.

## 📄 License

See the repository for license details.
