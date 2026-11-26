package com.misterdownloadmanager.mobile

import android.graphics.Bitmap
import android.view.View
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 渲染级回归：侧边栏「高亮药丸」必须真的画出来，且只画在被选中的行上。
 * 纯 isActivated 断言抓不住「状态对了但绘制不对 / 多画」的问题，这里直接对像素验证。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NavHighlightRenderTest {

    private class RowPix(val name: String, val active: Boolean, val blue: Boolean)

    private fun task(id: String, name: String, status: Status) = DownloadTask(
        id = id,
        url = "https://example.com/$name",
        fileName = name,
        dir = ApplicationProvider.getApplicationContext<android.content.Context>().filesDir.absolutePath,
        totalBytes = 1_000,
        status = status,
        segments = mutableListOf(Segment(0, 0, 999, 1_000)),
        addedAt = 1_000L,
    )

    /** 顶点坐标换算到 panel 位图内的 y */
    private fun topIn(root: View, v: View): Int {
        var y = 0
        var cur: View = v
        while (cur !== root && cur.parent is View) {
            y += cur.top
            val p = cur.parent
            if (p is android.widget.ScrollView) y -= p.scrollY
            cur = p as View
        }
        return y
    }

    private fun sampleRows(panel: View, container: LinearLayout): List<RowPix> {
        val w = panel.width.coerceAtLeast(1)
        val h = panel.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        panel.draw(android.graphics.Canvas(bmp))

        return (0 until container.childCount).map { i ->
            val row = container.getChildAt(i)
            val y = topIn(panel, row) + row.height / 2
            val x = (w * 0.9f).toInt().coerceIn(0, w - 1)   // 行右端，避开图标与文字
            val c = bmp.getPixel(x, y.coerceIn(0, h - 1))
            val r = (c shr 16) and 0xFF
            val b = c and 0xFF
            RowPix(
                name = row.findViewById<android.widget.TextView>(R.id.navTitle).text.toString(),
                active = row.isActivated,
                // 浅蓝药丸：蓝通道明显高于红通道
                blue = b > r + 10,
            )
        }
    }

    private fun assertPillMatches(rows: List<RowPix>, group: String) {
        val mism = rows.filter { it.active != it.blue }
        assertTrue(
            "$group 组高亮与绘制不一致：${mism.joinToString { "${it.name}(activated=${it.active}, 画了药丸=${it.blue})" }}；全部：${rows.joinToString { "${it.name}=${if (it.blue) "●" else "○" }}" }}",
            mism.isEmpty(),
        )
        val lit = rows.count { it.blue }
        assertTrue("$group 组亮了 $lit 行，最多只能一行", lit <= 1)
    }

    /** 每组恰好一行画着药丸；同组点新项，旧药丸必须从像素上消失 */
    private fun assertEachGroupOneLit(status: List<RowPix>, types: List<RowPix>) {
        val sLit = status.count { it.blue }
        val tLit = types.count { it.blue }
        assertTrue(
            "任务组亮了 $sLit 行（${status.joinToString { "${it.name}=${if (it.blue) "●" else "○" }}" }}），必须恰好一行",
            sLit == 1,
        )
        assertTrue(
            "类型组亮了 $tLit 行（${types.joinToString { "${it.name}=${if (it.blue) "●" else "○" }}" }}），必须恰好一行",
            tLit == 1,
        )
    }

    @Test
    fun pillRendersOnlyOnSelectedRows() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val dir = a.filesDir.absolutePath
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(task("v1", "a.mp4", Status.COMPLETED))
                    Repo.tasks.add(task("v2", "b.mp4", Status.DOWNLOADING))
                }
                a.findViewById<View>(R.id.recycler).requestLayout()

                val drawer = a.findViewById<SwipeDrawerLayout>(R.id.drawer)
                drawer.openDrawer(androidx.core.view.GravityCompat.START)
                a.findViewById<View>(R.id.drawerPanel).requestLayout()

                val status = a.findViewById<LinearLayout>(R.id.bodyTasks)
                val types = a.findViewById<LinearLayout>(R.id.bodyTypes)
                val panel = a.findViewById<View>(R.id.drawerPanel)

                // 默认：两组的「全部」各亮一行
                assertPillMatches(sampleRows(panel, status), "任务")
                assertPillMatches(sampleRows(panel, types), "类型")
                assertEachGroupOneLit(sampleRows(panel, status), sampleRows(panel, types))

                // 类型组选「视频」再换「音乐」：同组内点新的必须取消旧的（像素级）
                types.getChildAt(1).performClick()
                types.getChildAt(2).performClick()
                drawer.openDrawer(androidx.core.view.GravityCompat.START)

                var tp = sampleRows(panel, types)
                assertPillMatches(tp, "类型")
                assertTrue(
                    "「视频」的旧药丸必须被取消：${tp.joinToString { "${it.name}=${if (it.blue) "●" else "○" }}" }}",
                    tp.none { it.name == a.getString(R.string.nav_video) && it.blue },
                )

                // 任务组再选「已完成」：两组各亮一行，互不干扰
                status.getChildAt(2).performClick()
                drawer.openDrawer(androidx.core.view.GravityCompat.START)

                val sp = sampleRows(panel, status)
                tp = sampleRows(panel, types)
                assertPillMatches(sp, "任务")
                assertPillMatches(tp, "类型")
                assertEachGroupOneLit(sp, tp)
                assertTrue("「音频」不能被任务组的选择挤掉", tp.any { it.name == a.getString(R.string.nav_audio) && it.blue })
            }
        }
    }
}
