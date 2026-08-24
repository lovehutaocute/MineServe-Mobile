package com.mineserve.mobile.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerImportLayoutTest {

    private fun strip(vararg entries: String): String? {
        // 统一正斜杠（模拟压缩包条目名）
        val normalized = entries.map { it.replace('\\', '/') }
        return ServerImportLayout.computeStripPrefix(normalized)
    }

    @Test
    fun singleWrapperDirIsStripped() {
        assertEquals("我的服务器", strip("我的服务器/", "我的服务器/server.jar", "我的服务器/world/", "我的服务器/world/level.dat"))
    }

    @Test
    fun noWrapperWhenFilesAtRoot() {
        assertNull(strip("server.jar", "eula.txt", "world/", "world/level.dat"))
    }

    @Test
    fun twoServerDirsNotStripped() {
        assertNull(strip("a/server.jar", "b/server.jar"))
    }

    @Test
    fun junkEntriesIgnored() {
        assertEquals("我的服务器", strip("__MACOSX/._server.jar", "我的服务器/server.jar", "我的服务器/world/"))
    }

    @Test
    fun emptyDirEntryOnlyNotStripped() {
        assertNull(strip("我的服务器/"))
    }

    @Test
    fun windowsSeparatorsNormalized() {
        assertEquals("我的服务器", strip("我的服务器\\server.jar", "我的服务器\\world\\level.dat"))
    }

    @Test
    fun wrapperPlusFlatReadmeStripped() {
        assertEquals("我的服务器", strip("我的服务器/server.jar", "readme.txt"))
    }

    @Test
    fun wrapperPlusNestedOtherDirNotStripped() {
        assertNull(strip("我的服务器/server.jar", "docs/readme.txt"))
    }

    @Test
    fun pluginsAndWorldTwoDirsNotStripped() {
        assertNull(strip("plugins/x.jar", "world/level.dat"))
    }

    @Test
    fun strippedPathBasic() {
        assertNull(ServerImportLayout.strippedPath("我的服务器/", "我的服务器"))
        assertEquals("server.jar", ServerImportLayout.strippedPath("我的服务器/server.jar", "我的服务器"))
        assertEquals("server.jar", ServerImportLayout.strippedPath("server.jar", null))
        assertEquals("x/y/z.txt", ServerImportLayout.strippedPath("我的服务器/x/y/z.txt", "我的服务器"))
    }

    @Test
    fun isJunkEntryVariants() {
        assert(ServerImportLayout.isJunkEntry("__MACOSX/._x"))
        assert(ServerImportLayout.isJunkEntry(".DS_Store"))
        assert(ServerImportLayout.isJunkEntry("a/b/Thumbs.db"))
        assert(ServerImportLayout.isJunkEntry("a/._apple"))
        assert(!ServerImportLayout.isJunkEntry("server.jar"))
        assert(!ServerImportLayout.isJunkEntry("world/level.dat"))
    }

    @Test
    fun jarImportKeepsSourceFileName() {
        assertEquals("leaves-1.21.8.jar", ServerImportLayout.importedJarFileName("leaves-1.21.8.jar"))
        assertEquals("unknown-core.jar", ServerImportLayout.importedJarFileName("folder\\unknown-core.jar"))
    }
}
