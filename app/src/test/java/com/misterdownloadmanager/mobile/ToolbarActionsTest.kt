package com.misterdownloadmanager.mobile

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** 头部结构：标题与菜单同排、四个列表操作按钮、内联排序面板 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolbarActionsTest {

    private fun adapterOf(a: MainActivity): DownloadsAdapter =
        (a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter)

    /** 用私有字段判断展开状态，避免依赖收起动画结束 */
    private fun panelOpen(a: MainActivity): Boolean {
        val f = MainActivity::class.java.getDeclaredField("sortPanelOpen")
        f.isAccessible = true
        return f.getBoolean(a)
    }

    @Test
    fun titleSitsRightOfDrawerButton() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val title = a.findViewById<TextView>(R.id.tvLargeTitle)
                assertTrue("标题应在工具栏内（菜单按钮右侧）", title.parent is Toolbar)
                assertEquals(a.getString(R.string.nav_all), title.text.toString())
            }
        }
    }

    @Test
    fun actionRowHasFourListButtons() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val row = a.findViewById<View>(R.id.actionRow)
                assertEquals(View.VISIBLE, row.visibility)
                listOf(R.id.btnPauseAll, R.id.btnResumeAll, R.id.btnClearDone, R.id.btnSort)
                    .forEach { id ->
                        val btn = a.findViewById<View>(id)
                        assertNotNull("缺少按钮 $id", btn)
                        assertEquals("按钮 $id 不可见", View.VISIBLE, btn.visibility)
                    }
            }
        }
    }

    @Test
    fun sortPanelExpandsInlineWithoutDialog() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val panel = a.findViewById<View>(R.id.sortPanel)
                assertEquals(View.GONE, panel.visibility)

                a.findViewById<View>(R.id.btnSort).performClick()

                assertEquals("排序面板应内联展开", View.VISIBLE, panel.visibility)
                assertTrue("展开状态未记录", panelOpen(a))
                assertNull("不应再弹出排序对话框", ShadowDialog.getLatestDialog())

                a.findViewById<View>(R.id.btnSort).performClick()
                assertTrue("再次点击应收起", !panelOpen(a))
            }
        }
    }

    @Test
    fun sortDirectionTogglesBetweenDescAndAsc() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<View>(R.id.btnSort).performClick()
                val chip = a.findViewById<TextView>(R.id.tvSortOrder)
                assertTrue(
                    "默认应为降序，实际：${chip.text}",
                    chip.text.contains(a.getString(R.string.sort_desc))
                )

                chip.performClick()
                assertTrue(
                    "点击后应为增序，实际：${chip.text}",
                    chip.text.contains(a.getString(R.string.sort_asc))
                )

                chip.performClick()
                assertTrue("再次点击应回到降序", chip.text.contains(a.getString(R.string.sort_desc)))
            }
        }
    }

    @Test
    fun selectingSortModeUpdatesAdapter() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<View>(R.id.btnSort).performClick()
                val options = a.findViewById<LinearLayout>(R.id.sortOptions)
                val second = (options.getChildAt(0) as LinearLayout).getChildAt(1) as TextView
                assertEquals(a.getString(R.string.sort_name), second.text.toString())

                second.performClick()

                assertEquals(SortMode.NAME, adapterOf(a).sortMode)
                assertTrue("被选中的选项应高亮", second.isSelected)
            }
        }
    }

    @Test
    fun sortDirectionActuallyReordersList() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val dir = a.filesDir.absolutePath
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(
                        DownloadTask(
                            id = "old", url = "u1", fileName = "old.bin",
                            dir = dir, status = Status.COMPLETED, addedAt = 1_000L,
                        )
                    )
                    Repo.tasks.add(
                        DownloadTask(
                            id = "new", url = "u2", fileName = "new.bin",
                            dir = dir, status = Status.COMPLETED, addedAt = 3_000L,
                        )
                    )
                }
                val adapter = adapterOf(a)
                adapter.sortMode = SortMode.TIME
                adapter.sortAscending = false
                adapter.refresh()
                assertEquals("降序应把最新任务排在前面", "new", adapter.taskAt(0)?.id)

                a.findViewById<View>(R.id.btnSort).performClick()
                a.findViewById<TextView>(R.id.tvSortOrder).performClick()
                assertEquals("增序应把最早任务排在前面", "old", adapter.taskAt(0)?.id)
            }
        }
    }

    @Test
    fun drawerMoreSectionOnlyKeepsUtilityRows() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val body = a.findViewById<LinearLayout>(R.id.bodyMore)
                val labels = mutableListOf<String>()
                fun walk(v: View) {
                    if (v is TextView && v.id == R.id.navTitle) labels.add(v.text.toString())
                    if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
                }
                walk(body)

                assertEquals("侧边栏「更多」只保留「关于」一项（崩溃日志已收进关于页）", 1, labels.size)
                assertEquals(a.getString(R.string.nav_about), labels.first())
                listOf(
                    a.getString(R.string.pause_all),
                    a.getString(R.string.resume_all),
                    a.getString(R.string.clear_finished),
                ).forEach { assertTrue("「$it」不应再出现在侧边栏", it !in labels) }
            }
        }
    }
}
