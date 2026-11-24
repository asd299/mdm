package com.misterdownloadmanager.mobile

import android.app.Dialog
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
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
import java.util.concurrent.TimeUnit

/**
 * 列表项交互：
 * - 点击列表项直接展开属性面板（贴底半屏），不再先弹操作面板；
 * - 属性面板底部是左「打开」右「分享」两个按钮；
 * - 打开搜索框时把展开的面板收起来；
 * - 长按进入多选时关掉下拉刷新，避免手指继续下滑就刷新。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ItemPanelTest {

    private fun fieldOf(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun seed(a: MainActivity, count: Int = 3) {
        synchronized(Repo.tasks) {
            Repo.tasks.clear()
            repeat(count) { i ->
                val at = 1_700_000_000_000L + i * 1_000L
                Repo.tasks.add(
                    DownloadTask(
                        id = "t$i",
                        url = "https://example.com/file$i.mp4",
                        fileName = "file$i.mp4",
                        dir = a.filesDir.absolutePath,
                        totalBytes = 1000,
                        segments = mutableListOf(Segment(0, 0, 999, 1000)),
                        status = Status.COMPLETED,
                        addedAt = at,
                        startedAt = at,
                        finishedAt = at + 1_000,
                        activeMillis = 1_000,
                    )
                )
            }
        }
    }

    private fun layoutList(rv: RecyclerView, height: Int = 1600) {
        rv.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        rv.layout(0, 0, 1080, height)
    }

    private fun openFirst(a: MainActivity): Dialog {
        val rv = a.findViewById<RecyclerView>(R.id.recycler)
        (rv.adapter as DownloadsAdapter).refresh()
        ShadowLooper.idleMainLooper()
        layoutList(rv)
        rv.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
        ShadowLooper.idleMainLooper()
        return ShadowDialog.getLatestDialog()
    }

    private fun layoutHost(d: Dialog, a: MainActivity): View {
        val host = d.findViewById<View>(R.id.panelHost)
        val screenH = a.resources.displayMetrics.heightPixels
        host!!.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY),
        )
        host.layout(0, 0, 1080, screenH)
        return host
    }

    /** 点击列表项：直接弹出属性面板，半屏且贴住屏幕底 */
    @Test
    fun tappingItemOpensPropertiesFlushToBottom() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val d = openFirst(a)
                assertNotNull("点击列表项应弹出属性面板", d)
                assertTrue("面板应显示出来", d.isShowing)

                layoutHost(d, a)
                val sheet = d.findViewById<View>(R.id.propertiesSheet)
                assertNotNull("属性面板应存在", sheet)
                val screenH = a.resources.displayMetrics.heightPixels
                assertEquals("默认停半屏", (screenH * 0.62f).toInt(), sheet!!.height)
                assertEquals("底边贴住屏幕底", screenH, sheet.bottom)

                val list = d.findViewById<LinearLayout>(R.id.propList)!!
                assertTrue("属性行不应为空", list.childCount >= 10)
                d.dismiss()
            }
        }
    }

    /** 属性面板底部只有两个按钮：左打开、右分享 */
    @Test
    fun propertiesSheetHasOpenLeftAndShareRight() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val d = openFirst(a)
                layoutHost(d, a)

                val open = d.findViewById<TextView>(R.id.btnPropOpen)
                val share = d.findViewById<TextView>(R.id.btnPropShare)
                assertNotNull("应有打开按钮", open)
                assertNotNull("应有分享按钮", share)
                assertEquals("左按钮是打开", "打开", open!!.text.toString())
                assertEquals("右按钮是分享", "分享", share!!.text.toString())
                assertTrue(
                    "打开应在分享左侧：open.left=${open.left}, share.left=${share.left}",
                    open.left < share.left
                )
                assertEquals("两个按钮应在同一行", open.top, share.top)
                d.dismiss()
            }
        }
    }

    /** 打开搜索框（菜单项展开）时，展开的属性面板要收起来 */
    @Test
    fun expandingSearchClosesProperties() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val d = openFirst(a)
                assertTrue(d.isShowing)

                val item = fieldOf(a, "searchItem") as MenuItem
                item.expandActionView()
                ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)

                assertFalse("打开搜索框应收起属性面板", d.isShowing)
            }
        }
    }

    /** 长按进入多选：下拉刷新必须关掉，否则多选状态下手一滑就刷新列表 */
    @Test
    fun longPressTurnsOffPullToRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                (rv.adapter as DownloadsAdapter).refresh()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                val refresh = a.findViewById<androidx.swiperefreshlayout.widget.SwipeRefreshLayout>(
                    R.id.swipeRefresh
                )
                assertTrue("初始应允许下拉刷新", refresh.isEnabled)

                rv.findViewHolderForAdapterPosition(0)!!.itemView.performLongClick()
                ShadowLooper.idleMainLooper()

                assertFalse("长按进入多选后不应再触发下拉刷新", refresh.isEnabled)
                assertTrue("应进入多选", (rv.adapter as DownloadsAdapter).selectionMode)
            }
        }
    }
}
