package com.misterdownloadmanager.mobile

import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ListInteractionTest {

    private fun layout(rv: RecyclerView) {
        rv.requestLayout()
        repeat(2) {
            rv.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            )
            rv.layout(0, 0, 1080, 1920)
        }
    }

    /** 下载中：进度 / 速度变化必须真的刷新到界面（DiffUtil 快照修复） */
    @Test
    fun progressAndStatusRefreshWhileDownloading() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val dir = a.filesDir.absolutePath
                val t = DownloadTask(
                    id = "live", url = "https://example.com/x.bin", fileName = "x.bin", dir = dir,
                    totalBytes = 1000, status = Status.DOWNLOADING,
                    segments = mutableListOf(Segment(0, 0, 999, 100)),
                )
                synchronized(Repo.tasks) { Repo.tasks.clear(); Repo.tasks.add(t) }

                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                val ad = rv.adapter as DownloadsAdapter
                ad.refresh()
                layout(rv)

                val row = rv.getChildAt(0)
                val info = row.findViewById<TextView>(R.id.tvInfo)
                val status = row.findViewById<TextView>(R.id.tvStatus)
                assertTrue("初始进度没显示：${info.text}", info.text.contains("10%"))

                t.segments[0].downloaded = 500
                ad.refresh()
                layout(rv)
                assertTrue("进度没有刷新：${info.text}", info.text.contains("50%"))
                assertTrue("状态文字未显示百分比：${status.text}", status.text.contains("50%"))

                // 完成：状态必须从“下载中”变成“已完成”
                t.segments[0].downloaded = 1000
                t.status = Status.COMPLETED
                ad.refresh()
                layout(rv)
                assertEquals("已完成", status.text.toString())
            }
        }
    }

    private fun drawerOf(a: MainActivity): SwipeDrawerLayout = a.findViewById(R.id.drawer)

    private fun send(a: MainActivity, action: Int, x: Float, y: Float, time: Long) {
        val e = MotionEvent.obtain(0L, time, action, x, y, 0)
        a.dispatchTouchEvent(e)
        e.recycle()
    }

    /** 摆好尺寸并按 1233x2673 布局，返回抽屉面板 */
    private fun layoutDrawer(a: MainActivity): View {
        val decor = a.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1233, 2673)
        val drawer = drawerOf(a)
        drawer.requestLayout()
        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY),
        )
        drawer.layout(0, 0, 1233, 2673)
        return a.findViewById(R.id.drawerPanel)
    }

    /** 屏幕左侧右滑：抽屉跟着手指走，而不是一下子弹出 */
    @Test
    fun swipeRightDragsDrawerWithFinger() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val panel = layoutDrawer(a)
                val closedLeft = panel.left
                assertTrue("初始状态抽屉应在屏幕外：$closedLeft", closedLeft < 0)

                send(a, MotionEvent.ACTION_DOWN, 60f, 900f, 0)
                send(a, MotionEvent.ACTION_MOVE, 140f, 902f, 20)
                send(a, MotionEvent.ACTION_MOVE, 420f, 904f, 40)

                assertTrue("抽屉应跟随手指移动：$closedLeft -> ${panel.left}", panel.left > closedLeft)
                assertTrue("拖动中不应越过全开位置", panel.left <= 0)

                send(a, MotionEvent.ACTION_UP, 420f, 904f, 60)
                assertTrue(
                    "松手后应吸附为打开",
                    drawerOf(a).isDrawerOpen(androidx.core.view.GravityCompat.START),
                )
            }
        }
    }

    /** 只往右滑一点点就该完全展开，不再因为「没过一半」弹回去 */
    @Test
    fun smallRightSwipeFullyOpensDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val panel = layoutDrawer(a)
                val closedLeft = panel.left

                // 慢速小位移：不带甩动，只看方向
                send(a, MotionEvent.ACTION_DOWN, 60f, 900f, 0)
                send(a, MotionEvent.ACTION_MOVE, 90f, 902f, 200)
                send(a, MotionEvent.ACTION_MOVE, 110f, 903f, 400)
                send(a, MotionEvent.ACTION_UP, 110f, 903f, 600)

                assertTrue("抽屉应被拖动：$closedLeft -> ${panel.left}", panel.left > closedLeft)
                assertTrue(
                    "滑一点点松手也应完全展开",
                    drawerOf(a).isDrawerOpen(androidx.core.view.GravityCompat.START),
                )
            }
        }
    }

    /** 先右滑展开、再往左滑一段：应当关闭而不是继续打开 */
    @Test
    fun rightThenLeftClosesDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                layoutDrawer(a)

                send(a, MotionEvent.ACTION_DOWN, 60f, 900f, 0)
                send(a, MotionEvent.ACTION_MOVE, 200f, 902f, 100)
                send(a, MotionEvent.ACTION_MOVE, 400f, 903f, 200)
                // 手指往左滑回来一段（慢速，排除甩动影响）
                send(a, MotionEvent.ACTION_MOVE, 300f, 904f, 700)
                send(a, MotionEvent.ACTION_MOVE, 240f, 904f, 900)
                send(a, MotionEvent.ACTION_UP, 240f, 904f, 1200)

                assertTrue(
                    "右滑后又左滑回来应关闭",
                    !drawerOf(a).isDrawerOpen(androidx.core.view.GravityCompat.START),
                )
            }
        }
    }

    /** 打开状态下左滑一点点：同样直接关闭 */
    @Test
    fun smallLeftSwipeClosesOpenDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                layoutDrawer(a)
                drawerOf(a).openDrawer(GravityCompat.START)
                ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS)
                // Robolectric 不跑补间帧，按打开态重新布局一次，抽屉才真的停在 left = 0
                val panel = layoutDrawer(a)
                assertEquals("抽屉应停在完全打开位置", 0, panel.left)

                send(a, MotionEvent.ACTION_DOWN, 700f, 900f, 0)
                send(a, MotionEvent.ACTION_MOVE, 660f, 902f, 200)
                send(a, MotionEvent.ACTION_MOVE, 630f, 903f, 400)
                send(a, MotionEvent.ACTION_UP, 630f, 903f, 600)

                assertTrue(
                    "左滑一点点也应关闭",
                    !drawerOf(a).isDrawerOpen(GravityCompat.START),
                )
            }
        }
    }

    /** 顶部区域不能有不透明背景盖住面板自己的圆角（否则右上角是直角） */
    @Test
    fun drawerTopAreaDoesNotCoverCorner() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                layoutDrawer(a)
                val scroll = a.findViewById<android.widget.ScrollView>(R.id.drawerScroll)
                val column = scroll.getChildAt(0) as android.view.ViewGroup
                val header = column.getChildAt(0)
                assertTrue(
                    "侧边栏顶部不应有不透明背景，否则会盖掉右上角圆角",
                    header.background == null,
                )
                val panel = a.findViewById<View>(R.id.drawerPanel)
                assertTrue("侧边栏面板应使用自定义裁剪容器", panel is DrawerPanelLayout)
                val corner = a.resources.getDimension(R.dimen.drawer_corner)
                assertTrue(
                    "侧边栏圆角应收紧到 20dp：${corner / a.resources.displayMetrics.density}dp",
                    corner / a.resources.displayMetrics.density <= 20.1f,
                )
            }
        }
    }

    /** 右侧空白处右滑也能拖动抽屉：不再限制在屏幕左边缘 */
    @Test
    fun swipeRightOnBlankAreaAlsoDragsDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val panel = layoutDrawer(a)
                val closedLeft = panel.left

                send(a, MotionEvent.ACTION_DOWN, 800f, 1200f, 0)
                send(a, MotionEvent.ACTION_MOVE, 880f, 1202f, 20)
                send(a, MotionEvent.ACTION_MOVE, 1060f, 1204f, 40)
                assertTrue("空白处右滑也应拖动抽屉", panel.left > closedLeft)

                send(a, MotionEvent.ACTION_UP, 1060f, 1204f, 60)
            }
        }
    }

    /** 按在列表卡片上右滑：拖动抽屉，不应触发表情 / 选择 */
    @Test
    fun swipeRightOnListItemDragsDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = DownloadTask(
                    id = "swipe-item", url = "https://example.com/a.bin", fileName = "a.bin",
                    dir = a.filesDir.absolutePath, totalBytes = 100, status = Status.DOWNLOADING,
                    segments = mutableListOf(Segment(0, 0, 99, 10)),
                )
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }
                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                (rv.adapter as DownloadsAdapter).refresh()
                layout(rv)
                val child = rv.getChildAt(0) ?: throw AssertionError("列表项没有渲染")
                val childLoc = IntArray(2)
                child.getLocationOnScreen(childLoc)
                val rvLoc = IntArray(2)
                rv.getLocationOnScreen(rvLoc)
                val cy = childLoc[1] + child.height / 2f
                val cx = childLoc[0] + child.width * 0.75f
                assertTrue(
                    "测试坐标没落在卡片上",
                    rv.findChildViewUnder(cx - rvLoc[0], cy - rvLoc[1]) != null
                )

                val panel = layoutDrawer(a)
                val closedLeft = panel.left
                send(a, MotionEvent.ACTION_DOWN, cx, cy, 0)
                send(a, MotionEvent.ACTION_MOVE, cx + 90f, cy + 2f, 20)
                send(a, MotionEvent.ACTION_MOVE, cx + 280f, cy + 3f, 40)
                assertTrue("卡片上右滑也应拖动抽屉", panel.left > closedLeft)
                assertTrue("右滑不应进入多选", (rv.adapter as DownloadsAdapter).selectedIds.isEmpty())
                send(a, MotionEvent.ACTION_UP, cx + 280f, cy + 3f, 60)
            }
        }
    }

    /** 左滑选中，再左滑另一项：区间内的项全部选中 */
    @Test
    fun swipeLeftSelectsThenRangeSelects() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val tasks = (0 until 5).map { i ->
                    DownloadTask(
                        id = "s$i", url = "https://example.com/$i.bin", fileName = "f$i.bin",
                        dir = a.filesDir.absolutePath, totalBytes = 100, status = Status.COMPLETED,
                        addedAt = 10_000L - i,
                    )
                }
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.addAll(tasks)
                }
                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                val ad = rv.adapter as DownloadsAdapter
                ad.refresh()
                layout(rv)
                val list = ad.filteredTasks()
                assertEquals(5, list.size)

                val m = MainActivity::class.java.getDeclaredMethod(
                    "performSwipe", DownloadTask::class.java, Int::class.javaPrimitiveType
                )
                m.isAccessible = true

                // 第一次左滑：只选中这一项
                m.invoke(a, list[0], ItemTouchHelper.LEFT)
                assertTrue("左滑后应进入多选", ad.selectionMode)
                assertEquals("第一次左滑只选中一项", 1, ad.selectedIds.size)
                assertEquals(list[0].id, ad.anchorId)

                // 第二次左滑第 4 项：0..3 全选
                m.invoke(a, list[3], ItemTouchHelper.LEFT)
                assertEquals("应区间选中 4 项", 4, ad.selectedIds.size)
                assertTrue(
                    "区间内的项没有全部选中",
                    ad.selectedIds.containsAll(list.take(4).map { it.id })
                )
                assertTrue("区间外的项不应被选中", !ad.selectedIds.contains(list[4].id))
                ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
            }
        }
    }

    /** 列表项只保留左滑（选中）；右滑交给抽屉手势 */
    @Test
    fun rowsOnlySupportLeftSwipe() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val m = MainActivity::class.java
                    .getDeclaredMethod("swipeDirsFor", DownloadTask::class.java)
                m.isAccessible = true
                val done = DownloadTask(
                    id = "done", url = "u", fileName = "f.bin",
                    dir = a.filesDir.absolutePath, status = Status.COMPLETED,
                )
                val running = DownloadTask(
                    id = "run", url = "u2", fileName = "f2.bin",
                    dir = a.filesDir.absolutePath, status = Status.DOWNLOADING,
                )
                assertEquals(ItemTouchHelper.LEFT, m.invoke(a, done) as Int)
                assertEquals(ItemTouchHelper.LEFT, m.invoke(a, running) as Int)
                val none: DownloadTask? = null
                assertEquals(0, m.invoke(a, none) as Int)
            }
        }
    }
}
