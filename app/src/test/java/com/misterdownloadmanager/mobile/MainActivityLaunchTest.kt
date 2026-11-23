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
                val tasks = a.findViewById<TextView>(R.id.tvStatTasks)
                assertTrue("统计未写入", tasks.text.isNotBlank())
            }
        }
    }

    /** 侧边栏顶部概览卡：图标 / 名称 / 版本胶囊 / 上游基线 / 三列统计都在，且整卡可点 */
    @Test
    fun drawerHeaderShowsBrandAndStats() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val panel = a.findViewById<View>(R.id.drawerPanel)
                panel.measure(
                    View.MeasureSpec.makeMeasureSpec(924, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2280, View.MeasureSpec.EXACTLY),
                )
                panel.layout(0, 0, 924, 2280)

                val statTasks = a.findViewById<TextView>(R.id.tvStatTasks)
                val statDone = a.findViewById<TextView>(R.id.tvStatDone)
                val statSize = a.findViewById<TextView>(R.id.tvStatSize)
                val version = a.findViewById<TextView>(R.id.tvNavVersion)
                val upstream = a.findViewById<TextView>(R.id.tvNavUpstream)
                val icon = a.findViewById<View>(R.id.ivNavIcon)
                val card = a.findViewById<View>(R.id.drawerHeadCard)

                assertEquals(View.VISIBLE, statTasks.visibility)
                assertEquals(View.VISIBLE, statDone.visibility)
                assertEquals(View.VISIBLE, statSize.visibility)
                assertEquals(View.VISIBLE, icon.visibility)

                assertEquals("v${BuildConfig.VERSION_NAME}", version.text.toString())
                assertTrue("上游基线未写入", upstream.text.toString().contains(Changelog.upstream.version))
                assertTrue("上游日期未写入", upstream.text.toString().contains(Changelog.upstream.released))
                assertTrue("整卡应可点进关于页", card.isClickable)

                // 头部必须真的占据屏幕顶部的可见区域
                assertTrue("统计卡片高度异常：${statTasks.height}", statTasks.height > 0)
                assertTrue("统计卡片未显示在顶部：${statTasks.top}", statTasks.top < 400)
                assertTrue("概览卡顶部留白过多：${card.top}", card.top < 160)
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
                // 7 种文件类型 + 顶部的「全部类型」
                assertEquals(8, a.findViewById<android.widget.LinearLayout>(R.id.bodyTypes).childCount)
                // 批量操作已移到顶部工具栏，「崩溃日志」收进关于页，这里只剩「关于」
                assertEquals(1, a.findViewById<android.widget.LinearLayout>(R.id.bodyMore).childCount)
            }
        }
    }

    @Test
    fun opensDrawer() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val drawer = a.findViewById<SwipeDrawerLayout>(R.id.drawer)
                drawer.openDrawer(androidx.core.view.GravityCompat.START)
                assertTrue("openDrawer 后应处于打开状态", drawer.isDrawerOpen(androidx.core.view.GravityCompat.START))
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
