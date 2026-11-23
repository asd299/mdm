package com.misterdownloadmanager.mobile

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                assertNotNull("上游仓库入口应存在", a.findViewById<View>(R.id.rowUpstreamRepo))

                // 上游基线必须写清楚：地址、版本、上游发布日期、本版核对日期
                val repo = a.findViewById<TextView>(R.id.tvUpstreamRepo).text.toString()
                assertTrue("上游地址未写入", repo.contains(Changelog.upstream.repo))
                assertTrue(
                    "参考版本未写入",
                    a.findViewById<TextView>(R.id.tvUpstreamVersion).text.toString()
                        .contains(Changelog.upstream.version)
                )
                assertEquals(
                    Changelog.upstream.released,
                    a.findViewById<TextView>(R.id.tvUpstreamReleased).text.toString()
                )
                assertEquals(
                    Changelog.upstream.checkedAt,
                    a.findViewById<TextView>(R.id.tvUpstreamChecked).text.toString()
                )
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

    /** 版本说明默认只展开半屏，点顶部组件才拉满 */
    @Test
    fun changelogStartsHalfExpandedAndExpandsOnHeaderTap() {
        ActivityScenario.launch(AboutActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<View>(R.id.rowChangelog).performClick()
                ShadowLooper.idleMainLooper()

                val dialog = ShadowDialog.getLatestDialog() as BottomSheetDialog
                dialog.show()
                ShadowLooper.idleMainLooper()

                val behavior = dialog.behavior
                assertFalse("要停半屏必须关掉 fitToContents", behavior.isFitToContents)
                assertEquals("半屏比例", 0.62f, behavior.halfExpandedRatio, 0.001f)
                assertTrue("不应停在折叠态", behavior.skipCollapsed)

                // Robolectric 不做真实布局，先给面板一个确定高度，半屏态才成立
                val bottomSheet = dialog.findViewById<View>(
                    com.google.android.material.R.id.design_bottom_sheet
                )!!
                val parent = bottomSheet.parent as View
                parent.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2280, View.MeasureSpec.EXACTLY),
                )
                parent.layout(0, 0, 1080, 2280)
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED
                ShadowLooper.idleMainLooper()
                assertEquals(
                    "应能停在半屏",
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HALF_EXPANDED,
                    behavior.state,
                )

                dialog.findViewById<View>(R.id.sheetHeader)!!.performClick()
                ShadowLooper.idleMainLooper()
                assertEquals(
                    "点顶部后应完全展开",
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED,
                    behavior.state,
                )
                dialog.dismiss()
            }
        }
    }

    /** 更新记录按新到旧排列，当前版本在最上面 */
    @Test
    fun changelogIsOrderedNewestFirst() {
        val names = Changelog.versions.map { it.name }
        assertEquals(BuildConfig.VERSION_NAME, names.first())
        val sorted = names.sortedByDescending { name ->
            name.split('.').map(String::toInt).fold(0L) { acc, part -> acc * 1000 + part }
        }
        assertEquals("版本列表应按新到旧排列", sorted, names)
        assertTrue("每个版本都要有内容", Changelog.versions.all { it.notes.isNotEmpty() })
    }
}
