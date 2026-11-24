package com.misterdownloadmanager.mobile

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 侧边栏单选分类（任务 / 按类型二选一）、过滤逻辑，以及下拉刷新的数据合并 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class FiltersAndRefreshTest {

    private fun task(
        id: String,
        name: String,
        status: Status,
        dir: String,
        addedAt: Long = 1_000L,
    ) = DownloadTask(
        id = id,
        url = "https://example.com/$name",
        fileName = name,
        dir = dir,
        totalBytes = 1_000,
        status = status,
        segments = mutableListOf(Segment(0, 0, 999, 1_000)),
        addedAt = addedAt,
    )

    private fun adapterOf(a: MainActivity): DownloadsAdapter =
        (a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter)

    private fun titleOf(a: MainActivity): String =
        a.findViewById<TextView>(R.id.tvLargeTitle).text.toString()

    /** 纯逻辑：过滤层仍支持两维度 AND，任一维度为「全部」时不参与过滤（界面只允许单选） */
    @Test
    fun twoDimensionsCombineWithAnd() {
        val t = task("1", "movie.mp4", Status.COMPLETED, "/tmp")

        assertTrue(FileTypes.matches(Cat.ALL, Cat.ALL, t))
        assertTrue(FileTypes.matches(Cat.DONE, Cat.VIDEO, t))
        assertTrue(FileTypes.matches(Cat.DONE, Cat.ALL, t))
        assertTrue(FileTypes.matches(Cat.ALL, Cat.VIDEO, t))

        assertFalse(FileTypes.matches(Cat.RUNNING, Cat.VIDEO, t))
        assertFalse(FileTypes.matches(Cat.DONE, Cat.AUDIO, t))
        assertFalse(FileTypes.matches(Cat.ALL, Cat.DOC, t))

        // 状态维度不会误把类型分类当成状态（未知维度视为不筛选）
        assertTrue(FileTypes.matchesStatus(Cat.ALL, t))
        assertTrue(FileTypes.matchesStatus(Cat.VIDEO, t))
    }

    /** 侧边栏是单选：任意时刻只有一行高亮，选另一组会把前一组收回「全部」 */
    @Test
    fun sidebarIsSingleChoiceAcrossGroups() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val dir = a.filesDir.absolutePath
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(task("v1", "a.mp4", Status.COMPLETED, dir, 5_000))
                    Repo.tasks.add(task("v2", "b.mp4", Status.DOWNLOADING, dir, 4_000))
                    Repo.tasks.add(task("m1", "c.mp3", Status.COMPLETED, dir, 3_000))
                }
                val ad = adapterOf(a)
                ad.refresh()
                a.findViewById<View>(R.id.recycler).requestLayout()

                val statusRows = a.findViewById<LinearLayout>(R.id.bodyTasks)
                val typeRows = a.findViewById<LinearLayout>(R.id.bodyTypes)
                fun active(c: LinearLayout) =
                    (0 until c.childCount).filter { c.getChildAt(it).isActivated }

                // 默认：只有「任务 → 全部下载」是选中态，类型组的「全部」不重复占一行
                assertEquals(Cat.ALL, ad.statusCat)
                assertEquals(Cat.ALL, ad.typeCat)
                assertEquals(3, ad.itemCount)
                assertEquals(listOf(0), active(statusRows))
                assertTrue("默认不应有第二行高亮：${active(typeRows)}", active(typeRows).isEmpty())

                // 选「视频」：任务维度被收回全部，只剩类型组一行高亮
                typeRows.getChildAt(1).performClick()
                assertEquals(Cat.VIDEO, ad.typeCat)
                assertEquals(Cat.ALL, ad.statusCat)
                assertEquals(2, ad.itemCount)
                assertEquals(a.getString(R.string.nav_video), titleOf(a))
                assertEquals(listOf(1), active(typeRows))
                assertTrue("不应残留任务行高亮：${active(statusRows)}", active(statusRows).isEmpty())

                // 再选「已完成」：类型维度被收回全部，只剩任务组一行高亮
                statusRows.getChildAt(2).performClick()
                assertEquals(Cat.DONE, ad.statusCat)
                assertEquals(Cat.ALL, ad.typeCat)
                assertEquals("应只剩已完成的两个任务", 2, ad.itemCount)
                assertEquals(a.getString(R.string.nav_done), titleOf(a))
                assertEquals(listOf(2), active(statusRows))
                assertTrue("不应残留类型行高亮：${active(typeRows)}", active(typeRows).isEmpty())

                // 同组切换：旧的选中行必须熄灭
                statusRows.getChildAt(3).performClick()
                assertEquals(listOf(3), active(statusRows))

                // 类型组的「全部」等价于回到「全部下载」
                typeRows.getChildAt(0).performClick()
                assertEquals(Cat.ALL, ad.typeCat)
                assertEquals(Cat.ALL, ad.statusCat)
                assertEquals(3, ad.itemCount)
                assertEquals(listOf(0), active(statusRows))
                assertTrue(active(typeRows).isEmpty())
            }
        }
    }

    /** 侧边栏计数按各自维度统计：视频 2 个、已完成 2 个，互不影响 */
    @Test
    fun navCountsFollowTheirOwnDimension() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val dir = a.filesDir.absolutePath
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(task("v1", "a.mp4", Status.COMPLETED, dir, 5_000))
                    Repo.tasks.add(task("v2", "b.mp4", Status.DOWNLOADING, dir, 4_000))
                    Repo.tasks.add(task("m1", "c.mp3", Status.COMPLETED, dir, 3_000))
                }
                a.refreshNow()

                fun countOf(container: LinearLayout, index: Int): String =
                    container.getChildAt(index).findViewById<TextView>(R.id.navCount).text.toString()

                val typeRows = a.findViewById<LinearLayout>(R.id.bodyTypes)
                val statusRows = a.findViewById<LinearLayout>(R.id.bodyTasks)
                assertEquals("2", countOf(typeRows, 1))   // 视频
                assertEquals("1", countOf(typeRows, 2))   // 音乐
                assertEquals("2", countOf(statusRows, 2)) // 已完成
                assertEquals("1", countOf(statusRows, 1)) // 正在下载
            }
        }
    }

    /** 下拉刷新：磁盘上新增的任务会合并进列表 */
    @Test
    fun refreshMergesTasksFromDisk() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                synchronized(Repo.tasks) { Repo.tasks.clear() }
                val ad = adapterOf(a)
                ad.refresh()
                assertEquals(0, ad.itemCount)

                val t = task("disk1", "new-file.mp4", Status.COMPLETED, a.filesDir.absolutePath, 2_000)
                File(a.filesDir, "tasks.json").writeText(JSONArray().put(t.toJson()).toString())

                val added = a.refreshNow()

                assertEquals(1, added)
                assertEquals(1, ad.itemCount)
                assertTrue(Repo.tasks.any { it.id == "disk1" })
                assertEquals("new-file.mp4", ad.filteredTasks().first().fileName)
            }
        }
    }

    /** 刷新不能覆盖内存中正在下载的任务（进度比磁盘新） */
    @Test
    fun refreshKeepsLiveTaskState() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val dir = a.filesDir.absolutePath
                val live = task("live", "running.mp4", Status.DOWNLOADING, dir)
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(live)
                }
                // 磁盘上是同一任务的旧状态
                val stale = task("live", "running.mp4", Status.PAUSED, dir)
                File(a.filesDir, "tasks.json").writeText(JSONArray().put(stale.toJson()).toString())

                val added = a.refreshNow()

                assertEquals(0, added)
                assertEquals(1, Repo.tasks.size)
                assertEquals(Status.DOWNLOADING, Repo.tasks.first().status)
            }
        }
    }

    /** 列表外层必须是 SwipeRefreshLayout，下拉手势才能触发刷新 */
    @Test
    fun recyclerIsWrappedBySwipeRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val rv = a.findViewById<View>(R.id.recycler)
                assertTrue(
                    "列表应包裹在 SwipeRefreshLayout 中，实际父级：${rv.parent}",
                    rv.parent is androidx.swiperefreshlayout.widget.SwipeRefreshLayout
                )
            }
        }
    }
}
