package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupPhaseTest {
    @Test
    fun recognizesCommonCoreStartupMessages() {
        assertEquals(StartupPhase.LoadingCore, startupPhaseForLog("[main/INFO]: Loading Minecraft 1.21.1"))
        assertEquals(StartupPhase.LoadingCore, startupPhaseForLog("[main/INFO]: NeoForge mod loading"))
        assertEquals(StartupPhase.LoadingCore, startupPhaseForLog("[main/INFO]: Quilt Loader 0.27"))
        assertEquals(StartupPhase.StartingNetwork, startupPhaseForLog("[main/INFO]: Listening on /0.0.0.0:25565"))
        assertEquals(StartupPhase.Ready, startupPhaseForLog("[Server thread/INFO]: Done (2.1s)! For help, type \"help\""))
        assertEquals(StartupPhase.Ready, startupPhaseForLog("[Server thread/INFO]: Done (2.1s)!"))
        assertEquals(StartupPhase.Ready, startupPhaseForLog("[main/INFO]: Velocity has started"))
    }

    @Test
    fun recognizesMojangDownloadMessages() {
        // Paper 中文日志：正在加载 mojang_1.20.6.jar
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在加载 mojang_1.20.6.jar"))
        // Paper 英文日志：Downloading mojang_26.2.jar
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("Downloading mojang_26.2.jar"))
        // PowerNukkitX 缓存下载
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在加载 mojang_1.21.0.jar"))
        // 通用下载日志不被误判为 LoadingCore
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("[main/INFO]: 正在加载依赖库 libraries..."))
    }

    @Test
    fun loadingCoreStillTakesPriorityOverDownloading() {
        // "loading minecraft" 应匹配 LoadingCore（在 DownloadingDependencies 之前检测）
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[main/INFO]: Loading Minecraft 1.20.6"))
        // "loading nukkit" 应匹配 LoadingCore
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[main/INFO]: Loading Nukkit 1.0"))
    }
}
