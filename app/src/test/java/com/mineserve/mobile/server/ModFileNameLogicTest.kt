package com.mineserve.mobile.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 模组文件名解析与启停目标名计算的回归测试。
 * 背景：历史版本曾把模组文件改名为无后缀/缺失 .jar 的形态，
 * 导致 readMods 过滤后条目消失。parseModFileName/modToggleTarget
 * 需要识别所有遗留形态，且启用后一律恢复为标准 baseName.jar。
 */
class ModFileNameLogicTest {

    @Test
    fun parsesStandardJar() {
        assertEquals("sodium" to true, PluginManager.parseModFileName("sodium.jar"))
    }

    @Test
    fun parsesStandardDisabled() {
        assertEquals("sodium" to false, PluginManager.parseModFileName("sodium.jar.disabled"))
    }

    @Test
    fun parsesLegacyDisabledWithoutJar() {
        assertEquals("sodium" to false, PluginManager.parseModFileName("sodium.disabled"))
    }

    @Test
    fun parsesLegacyDashPrefix() {
        assertEquals("sodium" to false, PluginManager.parseModFileName("-sodium.jar"))
    }

    @Test
    fun parsesSuffixlessLeftoverAsDisabled() {
        assertEquals("sodium" to false, PluginManager.parseModFileName("sodium"))
    }

    @Test
    fun keepsDottedBaseNames() {
        assertEquals("iron.chests" to true, PluginManager.parseModFileName("iron.chests.jar"))
        assertEquals("iron.chests" to false, PluginManager.parseModFileName("iron.chests.jar.disabled"))
    }

    @Test
    fun disableTargetIsStandardName() {
        assertEquals("sodium.jar.disabled", PluginManager.modToggleTarget("sodium.jar", enable = false))
        // 遗留形态禁用时直接归一化为标准名
        assertEquals("sodium.jar.disabled", PluginManager.modToggleTarget("sodium.disabled", enable = false))
        assertEquals("sodium.jar.disabled", PluginManager.modToggleTarget("sodium", enable = false))
        assertEquals("sodium.jar.disabled", PluginManager.modToggleTarget("-sodium.jar", enable = false))
    }

    @Test
    fun enableTargetAlwaysRestoresJar() {
        assertEquals("sodium.jar", PluginManager.modToggleTarget("sodium.jar.disabled", enable = true))
        assertEquals("sodium.jar", PluginManager.modToggleTarget("sodium.disabled", enable = true))
        assertEquals("sodium.jar", PluginManager.modToggleTarget("sodium", enable = true))
        assertEquals("sodium.jar", PluginManager.modToggleTarget("-sodium.jar", enable = true))
    }

    @Test
    fun toggleRoundTripIsStable() {
        var name = "sodium.jar"
        repeat(3) {
            name = PluginManager.modToggleTarget(name, enable = false)
            assertEquals("sodium.jar.disabled", name)
            name = PluginManager.modToggleTarget(name, enable = true)
            assertEquals("sodium.jar", name)
        }
        // 遗留无后缀文件一轮后进入标准循环
        name = "sodium"
        name = PluginManager.modToggleTarget(name, enable = true)
        assertEquals("sodium.jar", name)
        name = PluginManager.modToggleTarget(name, enable = false)
        assertNotEquals("sodium", name)
        assertEquals("sodium.jar.disabled", name)
    }

    @Test
    fun caseInsensitiveSuffixesNormalize() {
        assertEquals("sodium" to true, PluginManager.parseModFileName("sodium.JAR"))
        assertEquals("sodium" to false, PluginManager.parseModFileName("sodium.JAR.DISABLED"))
        assertEquals("sodium.jar.disabled", PluginManager.modToggleTarget("sodium.JAR", enable = false))
        assertEquals("sodium.jar", PluginManager.modToggleTarget("sodium.JAR.DISABLED", enable = true))
    }
}
