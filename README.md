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
- **Tunneling** — frp + bore built-in tunneling
- **Backups** — snapshots of all 3 dimensions (world / world_nether / world_the_end), restore & export to local via SAF
- **Keep-alive** — foreground service + boot auto-start + WorkManager periodic keep-alive
- **Device & network monitor** — memory/storage/battery + real-time network traffic & speed
- **Full server.properties editor** — 73 parameters, basic/advanced groups, dedicated bottom-nav tab
- **Material 3 UI** — Jetpack Compose, edge-to-edge immersive design

## 📋 Requirements

- Android 8.0 (API 26) or higher
- arm64-v8a or x86_64 architecture
- At least 4GB RAM recommended (to run the MC server)
- Internet connection (first launch downloads Termux environment + JDK)

## 📥 Download & Install

### Option 1: Download APK

From the [Releases page](../../releases):

- `app-arm64-v8a-release.apk` — most phones
- `app-x86_64-release.apk` — emulators

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
