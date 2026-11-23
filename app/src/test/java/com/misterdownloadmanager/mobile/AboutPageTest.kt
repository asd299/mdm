package com.misterdownloadmanager.mobile

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

/** 关于页：版本信息、入口、以及自下而上的版本更新面板 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class AboutPageTest {

    @Test
    fun showsVersionAndBothEntries() {
        ActivityScenario.launch(AboutActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val version = a.findViewById<TextView>(R.id.tvAboutVersion).text.toString()
                assertTrue("应展示当前版本号", version.contains(BuildConfig.VERSION_NAME))

                assertNotNull("版本更新说明入口应存在", a.findViewById<View>(R.id.rowChangelog))
                assertNotNull("崩溃日志入口应存在", a.findViewById<View>(R.id.rowCrashLog))
                assertNotNull("项目链接应存在", a.findViewById<View>(R.id.btnProject))
            }
        }
    }

    /** 点「版本更新说明」应从下往上弹出面板，并列出所有版本 */
    @Test
    fun changelogSheetListsEveryVersion() {
        ActivityScenario.launch(AboutActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<View>(R.id.rowChangelog).performClick()
                ShadowLooper.idleMainLooper()

                val dialog = ShadowDialog.getLatestDialog()
                assertTrue("应弹出底部面板", dialog is BottomSheetDialog)
                dialog.show()

                val list = dialog.findViewById<LinearLayout>(R.id.changeList)
                assertNotNull("面板里应有版本列表", list)
                assertEquals("每个版本一条记录", Changelog.versions.size, list!!.childCount)

                val first = list.getChildAt(0)
                assertEquals("v${Changelog.versions[0].name}", first.findViewById<TextView>(R.id.tvVer).text.toString())
                assertEquals(View.VISIBLE, first.findViewById<TextView>(R.id.tvCurrent).visibility)
                dialog.dismiss()
            }
        }
    }

    /** 更新记录按新到旧排列，当前版本在最上面 */
    @Test
    fun changelogIsOrderedNewestFirst() {
        val names = Changelog.versions.map { it.name }
        assertEquals(BuildConfig.VERSION_NAME, names.first())
        val sorted = names.sortedByDescending { it.split('.').map(String::toInt).let { v -> v[0] * 10000 + v[1] * 100 + v[2] } }
        assertEquals("版本列表应按新到旧排列", sorted, names)
        assertTrue("每个版本都要有内容", Changelog.versions.all { it.notes.isNotEmpty() })
    }
}
