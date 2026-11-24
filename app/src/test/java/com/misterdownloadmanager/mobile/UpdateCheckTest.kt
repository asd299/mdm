package com.misterdownloadmanager.mobile

import android.graphics.Path
import android.graphics.RectF
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 检查更新：版本号逐段比较（支持 v 前缀与 `上游版本.迭代号` 这种四段版本），
 * 以及关于页上的入口与项目链接。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class UpdateCheckTest {

    @Test
    fun comparesVersionSegmentsNotStrings() {
        // 字符串比较会认为 "1001" < "999"，必须按数字比
        assertTrue(UpdateChecker.isNewer("v0.5.2.1002", "0.5.2.1001"))
        assertTrue(UpdateChecker.isNewer("0.5.2.1001", "0.5.2.999"))
        assertFalse(UpdateChecker.isNewer("v0.5.2.1001", "0.5.2.1001"))
        assertFalse(UpdateChecker.isNewer("0.5.2.1000", "0.5.2.1001"))
        assertTrue("上游升版后应算新版本", UpdateChecker.isNewer("0.6.0.1001", "0.5.2.1001"))
        assertTrue("tag 带大写 V 也要能比", UpdateChecker.isNewer("V0.5.3", "0.5.2.1001"))
    }

    @Test
    fun tagSegmentsIgnorePrefix() {
        assertEquals(listOf(0, 5, 2, 1001), UpdateChecker.segments("v0.5.2.1001"))
        assertEquals(listOf(1, 2, 3), UpdateChecker.segments("1.2.3"))
    }

    @Test
    fun aboutPageHasUpdateEntryAndProjectLink() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(AboutActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val checkRow = a.findViewById<android.view.View>(R.id.rowCheckUpdate)
                val repoRow = a.findViewById<android.view.View>(R.id.rowProjectRepo)
                assertNotNull("应有检查更新入口", checkRow)
                assertNotNull("应有项目主页入口", repoRow)

                val checkSub = checkRow.findViewById<TextView>(R.id.rowSub).text.toString()
                assertTrue("检查更新下方应显示当前版本：$checkSub", checkSub.contains(BuildConfig.VERSION_NAME))

                val repoSub = repoRow.findViewById<TextView>(R.id.rowSub).text.toString()
                assertEquals("github.com/${Changelog.PROJECT_REPO}", repoSub)
                assertEquals("https://github.com/${Changelog.PROJECT_REPO}", Changelog.PROJECT_URL)
                assertFalse("本项目地址不应再指向上游仓库", Changelog.PROJECT_URL.contains("anefzaoui"))
            }
        }
    }
}
