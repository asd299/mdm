package com.misterdownloadmanager.mobile

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

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
                assertNotNull("应弹出面板", dialog)
                assertTrue("面板应真的显示出来", dialog!!.isShowing)

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

    /**
     * 面板默认半屏，点顶部组件才拉满；两种状态下底边都必须贴住屏幕底，
     * 不能在下面露出一条半透明遮罩（旧实现把窗口设成 82% 高、窗口居中，底部就空了）。
     */
    @Test
    fun changelogSticksToBottomHalfThenExpands() {
        ActivityScenario.launch(AboutActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<View>(R.id.rowChangelog).performClick()
                ShadowLooper.idleMainLooper()

                val dialog = ShadowDialog.getLatestDialog()!!
                val host = dialog.findViewById<View>(R.id.panelHost)
                val sheet = dialog.findViewById<View>(R.id.changelogSheet)
                assertNotNull("面板载体应存在", host)
                assertNotNull("面板应存在", sheet)

                val screenH = a.resources.displayMetrics.heightPixels
                fun layoutNow() {
                    host!!.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY),
                    )
                    host.layout(0, 0, 1080, screenH)
                }

                layoutNow()
                assertEquals("默认应停半屏", (screenH * 0.62f).toInt(), sheet!!.height)
                assertEquals("半屏时底边要贴住屏幕底", screenH, sheet.bottom)

                sheet.findViewById<View>(R.id.sheetHeader).performClick()
                ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS)
                layoutNow()
                assertEquals("点顶部后应拉满", (screenH * 0.82f).toInt(), sheet.height)
                assertEquals("拉满后底边仍要贴住屏幕底", screenH, sheet.bottom)
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
