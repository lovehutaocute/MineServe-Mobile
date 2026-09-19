package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「检测更新」依赖 GitHub Releases API 的响应结构，而该响应里有大量可空字段。
 *
 * 背景：v1.2.6 的发布说明留空后，GitHub 返回的是 `"body": null`（不是 `""`），
 * 而 DTO 当时声明为非空 `String`，kotlinx.serialization 直接抛
 * `Expected string literal but 'null' literal was found at path: $.body`，
 * 线上检测更新全线失败。这里的样本都取自真实响应，防止再退化。
 */
class AppUpdateServiceTest {

    /** 真实响应片段：发布说明留空 → body 为 null，且夹杂大量未建模字段。 */
    private val emptyBodyRelease = """
        {
          "url": "https://api.github.com/repos/lovehutaocute/MineServe-Mobile/releases/1",
          "tag_name": "v1.2.6",
          "name": "MineServe Mobile 1.2.6",
          "body": null,
          "draft": false,
          "prerelease": false,
          "zipball_url": "https://api.github.com/repos/lovehutaocute/MineServe-Mobile/zipball/v1.2.6",
          "assets": [
            {
              "name": "MineServeMobile-arm64-v8a-release.apk",
              "browser_download_url": "https://github.com/lovehutaocute/MineServe-Mobile/releases/download/v1.2.6/MineServeMobile-arm64-v8a-release.apk",
              "size": 14700000,
              "content_type": "application/vnd.android.package-archive"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun emptyReleaseBodyParsesAsBlankInsteadOfFailing() {
        val release = AppUpdateService.decodeRelease(emptyBodyRelease)

        assertEquals("v1.2.6", release.tag_name)
        assertEquals("", release.body?.trim().orEmpty())
        assertEquals(1, release.assets.size)
        assertEquals("MineServeMobile-arm64-v8a-release.apk", release.assets.first().name)
    }

    @Test
    fun keepsBodyWhenPresent() {
        val release = AppUpdateService.decodeRelease(
            """{"tag_name":"v1.2.7","body":"  修复检测更新失败  ","assets":[]}"""
        )

        assertEquals("修复检测更新失败", release.body?.trim())
    }

    /** 上游把整个字段写成 null 时，应回退到默认值而不是让解析崩溃。 */
    @Test
    fun nullFieldsFallBackToDefaults() {
        val release = AppUpdateService.decodeRelease(
            """{"tag_name":null,"body":null,"assets":null}"""
        )

        assertEquals("", release.tag_name)
        assertEquals("", release.body?.trim().orEmpty())
        assertTrue(release.assets.isEmpty())
    }

    /** 缺少键（旧式精简响应）同样应走默认值。 */
    @Test
    fun missingKeysFallBackToDefaults() {
        val release = AppUpdateService.decodeRelease("{}")

        assertEquals("", release.tag_name)
        assertEquals("", release.body?.trim().orEmpty())
        assertTrue(release.assets.isEmpty())
    }

    /**
     * 版本号按数字段比较。
     * 注意 1.2.6 是 1.2.16 之后的重新编号版本，纯字符串比较会把两者判反。
     */
    @Test
    fun comparesVersionsByNumericSegment() {
        assertTrue(AppUpdateService.compare("1.2.16", "1.2.6") > 0)
        assertTrue(AppUpdateService.compare("1.2.10", "1.2.9") > 0)
        assertTrue(AppUpdateService.compare("26.2", "1.21.11") > 0)
        assertEquals(0, AppUpdateService.compare("v1.2.6", "1.2.6"))
        assertEquals(0, AppUpdateService.compare("1.2.6", "1.2.6"))
        assertTrue(AppUpdateService.compare("1.2.7", "1.2.6") > 0)
    }
}
