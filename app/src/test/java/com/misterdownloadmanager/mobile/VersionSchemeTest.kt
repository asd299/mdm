package com.misterdownloadmanager.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            "迭代号应按版本条数递增（首版 1001，每发一版 +1）",
            Changelog.ITERATION_BASE + Changelog.versions.size - 1,
            parts[3].toInt(),
        )
    }

    @Test
    fun changelogStartsAtFirstVersion() {
        assertTrue("版本记录不能为空", Changelog.versions.isNotEmpty())
        // 列表按发布时间倒序：最新版本在最前，首版在最后
        assertEquals("最新版本应排在最前", BuildConfig.VERSION_NAME, Changelog.versions.first().name)
        assertTrue("每个版本都要有内容", Changelog.versions.first().notes.size >= 3)
        assertTrue("每个版本都要有日期", date.matches(Changelog.versions.first().date))
        assertEquals(
            "首版应为 0.5.2.1001",
            "${Changelog.upstream.version}.${Changelog.ITERATION_BASE}",
            Changelog.versions.last().name,
        )
        // 版本号必须逐版递减，不能重复或乱序
        val names = Changelog.versions.map { it.name }
        assertEquals("版本号不能重复：$names", names.size, names.toSet().size)
        assertEquals(names.sortedDescending(), names)
    }

    @Test
    fun upstreamIsRecordedWithVersionAndDates() {
        val up = Changelog.upstream
        assertTrue("上游地址应为 github 仓库", up.url.startsWith("https://github.com/"))
        assertTrue("仓库名应出现在地址里", up.url.contains(up.repo))
        assertTrue("上游版本号缺失", up.version.matches(Regex("""\d+\.\d+\.\d+""")))
        assertTrue("上游发布日期格式不对：${up.released}", date.matches(up.released))
        assertTrue("核对日期格式不对：${up.checkedAt}", date.matches(up.checkedAt))
        // 关于页现在有两处地址：上游项目（用于跟进）与本项目（项目主页 / 检查更新）
        assertEquals(
            "本项目地址应指向自己的仓库",
            "https://github.com/${Changelog.PROJECT_REPO}",
            Changelog.PROJECT_URL,
        )
        assertNotEquals("本项目地址不应与上游混用", up.url, Changelog.PROJECT_URL)
    }
}
