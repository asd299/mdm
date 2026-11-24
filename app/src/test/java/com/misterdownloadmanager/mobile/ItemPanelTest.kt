package com.misterdownloadmanager.mobile

import android.app.Dialog
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
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
import org.robolectric.shadows.ShadowLooper

/**
 * 列表项操作面板：
 * - 插在被点项的正下方（不是屏幕中间或顶部）；
 * - 一行三个按钮，已完成项的第一个按钮是「打开文件」；
 * - 点「属性」以版本说明同款的贴底面板展开；
 * - 打开搜索框时把展开的面板收起来。
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

    @Test
    fun panelOpensRightBelowTappedItem() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                val adapter = rv.adapter as DownloadsAdapter
                adapter.refresh()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                val card = rv.findViewHolderForAdapterPosition(1) ?: return@onActivity
                card.itemView.performClick()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                val task = adapter.taskAt(1)!!
                assertEquals("面板应对应被点的项", task.id, adapter.expandedId)
                // 面板行紧跟在被点项之后
                val itemPos = adapter.positionOf(task.id)
                assertEquals(1, itemPos)
                val panelVh = rv.findViewHolderForAdapterPosition(itemPos + 1)
                assertNotNull("面板应作为列表的一行插在项下面", panelVh)
                assertNotNull(
                    "这一行应是操作面板",
                    panelVh!!.itemView.findViewById<View>(R.id.itemActionCard),
                )
                assertTrue(
                    "面板应在被点项下方：card.bottom=${card.itemView.bottom}, panel.top=${panelVh.itemView.top}",
                    panelVh.itemView.top >= card.itemView.bottom,
                )

                // 再点同一项收起
                rv.findViewHolderForAdapterPosition(1)!!.itemView.performClick()
                ShadowLooper.idleMainLooper()
                assertNull("再点同一项应收起面板", adapter.expandedId)
                assertEquals("收起后列表只剩任务行", 3, adapter.itemCount)
            }
        }
    }

    @Test
    fun panelShowsThreeButtonsPerRowStartingWithOpenFile() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                val adapter = rv.adapter as DownloadsAdapter
                adapter.refresh()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                rv.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                val panel = rv.findViewHolderForAdapterPosition(1)!!.itemView
                val grid = panel.findViewById<LinearLayout>(R.id.itemActionGrid)
                val firstRow = grid.getChildAt(0) as LinearLayout
                assertEquals("第一行应是一行三个按钮", 3, firstRow.childCount)
                val labels = (0 until firstRow.childCount).map {
                    firstRow.getChildAt(it).findViewById<TextView>(R.id.chipText).text.toString()
                }
                assertEquals("第一个按钮应为打开文件", "打开文件", labels[0])
                assertEquals("第二个按钮应为属性", "属性", labels[1])
                assertTrue("操作项顺序异常：$labels", labels.size == 3)
            }
        }
    }

    /** 点「属性」：以版本说明同款的贴底面板展开（默认半屏、底边贴屏底） */
    @Test
    fun tappingPropertiesOpensBottomSheetFlushToBottom() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                val adapter = rv.adapter as DownloadsAdapter
                adapter.refresh()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                rv.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                val panel = rv.findViewHolderForAdapterPosition(1)!!.itemView
                val grid = panel.findViewById<LinearLayout>(R.id.itemActionGrid)
                val firstRow = grid.getChildAt(0) as LinearLayout
                val propChip = firstRow.getChildAt(1)
                assertEquals("属性", propChip.findViewById<TextView>(R.id.chipText).text.toString())
                propChip.performClick()
                ShadowLooper.idleMainLooper()

                assertNull("点操作项后面板应收起", adapter.expandedId)

                val dialog: Dialog = ShadowDialog.getLatestDialog()
                assertNotNull("应弹出属性面板", dialog)
                val host = dialog.findViewById<View>(R.id.panelHost)
                val sheet = dialog.findViewById<View>(R.id.propertiesSheet)
                assertNotNull("属性面板载体应存在", host)
                assertNotNull("属性面板应存在", sheet)

                val screenH = a.resources.displayMetrics.heightPixels
                host!!.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(screenH, View.MeasureSpec.EXACTLY),
                )
                host.layout(0, 0, 1080, screenH)

                assertEquals("默认应停半屏", (screenH * 0.62f).toInt(), sheet!!.height)
                assertEquals("面板底边要贴住屏幕底", screenH, sheet.bottom)

                // 属性内容还在
                val list = dialog.findViewById<LinearLayout>(R.id.propList)!!
                assertTrue("属性行不应为空", list.childCount >= 10)
                dialog.dismiss()
            }
        }
    }

    /** 打开搜索框（菜单项展开）时，展开的操作面板要收起来 */
    @Test
    fun expandingSearchCollapsesPanel() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                seed(a)
                val rv = a.findViewById<RecyclerView>(R.id.recycler)
                val adapter = rv.adapter as DownloadsAdapter
                adapter.refresh()
                ShadowLooper.idleMainLooper()
                layoutList(rv)

                rv.findViewHolderForAdapterPosition(0)!!.itemView.performClick()
                ShadowLooper.idleMainLooper()
                assertEquals("面板应已展开", adapter.taskAt(0)!!.id, adapter.expandedId)

                val item = fieldOf(a, "searchItem") as MenuItem
                item.expandActionView()
                ShadowLooper.idleMainLooper()

                assertNull("打开搜索框应收起操作面板", adapter.expandedId)
                assertEquals("收起后列表只剩任务行", 3, adapter.itemCount)
            }
        }
    }
}
