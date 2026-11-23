package com.misterdownloadmanager.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 版本号规则：`<上游版本号>.<迭代号>`，0.5.2.1001 是本项目第一版。
 * 这类约定靠人记容易走样，用测试钉住，改版本号时会被提醒同步文档。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VersionSchemeTest {

    private val date = Regex("""\d{4}-\d{2}-\d{2}""")

    @Test
    fun versionIsUpstreamPlusIteration() {
        val parts = BuildConfig.VERSION_NAME.split('.')
        assertEquals("版本号应为「上游版本号 + 迭代号」四段：${BuildConfig.VERSION_NAME}", 4, parts.size)
        assertEquals(
            "前三段必须与上游基线一致",
            Changelog.upstream.version,
            parts.take(3).joinToString("."),
        )
        assertEquals(
            "迭代号应按版本条数递增（当前第 1 版 = 1001）",
            Changelog.ITERATION_BASE + Changelog.versions.size - 1,
            parts[3].toInt(),
        )
    }

    @Test
    fun changelogStartsAtFirstVersion() {
        assertEquals("记录应从第一个版本开始，且只保留已交付的版本", 1, Changelog.versions.size)
        assertEquals(BuildConfig.VERSION_NAME, Changelog.versions.first().name)
        assertTrue("第一版要有内容", Changelog.versions.first().notes.size >= 5)
        assertTrue("第一版要有日期", date.matches(Changelog.versions.first().date))
    }

    @Test
    fun upstreamIsRecordedWithVersionAndDates() {
        val up = Changelog.upstream
        assertTrue("上游地址应为 github 仓库", up.url.startsWith("https://github.com/"))
        assertTrue("仓库名应出现在地址里", up.url.contains(up.repo))
        assertTrue("上游版本号缺失", up.version.matches(Regex("""\d+\.\d+\.\d+""")))
        assertTrue("上游发布日期格式不对：${up.released}", date.matches(up.released))
        assertTrue("核对日期格式不对：${up.checkedAt}", date.matches(up.checkedAt))
        assertEquals("对外链接应与上游地址一致", up.url, Changelog.PROJECT_URL)
    }
}
