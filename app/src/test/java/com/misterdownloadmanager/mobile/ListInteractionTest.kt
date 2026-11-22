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

    private fun claimed(a: MainActivity): Boolean {
        val f = MainActivity::class.java.getDeclaredField("drawerSwipeClaimed")
        f.isAccessible = true
        return f.getBoolean(a)
    }

    /** 屏幕左侧 1/3 区域内向右滑，应识别为“打开侧边栏”手势 */
    @Test
    fun swipeRightInLeftThirdIsHandledAsDrawerGesture() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val decor = a.window.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, 1233, 2673)

                fun send(action: Int, x: Float, y: Float, time: Long) {
                    val e = MotionEvent.obtain(0L, time, action, x, y, 0)
                    a.dispatchTouchEvent(e)
                    e.recycle()
                }
                send(MotionEvent.ACTION_DOWN, 60f, 900f, 0)
                send(MotionEvent.ACTION_MOVE, 300f, 902f, 20)
                send(MotionEvent.ACTION_MOVE, 520f, 905f, 40)
                assertTrue("左 1/3 右滑没有被识别为抽屉手势", claimed(a))
                send(MotionEvent.ACTION_UP, 520f, 905f, 60)
            }
        }
    }

    /** 屏幕右侧、没有任何列表项的空白处右滑：也应打开侧边栏 */
    @Test
    fun swipeRightOnBlankAreaIsHandledAsDrawerGesture() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val decor = a.window.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY),
                )
                decor.layout(0, 0, 1233, 2673)

                fun send(action: Int, x: Float, y: Float, time: Long) {
                    val e = MotionEvent.obtain(0L, time, action, x, y, 0)
                    a.dispatchTouchEvent(e)
                    e.recycle()
                }
                send(MotionEvent.ACTION_DOWN, 800f, 900f, 0)
                send(MotionEvent.ACTION_MOVE, 1000f, 902f, 20)
                assertTrue("空白处右滑没有被识别为抽屉手势", claimed(a))
                // claimed 为 true 说明已经走完「拦截手势 → openDrawer」分支
                // （Robolectric 里 DrawerLayout 的开合动画不会真正播放，故不断言可见性）
                send(MotionEvent.ACTION_UP, 1000f, 902f, 40)
            }
        }
    }

    /** 按在列表卡片上右滑：和空白区域一样，直接打开侧边栏，不应触发列表动作 */
    @Test
    fun swipeRightOnListItemOpensDrawer() {
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

                fun send(action: Int, x: Float, y: Float, time: Long) {
                    val e = MotionEvent.obtain(0L, time, action, x, y, 0)
                    a.dispatchTouchEvent(e)
                    e.recycle()
                }
                send(MotionEvent.ACTION_DOWN, cx, cy, 0)
                send(MotionEvent.ACTION_MOVE, cx + 200f, cy + 2f, 20)
                assertTrue("卡片上右滑也应直接打开侧边栏", claimed(a))
                send(MotionEvent.ACTION_UP, cx + 200f, cy + 2f, 40)
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
