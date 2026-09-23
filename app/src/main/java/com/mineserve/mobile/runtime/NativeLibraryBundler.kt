package com.mineserve.mobile.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 随 APK 打包的 Termux 共享库支持。
 *
 * ## 背景
 * App 内置的 Termux 环境由 deb 包拼装而成（dpkg-deb -x 解包 + 符号链接重建）。任何一步
 * 不完整都会让 Termux 二进制在**动态链接阶段**直接失败：
 *
 * ```
 * F linker: CANNOT LINK EXECUTABLE "/data/user/0/com.mineserve.mobile/files/home/bin/apt-get":
 *           library "libandroid-glob.so" not found: needed by main executable
 * ```
 *
 * 这种失败发生在 main() 之前，`canExecute()`、SELinux 上下文、可执行位全部正常，
 * 所以既有的「权限自愈 / 复制副本执行」兜底链路会误判成 Permission denied，反向掩盖真因。
 *
 * 更麻烦的是**连锁性**：apt-get 起不来 → apt / dpkg / proot / Java 全部失效。
 * 而这些库处在 apt 依赖链的底层（`apt` Depends 含 libandroid-glob / libbz2 / libiconv /
 * liblz4 / liblzma / zlib / zstd，`dpkg` 含 libbz2 / liblzma / zlib / zstd），
 * 所以一次性把整条链补齐，比「缺一个查一个」务实得多。
 *
 * ## 方案
 * 把需要的 .so 放进 `app/src/main/jniLibs/arm64-v8a/`，随 APK 分发，运行时从
 * [nativeLibraryDir] 复制到 Termux 的库目录。
 *
 * 之所以能这么做：本 App `targetSdk = 28`，小于 Android 10（API 29）引入的
 * `extractNativeLibs=false` 默认行为，系统会把 `jniLibs` 里的 .so **解压成真实文件**，
 * 因此 [nativeLibraryDir] 下是可读、可 `cp`、可走 ELF 搜索路径的普通文件。
 *
 * > AndroidManifest 已显式声明 `android:extractNativeLibs="true"`，所以将来提升
 * > targetSdk 也不会让这个机制失效。
 */
object NativeLibraryBundler {

    private const val TAG = "NativeLibraryBundler"

    /**
     * 打包进 APK 的库清单：**打包文件名 → 运行时的真实文件名（SONAME）**。
     *
     * 键是 `jniLibs/arm64-v8a/` 下的文件名；值是它落到 Termux 库目录后应该叫什么。
     * 两者不同的原因是：Termux 里带版本号的库（`libtalloc.so.2`、`libz.so.1` 等）
     * 在其 SONAME 中带主版本号，而 APK 打包时用不带版本的名字更直观、也便于
     * 后续替换版本。
     *
     * 例外是 `libxxhash.so`：上游 deb 里磁盘名是 `libxxhash.so.0.8.3`，SONAME 是
     * `libxxhash.so.0`。**打包名必须是不带版本号的 `libxxhash.so`** ——
     * AGP 的原生库打包只收集 `lib*.so` 这种形态，带 `.0` 后缀的文件会被
     * `mergeDebugNativeLibs` 直接丢弃（实测：merged 目录里就是找不到它，
     * 随后 `stripDebugDebugSymbols` 报 `Failed to create MD5 hash for file content`）。
     * 所以这里用「打包名 → SONAME」这层映射把它还原回去。
     *
     * 这些库的共同特征：**NEEDED 只有 `libc.so`**（或仅依赖本清单内的其他库），
     * 因此打包它们不会引入新的缺失链。每一项都已用 `readelf -d` 逐一核验。
     *
     * ## 来源与已核验的对应关系
     * 除下面两项外，全部取自 App 自己安装的**同一个** Termux bootstrap rootfs
     * （`bootstrap-2026.05.24-r1+apt.android-7 / bootstrap-aarch64.zip`，
     * SHA256 `1f48f4d0…d17b`）的 `lib/` 目录，并已按 SONAME 打成不带版本号的文件名，
     * 与 `app/src/main/jniLibs/arm64-v8a/` 下的文件名一一对应 ——
     * [BundledLibrariesTest] 会在 CI 上校验这层一致性（清单有、文件没有就会失败）。
     *
     * ## 这张表只放「真的随 APK 打包」的库
     * `libtalloc.so.2` 与 `libandroid-shmem.so` 属于 **proot 依赖链**，官方 rootfs 不含
     * proot（`bootstrap-aarch64.zip` 里搜不到 proot/talloc/shmem），它们只能由运行时的
     * proot-distro 提供 —— 因此**不在这张表里**，见 [RUNTIME_OPTIONAL_LIBRARIES]。
     *
     * 把它们混进这张表会造成一个持续存在的假警报：由本表派生的
     * [TermuxRuntime.termuxLibraryCandidates] 会为每一项检查「是否已就位」，
     * 而这两个库在设备上**永远不会**位于 App 能落盘的位置，于是每次初始化都会打出
     * 「依赖 libtalloc.so.2 未找到」这类无法消除的警告，把真正缺库时的提示淹没掉。
     */
    val PACKAGED_LIBRARIES: Map<String, String> = linkedMapOf(
        // ── apt 直接依赖 ──────────────────────────────────────
        "libandroid-glob.so" to "libandroid-glob.so",
        "libiconv.so" to "libiconv.so",
        "libcharset.so" to "libcharset.so",          // libiconv 的兄弟库
        "libz.so" to "libz.so.1",
        "libzstd.so" to "libzstd.so.1",
        "liblzma.so" to "liblzma.so.5",
        "libbz2.so" to "libbz2.so.1.0",
        "liblz4.so" to "liblz4.so",
        "libpcre2-8.so" to "libpcre2-8.so",          // grep / libandroid-selinux 需要
        // ── dpkg / proot / coreutils 依赖 ─────────────────────
        "libandroid-support.so" to "libandroid-support.so",
        "libandroid-selinux.so" to "libandroid-selinux.so",
        // ── 第二批：实测体检暴露出的缺口 ─────────────────────
        // 来源：设备日志
        //   [bootstrap] 警告: apt-get 可能缺少 libapt-private.so, libapt-pkg.so
        //   [bootstrap] 警告: proot  可能缺少 libandroid-shmem.so
        //   [bootstrap] 警告: tar    可能缺少 libacl.so
        // 这批库补齐后，apt-get / proot / tar 才能在设备上真正启动。
        // 注意 libapt-pkg.so 自身还依赖 libgcrypt / libxxhash / libc++_shared，
        // 这三者又依赖 libgpg-error —— 整条链一并补齐，避免「补一个又缺一个」。
        "libapt-pkg.so" to "libapt-pkg.so",
        "libapt-private.so" to "libapt-private.so",
        "libacl.so" to "libacl.so",
        "libattr.so" to "libattr.so",                // libacl 的 SONAME 依赖
        "libc++_shared.so" to "libc++_shared.so",    // apt 的 C++ 运行时
        "libgcrypt.so" to "libgcrypt.so",
        "libgpg-error.so" to "libgpg-error.so",      // libgcrypt 的依赖
        "libxxhash.so" to "libxxhash.so.0"           // 见下方注释：打包名不能带版本号
    )

    /**
     * **不随 APK 打包**、由 Termux 侧包管理器（proot-distro）提供的库。
     *
     * 这两个库属于 proot 依赖链，而官方 bootstrap rootfs 里没有 proot
     * （`bootstrap-aarch64.zip` 中搜不到 proot/talloc/shmem），App 无从提供副本。
     * 保留它们只是为了把「proot 链路依赖什么」写在一处。
     *
     * 它们**不参与落盘，也不参与「缺库就报警告」的静态检查**：
     * 在官方 rootfs 上它们本来就不存在，静态检查只会每次初始化都报一遍
     * 「依赖 libtalloc.so.2 未找到」，把真正缺库时的提示淹没掉。
     * 是否真的缺失，交给 [auditTermuxBinaries] 按 proot 二进制的 DT_NEEDED 动态判断。
     */
    val RUNTIME_OPTIONAL_LIBRARIES: Map<String, String> = linkedMapOf(
        "libtalloc.so" to "libtalloc.so.2",           // proot 硬依赖
        "libandroid-shmem.so" to "libandroid-shmem.so"
    )

    /**
     * 全部登记项 = 打包 + 非打包，仅供「清单 ↔ jniLibs」一致性守卫使用
     * （见 [BundledLibrariesTest]）。**运行时逻辑一律用 [PACKAGED_LIBRARIES]**，
     * 否则又会把非打包项当成缺失。
     */
    val BUNDLED_LIBRARIES: Map<String, String> =
        PACKAGED_LIBRARIES + RUNTIME_OPTIONAL_LIBRARIES

    /**
     * 返回随 APK 打包的共享库所在目录；目录不存在时返回 null。
     *
     * 调用方必须容忍 null —— 降级路径（从 Termux 源 apt 安装）依然可用。
     */
    fun nativeLibraryDir(context: Context): String? {
        val dir = runCatching { context.applicationInfo.nativeLibraryDir }.getOrNull()
        if (dir.isNullOrBlank()) {
            Log.w(TAG, "nativeLibraryDir 不可用")
            return null
        }
        val present = PACKAGED_LIBRARIES.keys.count { File(dir, it).isFile }
        if (present == 0) {
            Log.w(TAG, "nativeLibraryDir 下未找到任何打包库：$dir")
            return null
        }
        Log.i(TAG, "打包库可用：$dir（$present/${PACKAGED_LIBRARIES.size}）")
        return dir
    }

    /**
     * 把打包的库复制到 [targetDirs]（只补缺失，不覆盖已有文件）。
     *
     * 不覆盖是刻意的：Termux 里若已存在（例如用户已 apt 安装过），说明版本由包管理器
     * 管辖，App 不应插手。只有缺失时才用打包版本兜底。
     *
     * @return 实际落盘的路径列表（空表示无需落盘或源不可用）。
     */
    fun provision(bundledDir: String?, targetDirs: List<File>): List<String> {
        if (bundledDir.isNullOrBlank()) return emptyList()
        val written = mutableListOf<String>()

        PACKAGED_LIBRARIES.forEach { (packagedName, runtimeName) ->
            val source = File(bundledDir, packagedName)
            if (!source.isFile) return@forEach

            targetDirs.forEach { dir ->
                val dest = File(dir, runtimeName)
                if (dest.isFile && dest.length() > 0) return@forEach
                runCatching {
                    dir.mkdirs()
                    source.copyTo(dest, overwrite = false)
                    dest.setReadable(true, false)
                    // 数据目录默认挂载 nosuid/noexec，可执行位对 ELF 的 *加载* 不是必需，
                    // 但保留它能让 dlopen/exec 行为更符合直觉，且无副作用。
                    dest.setExecutable(true, false)
                    written += dest.absolutePath
                }.onFailure { e ->
                    Log.w(TAG, "落盘 $runtimeName -> ${dest.absolutePath} 失败: ${e.message}")
                }
            }
        }
        if (written.isNotEmpty()) {
            Log.i(TAG, "已落盘打包库 ${written.size} 个")
        }
        return written
    }

    /**
     * 读取 [binary] 的 DT_NEEDED 列表（即它在启动时一定要能找到的库）。
     *
     * 实现：优先用 `linker64 --list`（Termux 自带 readelf 不一定可用），
     * 失败则退回直接解析 ELF 的 `.dynamic` 段 —— 后者是纯字节操作，
     * 不依赖任何外部命令，因此在任何设备上都能给出答案。
     *
     * ## 为什么不再用 linker 的报错文本直接当结论
     * linker 的失败输出里混着 `linker64": library "xxx" not found: needed by ...`
     * 这类**非库名**片段。早期版本按「含 not found 就取最后一段」粗暴解析，
     * 结果把 `linker64":library "libreadline.so.8" not found: needed by main`
     * 整串塞进了库名里，用户看到的「缺库清单」其实是坏字符串。
     * 现在只认 [需要的库名正则]，且最后用库目录实存性交叉验证。
     */
    fun neededLibrariesOf(binary: File): List<String> {
        elfNeeded(binary)?.let { if (it.isNotEmpty()) return it }
        // 退化路径：交给 linker 解析并严格提取
        return linkerView(binary)
    }

    /** 严格从一段文本里提取「缺失的库名」。只接受合法库文件名。 */
    private fun extractMissingLibNames(text: String): List<String> {
        // 例：library "libreadline.so.8" not found
        //     cannot locate symbol ... / linker64":library "x" not found
        val quoted = Regex("\"([^\"]+)\"\\s*not found")
        return quoted.findAll(text)
            .map { it.groupValues[1] }
            .filter { LIB_NAME.matches(it) }
            .distinct()
            .toList()
    }

    private val LIB_NAME = Regex("^lib[A-Za-z0-9_+.-]+\\.so(\\.[0-9]+)*$")

    /** 用 linker64 --list 解析依赖；失败返回空表。 */
    private fun linkerView(binary: File): List<String> {
        val linker = listOf(
            File("/system/bin/linker64"),
            File("/apex/com.android.runtime/bin/linker64")
        ).firstOrNull { it.isFile } ?: return emptyList()

        val prefix = binary.parentFile?.parentFile ?: return emptyList()
        val paths = buildList {
            add(File(prefix, "lib"))
            add(File(prefix, "usr/lib"))
            add(File(prefix, "data/data/com.termux/files/usr/lib"))
            add(File("/system/lib64"))
        }.filter { it.isDirectory }.joinToString(":") { it.absolutePath }

        return runCatching {
            val process = ProcessBuilder(linker.absolutePath, "--list", binary.absolutePath)
                .apply { environment()["LD_LIBRARY_PATH"] = paths }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return emptyList()
            }
            extractMissingLibNames(output)
        }.getOrElse {
            Log.w(TAG, "linker 探测失败: ${it.message}")
            emptyList()
        }
    }

    /**
     * 直接解析 ELF 的 `.dynamic` 段取出 DT_NEEDED。
     *
     * 只用 FileChannel 随机读，不把整个 ELF 载入内存（库文件可达数 MB）。
     * 结构参照 ELF64 规范；不认识的格式一律返回 null 交给上层降级。
     */
    private fun elfNeeded(binary: File): List<String>? = runCatching {
        java.io.RandomAccessFile(binary, "r").use { raf ->
            val header = ByteArray(64)
            if (raf.read(header) != 64) return null
            // 魔数 \x7fELF
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) return null
            // e_ident[EI_CLASS]：1=32 位，2=64 位
            val is64 = header[4] == 2.toByte()
            val le = header[5] == 1.toByte()
            fun u16(off: Int): Int =
                if (le) (header[off].toInt() and 0xff) or ((header[off + 1].toInt() and 0xff) shl 8)
                else ((header[off].toInt() and 0xff) shl 8) or (header[off + 1].toInt() and 0xff)
            fun u32(off: Int): Long {
                var v = 0L
                for (i in 0 until 4) {
                    val b = header[off + i].toInt() and 0xff
                    v = if (le) v or (b.toLong() shl (8 * i)) else (v shl 8) or b.toLong()
                }
                return v
            }
            fun u64(off: Int): Long {
                var v = 0L
                for (i in 0 until 8) {
                    val b = header[off + i].toLong() and 0xff
                    v = if (le) v or (b shl (8 * i)) else (v shl 8) or b
                }
                return v
            }

            // 32 位头字段起点不同，本项目只处理 arm64，遇到 32 位直接放弃
            if (!is64) return null

            val phOff = u64(32)
            val phEntSize = u16(54).toLong()
            val phNum = u16(56)
            if (phOff == 0L || phEntSize == 0L || phNum == 0) return null

            val ph = ByteArray(phEntSize.toInt())
            var dynOff = -1L
            var dynSize = 0L
            for (i in 0 until phNum) {
                raf.seek(phOff + i * phEntSize)
                if (raf.read(ph) != ph.size) return null
                // p_type == PT_DYNAMIC (2)
                var pType = 0L
                run {
                    var v = 0L
                    for (k in 0 until 4) {
                        val b = ph[k].toLong() and 0xff
                        v = if (le) v or (b shl (8 * k)) else (v shl 8) or b
                    }
                    pType = v
                }
                if (pType == 2L) {
                    var v64 = 0L
                    for (k in 0 until 8) {
                        val b = ph[8 + k].toLong() and 0xff
                        v64 = if (le) v64 or (b shl (8 * k)) else (v64 shl 8) or b
                    }
                    dynOff = v64
                    var vSz = 0L
                    for (k in 0 until 8) {
                        val b = ph[32 + k].toLong() and 0xff
                        vSz = if (le) vSz or (b shl (8 * k)) else (vSz shl 8) or b
                    }
                    dynSize = vSz
                    break
                }
            }
            if (dynOff < 0 || dynSize <= 0) return null

            // 先把 .dynamic 表整段读出来（通常几百字节），再解析 DT_STRTAB / DT_NEEDED
            val dyn = ByteArray(dynSize.toInt().coerceAtMost(1 shl 16))
            raf.seek(dynOff)
            val read = raf.read(dyn)
            if (read <= 0) return null

            var strTabAddr = -1L
            val neededOffsets = mutableListOf<Long>()
            var k = 0
            while (k + 16 <= read) {
                var tag = 0L
                for (j in 0 until 8) {
                    val b = dyn[k + j].toLong() and 0xff
                    tag = if (le) tag or (b shl (8 * j)) else (tag shl 8) or b
                }
                var v = 0L
                for (j in 0 until 8) {
                    val b = dyn[k + 8 + j].toLong() and 0xff
                    v = if (le) v or (b shl (8 * j)) else (v shl 8) or b
                }
                if (tag == 0L) break            // DT_NULL
                if (tag == 5L) strTabAddr = v   // DT_STRTAB（这里是文件内偏移）
                if (tag == 1L) neededOffsets += v // DT_NEEDED（strtab 内偏移）
                k += 16
            }
            if (strTabAddr < 0 || neededOffsets.isEmpty()) return null

            val names = mutableListOf<String>()
            neededOffsets.forEach { no ->
                raf.seek(strTabAddr + no)
                val bytes = ByteArray(256)
                val n = raf.read(bytes)
                if (n <= 0) return@forEach
                val zero = bytes.indexOfFirst { it == 0.toByte() }
                val name = String(bytes, 0, if (zero >= 0) zero else n, Charsets.UTF_8)
                if (name.isNotBlank()) names += name
            }
            names
        }
    }.getOrNull()

    /**
     * 把裸的库名解析成 Termux 里实际存在的文件路径。
     *
     * Termux 的库名带版本（`libreadline.so.8` 是 SONAME，磁盘上是 `libreadline.so.8.3`），
     * Android 系统库则落在 `/system/lib64`。这里把所有可能的位置都找一遍，
     * 找不到才算「真的缺」。
     */
    private fun locateLibrary(name: String, prefix: File, extraLibraryDir: String?): File? {
        val dirs = listOfNotNull(
            extraLibraryDir?.let { File(it) },
            File(prefix, "lib"),
            File(prefix, "usr/lib"),
            File(prefix, "data/data/com.termux/files/usr/lib"),
            File("/system/lib64"),
            File("/vendor/lib64"),
            File("/apex/com.android.runtime/lib64")
        )
        // 精确命中
        dirs.forEach { d -> File(d, name).takeIf { it.isFile }?.let { return it } }
        // 带版本后缀命中：libreadline.so.8 → libreadline.so.8.3
        dirs.forEach { d ->
            val list = d.listFiles() ?: return@forEach
            list.firstOrNull { it.name == name || it.name.startsWith("$name.") }
                ?.let { return it }
        }
        return null
    }

    /**
     * 探测 [binary] 缺失的库（已排除能从现有目录/系统库找到的）。
     *
     * 任何异常都返回空列表：这是诊断增强，绝不能影响主流程。
     */
    fun missingLibrariesFor(binary: File, extraLibraryDir: String?): List<String> {
        val prefix = binary.parentFile?.parentFile ?: return emptyList()
        return neededLibrariesOf(binary).filter { name ->
            locateLibrary(name, prefix, extraLibraryDir) == null
        }
    }

    /**
     * 批量探测一组 Termux 关键命令，汇总「谁缺什么」。
     *
     * ## 只探测 apt 链条，不要拉 bash 进来
     * 早期版本把 `bash` 也放进 [probes]，结果它在很多设备上「缺」 `libreadline.so.8`
     * —— 而 readline 只服务交互式 bash 的 Tab 补全与历史，**与 apt 能否运行毫无关系**。
     * 那个假警报会导致体检判定失败，进而让整个 apt 安装流程被拒绝执行，
     * 表现为「环境本来好好的，更新后却初始化失败」。
     *
     * 现在只体检真正决定 apt/dpkg 能否启动的那几个可执行文件。
     * bash 的依赖仍然会被 [auditIfEnabled] 报告出来，但**不参与成败判定**。
     *
     * @return 命令名 → 缺失库列表（只含确实有缺失的项；全部正常时为空 Map）
     */
    fun auditTermuxBinaries(prefix: File, extraLibraryDir: String?): Map<String, List<String>> {
        val found = linkedMapOf<String, List<String>>()
        // 决定性命令：这些起不来才算致命
        criticalProbes.forEach { name ->
            val bin = resolveBinary(prefix, name) ?: return@forEach
            val missing = missingLibrariesFor(bin, extraLibraryDir)
            if (missing.isNotEmpty()) found[name] = missing
        }
        return found
    }

    /** apt/dpkg/proot 链条上的决定性命令。缺任何一个，包管理就不可用。 */
    private val criticalProbes = listOf("apt-get", "dpkg", "proot", "tar", "grep")

    /**
     * 仅供显示、不参与判定的命令。
     *
     * bash 在这里：它缺 readline 时依然能执行 apt 脚本（非交互模式用不到 readline），
     * 所以只提示、不拦截。
     */
    private val advisoryProbes = listOf("bash", "sh")

    private fun resolveBinary(prefix: File, name: String): File? = listOf(
        File(prefix, "usr/bin/$name"),
        File(prefix, "bin/$name"),
        File(prefix, "data/data/com.termux/files/usr/bin/$name")
    ).firstOrNull { it.isFile }

    /**
     * 完整审计：返回 (阻断性问题, 仅供参考的提示)。
     *
     * 调用方应当**只根据第一个返回值决定是否中止**流程；第二个纯粹用于日志，
     * 避免再次出现「因为有非致命瑕疵而拒绝执行」的倒退。
     */
    fun auditIfEnabled(
        prefix: File,
        extraLibraryDir: String?
    ): Pair<Map<String, List<String>>, Map<String, List<String>>> {
        val blocking = auditTermuxBinaries(prefix, extraLibraryDir)
        val advisory = linkedMapOf<String, List<String>>()
        advisoryProbes.forEach { name ->
            val bin = resolveBinary(prefix, name) ?: return@forEach
            val missing = missingLibrariesFor(bin, extraLibraryDir)
            if (missing.isNotEmpty()) advisory[name] = missing
        }
        return blocking to advisory
    }
}
