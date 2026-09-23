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
     * 允许「清单里有、但官方 bootstrap rootfs 里确实没有」的**打包名**。
     *
     * 直接取自 [NativeLibraryBundler.RUNTIME_OPTIONAL_LIBRARIES] —— 它们属于 proot
     * 依赖链，官方 rootfs 不含 proot（`bootstrap-aarch64.zip` 里搜不到 proot/talloc/shmem），
     * 只能由运行时的 proot-distro 安装。从清单派生可避免两张表各自维护、慢慢失步。
     *
     * 注意：这里比对的是 `BUNDLED_LIBRARIES` 的**键**（打包名），
     * 不是它的值（Termux 侧 SONAME，例如 `libtalloc.so.2`）。
     */
    private val notPackaged = NativeLibraryBundler.RUNTIME_OPTIONAL_LIBRARIES.keys

    /**
     * 不在 [NativeLibraryBundler.BUNDLED_LIBRARIES] 里、但确实该躺在 jniLibs 里的文件：
     * `heaptagfix.c` 的编译产物（见 [NativeLibraryBundler.HEAP_TAG_FIX_LIB]）。
     * 它不经 provision 落盘（TermuxRuntime 直接以绝对路径交给 LD_PRELOAD），
     * 所以不进打包清单。
     */
    private val extraJniLibs = setOf(NativeLibraryBundler.HEAP_TAG_FIX_LIB)

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
            .filter { it !in notPackaged && !File(dir, it).isFile }
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
        val unlisted = files - NativeLibraryBundler.BUNDLED_LIBRARIES.keys - extraJniLibs
        assertEquals(
            "jniLibs 里既没登记进 BUNDLED_LIBRARIES、也不属于 extraJniLibs 的文件：" +
                "它们会被打进 APK 却没有任何代码会用到",
            emptySet<String>(),
            unlisted
        )
    }

    /**
     * heaptagfix 的产物必须存在。
     *
     * 它是唯一**不经过常规构建**（而由 build-heaptagfix 工作流生成）的文件：
     * 一旦漏掉，TermuxRuntime 的 LD_PRELOAD 会静默退化成空串 —— 不报错、没日志，
     * 只是「关闭堆指针标签、省内存」这项优化又变回死的（1.2.8 之前就是这样）。
     */
    @Test
    fun heapTagFixLibraryIsPackaged() {
        val dir = jniLibsDir()
        extraJniLibs.forEach { name ->
            val f = File(dir, name)
            assertTrue(
                "$name 不在 jniLibs 里（改过 heaptagfix.c 后要跑一次 build-heaptagfix 工作流）",
                f.isFile
            )
            assertTrue("$name 是空文件", f.length() > 0L)
        }
    }

    @Test
    fun packagedLibrariesAreNonEmptyArm64Elf() {
        val dir = jniLibsDir()
        (NativeLibraryBundler.BUNDLED_LIBRARIES.keys + extraJniLibs)
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
