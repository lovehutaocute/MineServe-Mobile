# MineServe Mobile

<div align="center">

# ✅ Now Working · 可以正常使用

**Run a Minecraft server directly on your Android phone — no root required.**

[中文文档](./README.zh-CN.md) · [Architecture](./ARCHITECTURE.md) · [Changelog](./CHANGELOG.md)

</div>

MineServe Mobile is a native Android app for Minecraft Java Edition servers, with PowerNukkitX support for Bedrock Edition. It includes multi-server management, plugins and mods, Modrinth integration, tunneling, backups, crash reports, keep-alive tools, MCP remote control, and no-root data access.

## ✨ Features

- **No root required** — built-in Termux bootstrap runtime; Java 17/21/25 run directly, while legacy Java 8 servers use ARM64 Ubuntu PRoot.
- **10 core types** — Paper, Purpur, Fabric, Forge, NeoForge, Quilt, Vanilla, Velocity, BungeeCord, and PowerNukkitX.
- **Multi-server management** — isolated cores, worlds, configuration, plugins, and mods with one-tap switching.
- **Server import** — independent folder, archive, JAR, and Modrinth `.mrpack` actions with core/version detection.
- **Plugins and mods** — scan, enable/disable, SAF upload, URL install, and Modrinth search/download.
- **Tunneling** — frp and bore with LAN and public address display.
- **Backup and crash reports** — world/full-server backup, SAF export, abnormal-exit logs, native-report aggregation, and offline analysis.
- **Keep-alive** — foreground service, wake locks, boot/periodic recovery, and an optional status overlay.
- **MCP remote control** — embedded Model Context Protocol server (Streamable HTTP) so LAN AI assistants can check status, start/stop the server, send console commands, and read logs; protected by a bearer token.
- **Material 3 UI** — Jetpack Compose with compact core and version selection.

## 📋 Requirements

- Android 8.0 (API 26) or newer.
- `arm64-v8a` device.
- At least 4 GB RAM and enough storage for the runtime, worlds, and backups are recommended.
- Network access is required for initial setup, core download, and modpack import.

## 📥 Download and build

Download an `arm64-v8a` APK from the [Releases page](../../releases), or build locally:

```powershell
git clone https://github.com/lovehutaocute/MineServe-Mobile.git
cd MineServe-Mobile
.\gradlew.bat :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

## 🚀 Quick Start

1. **Initialize** — complete runtime setup and install the required Java version.
2. **Download or import** — select a core/version on Download, or open Import Server.
3. **Accept EULA** — read and accept the generated `eula.txt`.
4. **Start and join** — start from Dashboard; LAN players join using the displayed phone IP and port.

Use frp/bore or router port forwarding for public access.

## 🧩 Supported Cores

| Core | Plugins | Mods | Notes |
|---|---|---|---|
| Paper | ✅ | ❌ | High-performance Java plugin server |
| Purpur | ✅ | ❌ | Paper fork with more feature toggles |
| Fabric | ❌ | ✅ | Lightweight mod loader |
| Forge | ❌ | ✅ | Traditional mod loader |
| NeoForge | ❌ | ✅ | Forge successor with installer flow |
| Quilt | ❌ | ✅ | Fabric fork with installer flow |
| Vanilla | ❌ | ❌ | Official server |
| Velocity | ❌ | ❌ | Java proxy |
| BungeeCord | ❌ | ❌ | Java proxy |
| PowerNukkitX | ✅ | ❌ | Bedrock Nukkit core using UDP |

## 📖 Features in Detail

### 🧩 Cores and downloads

- Version lists come from official core APIs; custom version strings are supported.
- The Download screen shows the newest eight versions first and can reveal all versions.
- Changing a core clears the old versions and rejects late responses from the previous request.
- Forge, NeoForge, and Quilt run their matching installer after download.

### 📥 Server and modpack import

- **Folder / JAR** — import an existing server and detect its core/version.
- **Archive** — ZIP, TAR, TAR.GZ/TGZ, TAR.XZ/TXZ, TAR.BZ2/TBZ2, TAR.ZST/TZST, TAR.LZ4, and 7z; removes one wrapper folder and blocks path traversal.
- **Modrinth `.mrpack`** — downloads server-required files, verifies SHA-1, skips client-only files, and applies `overrides/` and `server-overrides/`.

> CurseForge packs require external metadata/API handling and cannot be imported directly yet.

### 🎮 Server control and console

- One-tap start/stop, Java heap settings, and optional crash auto-restart.
- Colored MC console, quick commands, copy, and smart auto-scroll.
- Runtime state for uptime, players, TPS, and memory.

### 🔌 Plugins, mods, and files

- Plugin/mod scan, enable/disable, delete, SAF upload, and URL install.
- Modrinth search with Minecraft-version and loader matching.
- Server file browse, directory creation, text editing, import/export, and full-server archive backup.
- The bundled DocumentsProvider gives compatible file managers no-root access to app data.

### 💾 Backup, crash reports, and network

- Backup, restore, delete, and SAF export for overworld, nether, and end.
- On abnormal exit, saves the last 200 `latest.log` lines and the newest native report; the crash screen aggregates native reports from all installed servers and analyzes them locally.
- frp and bore tunnels, with one-tap LAN/public address copy.

### 🛡️ Keep-alive

- Foreground service, persistent notification, CPU/Wi-Fi wake locks, boot startup, and WorkManager periodic checks.
- Optional draggable status overlay for state, CPU, and memory.

> Android and OEM battery policies can still terminate background work. Disable battery optimization and keep the device powered. CPU values are best effort and may be unavailable where Android/PRoot restricts `/proc`.

## 📱 How does a phone run the server?

First setup installs a Termux-compatible runtime and Java into app-private storage. Java 17/21/25 servers are started by the Android shell; legacy Java 8 servers run inside ARM64 Ubuntu PRoot. Both are ordinary Java child processes managed by the app, not root processes.

Console commands go to process stdin and output is displayed live and saved to `logs/latest.log`. Minecraft listens on normal device network ports: use the phone LAN IP locally, or a tunnel/port mapping publicly. The app cannot bypass NAT, carrier CGNAT, or firewalls.

See the [architecture document](./ARCHITECTURE.md) for implementation details.

## 📄 License

Licensed under [GNU GPL v3.0 or later](LICENSE).
