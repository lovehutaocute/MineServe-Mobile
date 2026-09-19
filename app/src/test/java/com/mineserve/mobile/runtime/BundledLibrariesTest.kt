package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「打包清单」与「jniLibs 实际文件」的一致性守卫。
 *
 * ## 为什么需要它（真实事故）
 * [NativeLibraryBundler.BUNDLED_LIBRARIES] 早就列出了 `libandroid-glob.so` 等 21 个库，
 * 但 `app/src/main/jniLibs/` 目录**根本不存在** —— 清单有、文件没有，
 * [NativeLibraryBundler.provision] 因此一直在空转，APK 里一个补充库都没有。
 *
 * 后果（荣耀 Magic 6 实测）：设备上 apt-get 在**动态链接阶段**就退出
 *
 * ```
 * F linker: CANNOT LINK EXECUTABLE ".../bin/apt-get":
 *           library "libandroid-glob.so" not found: needed by main executable
 * ```
 *
 * 现象正是「一键安装依赖库」等待数秒后报错、无任何日志输出、也没有网络连接。
 *
 * ## 这个测试能提前拦住它
 * 清单与文件都在仓库里，属于**纯静态事实**，不需要设备也不需要 NDK 就能校验：
 * 任何一次「加了清单项却忘了放文件」（或反过来）都会在 CI 直接失败。
 */
class BundledLibrariesTest {

    /**
     * 允许「清单里有、但官方 bootstrap rootfs 里确实没有」的库。
     *
     * `libtalloc.so.2` 与 `libandroid-shmem.so` 属于 proot 依赖链，官方 rootfs
     * 不含 proot（`bootstrap-aarch64.zip` 里搜不到 proot/talloc/shmem），
     * 它们是运行时由 proot-distro 安装的，所以这里只保留兜底条目、不打包。
     */
    private val notInRootfs = setOf("libtalloc.so.2", "libandroid-shmem.so")

    /** 单元测试的工作目录是模块目录（app/），但兼容从仓库根目录运行的情况。 */
    private fun jniLibsDir(): File {
        val candidates = listOf(
            File("src/main/jniLibs/arm64-v8a"),
            File("app/src/main/jniLibs/arm64-v8a")
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 jniLibs/arm64-v8a：${candidates.joinToString { it.absolutePath }}")
    }

    @Test
    fun everyBundledLibraryIsActuallyPackaged() {
        val dir = jniLibsDir()
        val missing = NativeLibraryBundler.BUNDLED_LIBRARIES.keys
            .filter { it !in notInRootfs && !File(dir, it).isFile }
        assertEquals(
            "清单声明要打包、jniLibs 里却没有的库（设备上会因缺库崩溃）",
            emptySet<String>(),
            missing.toSet()
        )
    }

    @Test
    fun noUnlistedLibrarySitsInJniLibs() {
        val dir = jniLibsDir()
        val files = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()
        val unlisted = files - NativeLibraryBundler.BUNDLED_LIBRARIES.keys
        assertEquals(
            "jniLibs 里有未登记进 BUNDLED_LIBRARIES 的文件：它们会被打包却永远不会落盘",
            emptySet<String>(),
            unlisted
        )
    }

    @Test
    fun packagedLibrariesAreNonEmptyArm64Elf() {
        val dir = jniLibsDir()
        NativeLibraryBundler.BUNDLED_LIBRARIES.keys
            .map { File(dir, it) }
            .filter { it.isFile }
            .forEach { f ->
                assertTrue("${f.name} 是空文件", f.length() > 0L)

                val head = ByteArray(20)
                val read = f.inputStream().use { it.read(head) }
                assertTrue("${f.name} 读取头失败", read == head.size)
                assertTrue(
                    "${f.name} 不是 ELF 文件",
                    head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
                        head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
                )
                assertEquals("${f.name} 不是 64 位 ELF", 2, head[4].toInt())

                // e_machine：0x00B7 = EM_AARCH64。防的是误把 x86_64/arm 的库放进 arm64-v8a。
                val machine = (head[18].toInt() and 0xff) or ((head[19].toInt() and 0xff) shl 8)
                assertEquals("${f.name} 不是 arm64 架构（e_machine=$machine）", 0xB7, machine)
            }
    }
}
