package com.misterdownloadmanager.mobile

import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainActivityLaunchTest {

    @Test
    fun launchesWithoutCrash() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                check(a.findViewById<View>(R.id.drawer) != null) { "drawer 未找到" }
                val version = a.findViewById<TextView>(R.id.tvNavVersion)
                assertTrue("版本号未写入", version.text.isNotBlank())
                val tasks = a.findViewById<TextView>(R.id.tvStatTasks)
                assertTrue("统计未写入", tasks.text.isNotBlank())
            }
        }
    }

    /** 侧边栏顶部（图标 / 名称 / 版本 / 统计）必须真实存在且可见 */
    @Test
    fun drawerHeaderIsComplete() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val panel = a.findViewById<View>(R.id.drawerPanel)
                panel.measure(
                    View.MeasureSpec.makeMeasureSpec(924, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2280, View.MeasureSpec.EXACTLY),
                )
                panel.layout(0, 0, 924, 2280)

                val version = a.findViewById<TextView>(R.id.tvNavVersion)
                val statTasks = a.findViewById<TextView>(R.id.tvStatTasks)
                val statDone = a.findViewById<TextView>(R.id.tvStatDone)
                val statSize = a.findViewById<TextView>(R.id.tvStatSize)
                val title = a.findViewById<TextView>(R.id.tvNavTitle)
                val icon = a.findViewById<View>(R.id.ivNavIcon)

                assertEquals(View.VISIBLE, version.visibility)
                assertEquals(View.VISIBLE, statTasks.visibility)
                assertEquals(View.VISIBLE, statDone.visibility)
                assertEquals(View.VISIBLE, statSize.visibility)
                assertEquals(View.VISIBLE, title.visibility)
                assertEquals(View.VISIBLE, icon.visibility)

                // 头部必须真的占据屏幕顶部的可见区域
                assertTrue("头部高度异常：${title.height}", title.height > 0)
                assertTrue("统计卡片高度异常：${statTasks.height}", statTasks.height > 0)
                assertTrue("统计卡片未显示在顶部：${statTasks.top}", statTasks.top < 400)
                assertTrue("应用名称未显示", title.text.isNotBlank())
                assertTrue("版本号未显示", version.text.isNotBlank())
            }
        }
    }

    /** 分类分组可折叠 / 可展开 */
    @Test
    fun drawerSectionCollapsesAndExpands() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val header = a.findViewById<View>(R.id.hdrTasks)
                val body = a.findViewById<View>(R.id.bodyTasks)
                assertEquals(View.VISIBLE, body.visibility)

                header.performClick()
                // 动画结束前高度在变化，这里直接看最终状态
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                assertEquals(View.GONE, body.visibility)

                header.performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                assertEquals(View.VISIBLE, body.visibility)
            }
        }
    }

    /** 原设置页内容已经搬进侧边栏 */
    @Test
    fun settingsLiveInDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                assertTrue(a.findViewById<View>(R.id.swWifi) != null)
                assertTrue(a.findViewById<View>(R.id.swRetry) != null)
                assertTrue(a.findViewById<View>(R.id.rowSeg) != null)
                assertTrue(a.findViewById<View>(R.id.rowPath) != null)
                val seg = a.findViewById<TextView>(R.id.tvSeg)
                assertTrue("线程数未显示：${seg.text}", seg.text.isNotBlank())
                val path = a.findViewById<TextView>(R.id.tvSavePath)
                assertTrue("保存路径未显示：${path.text}", path.text.isNotBlank())
            }
        }
    }

    /** 侧边栏所有分类行都已生成 */
    @Test
    fun drawerHasAllCategoryRows() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                assertEquals(5, a.findViewById<android.widget.LinearLayout>(R.id.bodyTasks).childCount)
                assertEquals(7, a.findViewById<android.widget.LinearLayout>(R.id.bodyTypes).childCount)
                assertTrue(a.findViewById<android.widget.LinearLayout>(R.id.bodyMore).childCount >= 5)
            }
        }
    }

    @Test
    fun opensDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val drawer = a.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer)
                drawer.openDrawer(androidx.core.view.GravityCompat.START)
            }
        }
    }

    @Test
    fun bindsDownloadItem() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val dir = a.filesDir.absolutePath
                synchronized(Repo.tasks) {
                    Repo.tasks.add(
                        0,
                        DownloadTask(
                            id = "t1",
                            url = "https://example.com/demo.mp4",
                            fileName = "demo.mp4",
                            dir = dir,
                            totalBytes = 1024 * 1024,
                            status = Status.DOWNLOADING,
                            segments = mutableListOf(Segment(0, 0, 1024 * 1024 - 1, 1024 * 512)),
                        ),
                    )
                    Repo.tasks.add(
                        1,
                        DownloadTask(
                            id = "t2",
                            url = "https://example.com/fail.apk",
                            fileName = "fail.apk",
                            dir = dir,
                            status = Status.FAILED,
                            error = "HTTP 404",
                        ),
                    )
                }
                val recycler = a.findViewById<RecyclerView>(R.id.recycler)
                // 让适配器重新读取 Repo 数据（真实场景由 500ms 定时器触发）
                (recycler.adapter as DownloadsAdapter).refresh()
                repeat(2) {
                    recycler.measure(
                        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                    )
                    recycler.layout(0, 0, 1080, 1920)
                }
                assertTrue("列表未绑定任何条目", recycler.childCount > 0)
            }
        }
    }
}
