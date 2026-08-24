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
        assertEquals(StartupPhase.Ready, startupPhaseForLog("[main/INFO]: Velocity has started"))
    }
}
