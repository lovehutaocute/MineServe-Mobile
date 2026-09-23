package com.mineserve.mobile.server

import io.airlift.compress.zstd.ZstdCompressor
import io.airlift.compress.zstd.ZstdInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 「导入 .tar.zst」所用 zstd 解码器的守卫测试。
 *
 * ## 背景（真实缺陷）
 * 这条路径原先用 commons-compress 的 `ZstdCompressorInputStream`，它需要
 * `com.github.luben:zstd-jni` 提供的原生库；而 zstd-jni 的 aarch64 `.so` 是 glibc
 * 构建（NEEDED 含 `libc.so.6`），jar 里也没有 `android/` 目录，在 Android 的 bionic
 * 上必然 `dlopen failed`。也就是说：这个格式在真机上从来没有导入成功过。
 *
 * 现在改用 aircompressor 的**纯 Java** 解码器（见 ServerImporter 的 openTar）。
 * 本测试锁住两件事：
 *  1. 该依赖确实在 test classpath 上 —— 谁把它换回需要原生库的实现，这里会先失败；
 *  2. 标准 zstd 流能被逐字节还原。
 *
 * 局限（已知）：数据由同一个库压出，覆盖不到「外部 zstd 工具生成的、带字典或多帧的
 * 流」。真机验证仍需实际导入一次 .tar.zst。
 */
class ZstdStreamTest {

    @Test
    fun decodesStandardZstdStream() {
        // 高度可压缩的数据
        val payload = ByteArray(64 * 1024) { (it * 31 % 251).toByte() }
        assertRoundTrip(payload)
    }

    @Test
    fun decodesIncompressiblePayload() {
        // 伪随机数据：确认解码器不依赖「数据可压缩」这类隐含假设
        var seed = 0x12345678
        val payload = ByteArray(32 * 1024) {
            seed = seed * 1103515245 + 12345
            (seed ushr 16).toByte()
        }
        assertRoundTrip(payload)
    }

    private fun assertRoundTrip(payload: ByteArray) {
        val compressed = compress(payload)
        assertTrue("压缩结果不应明显大于原文", compressed.size <= payload.size + 64)

        val decoded = ZstdInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        assertArrayEquals("解压结果必须与原文逐字节一致", payload, decoded)
    }

    /**
     * aircompressor 的 `ZstdCompressor` 只暴露低层接口
     * （`maxCompressedLength` + `compress(src, srcOff, srcLen, dst, dstOff, maxDstLen)`），
     * 没有 `compress(byte[])` 这样的便捷重载。
     */
    private fun compress(payload: ByteArray): ByteArray {
        val compressor = ZstdCompressor()
        val out = ByteArray(compressor.maxCompressedLength(payload.size))
        val written = compressor.compress(payload, 0, payload.size, out, 0, out.size)
        return out.copyOf(written)
    }
}
