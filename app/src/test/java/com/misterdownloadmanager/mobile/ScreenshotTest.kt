package com.misterdownloadmanager.mobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {

    private fun shot(v: View, w: Int, h: Int, path: String) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        v.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        File(path).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun renderMain() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = ctx.filesDir.absolutePath
        synchronized(Repo.tasks) {
            Repo.tasks.clear()
            Repo.tasks.add(
                DownloadTask(
                    id = "a", url = "https://example.com/Movies/Interstellar.2014.2160p.mkv",
                    fileName = "Interstellar.2014.2160p.mkv", dir = dir,
                    totalBytes = 8_000_000_000, status = Status.DOWNLOADING,
                    segments = mutableListOf(Segment(0, 0, 8_000_000_000, 3_100_000_000)),
                )
            )
            Repo.tasks.add(
                DownloadTask(
                    id = "b", url = "https://example.com/app-release.apk",
                    fileName = "app-release.apk", dir = dir,
                    totalBytes = 24_000_000, status = Status.COMPLETED,
                    segments = mutableListOf(Segment(0, 0, 24_000_000, 24_000_000)),
                    publicSaved = true,
                )
            )
            Repo.tasks.add(
                DownloadTask(
                    id = "c", url = "https://example.com/Photos/IMG_2048.jpg",
                    fileName = "IMG_2048.jpg", dir = dir,
                    totalBytes = 4_500_000, status = Status.PAUSED,
                    segments = mutableListOf(Segment(0, 0, 4_500_000, 1_200_000)),
                )
            )
            Repo.tasks.add(
                DownloadTask(
                    id = "d", url = "https://example.com/broken.zip",
                    fileName = "broken.zip", dir = dir,
                    status = Status.FAILED, error = "HTTP 404",
                )
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
                // 关闭入场动画，静态截图才能看到内容（动画需要真实帧时钟）
                rv.layoutAnimation = null
                rv.requestLayout()
                shot(a.window.decorView, 1080, 2280, "/tmp/shot_main.png")
            }
        }
    }

    /** 顶部操作行 + 内联排序面板展开态 */
    @Test
    fun renderSortPanel() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = ctx.filesDir.absolutePath
        synchronized(Repo.tasks) {
            Repo.tasks.clear()
            Repo.tasks.add(
                DownloadTask(
                    id = "s1", url = "https://example.com/Interstellar.mkv",
                    fileName = "Interstellar.2014.2160p.mkv", dir = dir,
                    totalBytes = 8_000_000_000, status = Status.DOWNLOADING,
                    segments = mutableListOf(Segment(0, 0, 8_000_000_000, 3_100_000_000)),
                )
            )
            Repo.tasks.add(
                DownloadTask(
                    id = "s2", url = "https://example.com/app-release.apk",
                    fileName = "app-release.apk", dir = dir,
                    totalBytes = 24_000_000, status = Status.COMPLETED,
                    segments = mutableListOf(Segment(0, 0, 24_000_000, 24_000_000)),
                    publicSaved = true,
                )
            )
            Repo.tasks.add(
                DownloadTask(
                    id = "s3", url = "https://example.com/IMG_2048.jpg",
                    fileName = "IMG_2048.jpg", dir = dir,
                    totalBytes = 4_500_000, status = Status.PAUSED,
                    segments = mutableListOf(Segment(0, 0, 4_500_000, 1_200_000)),
                )
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
                rv.layoutAnimation = null
                a.findViewById<View>(R.id.btnSort).performClick()
                // 展开动画走真实帧时钟，这里把主线程时间推过去再截图
                org.robolectric.shadows.ShadowLooper.idleMainLooper(
                    600, java.util.concurrent.TimeUnit.MILLISECONDS
                )
                shot(a.window.decorView, 1080, 2280, "/tmp/shot_sort.png")
            }
        }
    }

    @Test
    fun renderDrawer() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = ctx.filesDir.absolutePath
        synchronized(Repo.tasks) {
            if (Repo.tasks.isEmpty()) {
                Repo.tasks.add(
                    DownloadTask(id = "x", url = "https://example.com/a.mp4", fileName = "a.mp4", dir = dir,
                        totalBytes = 1000, status = Status.COMPLETED,
                        segments = mutableListOf(Segment(0, 0, 1000, 1000)))
                )
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<androidx.drawerlayout.widget.DrawerLayout>(R.id.drawer)
                    .openDrawer(androidx.core.view.GravityCompat.START)
                val panel = a.findViewById<View>(R.id.drawerPanel)
                shot(panel, 924, 2280, "/tmp/shot_drawer.png")
                // 滚动到底部，检查内联设置与更多分组
                val scroll = a.findViewById<android.widget.ScrollView>(R.id.drawerScroll)
                shot(panel, 924, 2280, "/tmp/shot_drawer_scrolled.png")
                scroll.scrollTo(0, 2000)
                shot(panel, 924, 2280, "/tmp/shot_drawer_bottom.png")
            }
        }
    }

    /** 左滑多选后的列表：选中态高亮 + 底部批量操作条 */
    @Test
    fun renderSelection() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = ctx.filesDir.absolutePath
        val names = listOf(
            "Interstellar.2014.2160p.mkv", "app-release.apk", "IMG_2048.jpg",
            "broken.zip", "notes.pdf",
        )
        val dirs = listOf(
            DownloadTask(id = "p0", url = "https://example.com/a", fileName = names[0], dir = dir,
                totalBytes = 8_000_000_000, status = Status.DOWNLOADING,
                segments = mutableListOf(Segment(0, 0, 8_000_000_000, 3_100_000_000)),
                addedAt = 5_000L),
            DownloadTask(id = "p1", url = "https://example.com/b", fileName = names[1], dir = dir,
                totalBytes = 24_000_000, status = Status.COMPLETED,
                segments = mutableListOf(Segment(0, 0, 24_000_000, 24_000_000)),
                publicSaved = true, addedAt = 4_000L),
            DownloadTask(id = "p2", url = "https://example.com/c", fileName = names[2], dir = dir,
                totalBytes = 4_500_000, status = Status.PAUSED,
                segments = mutableListOf(Segment(0, 0, 4_500_000, 1_200_000)),
                addedAt = 3_000L),
            DownloadTask(id = "p3", url = "https://example.com/d", fileName = names[3], dir = dir,
                status = Status.FAILED, error = "HTTP 404", addedAt = 2_000L),
            DownloadTask(id = "p4", url = "https://example.com/e", fileName = names[4], dir = dir,
                totalBytes = 900_000, status = Status.COMPLETED,
                segments = mutableListOf(Segment(0, 0, 900_000, 900_000)),
                publicSaved = true, addedAt = 1_000L),
        )
        synchronized(Repo.tasks) {
            Repo.tasks.clear()
            Repo.tasks.addAll(dirs)
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
                rv.layoutAnimation = null
                val ad = rv.adapter as DownloadsAdapter
                ad.refresh()
                // 模拟：先左滑第 1 项，再左滑第 3 项 → 三项区间选中
                ad.selectOnly(dirs[0])
                ad.selectRange(dirs[0].id, dirs[2].id)
                rv.requestLayout()
                shot(a.window.decorView, 1080, 2280, "/tmp/shot_selection.png")
            }
        }
    }

    /** 双层筛选：只选「视频」，任务维度仍为全部 */
    @Test
    fun renderTypeFilter() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val dir = ctx.filesDir.absolutePath
        synchronized(Repo.tasks) {
            Repo.tasks.clear()
            Repo.tasks.add(
                DownloadTask(id = "f1", url = "https://example.com/Interstellar.mkv",
                    fileName = "Interstellar.2014.2160p.mkv", dir = dir,
                    totalBytes = 8_000_000_000, status = Status.DOWNLOADING,
                    segments = mutableListOf(Segment(0, 0, 8_000_000_000, 3_100_000_000)))
            )
            Repo.tasks.add(
                DownloadTask(id = "f2", url = "https://example.com/app-release.apk",
                    fileName = "app-release.apk", dir = dir,
                    totalBytes = 24_000_000, status = Status.COMPLETED,
                    segments = mutableListOf(Segment(0, 0, 24_000_000, 24_000_000)),
                    publicSaved = true)
            )
            Repo.tasks.add(
                DownloadTask(id = "f3", url = "https://example.com/IMG_2048.jpg",
                    fileName = "IMG_2048.jpg", dir = dir,
                    totalBytes = 4_500_000, status = Status.PAUSED,
                    segments = mutableListOf(Segment(0, 0, 4_500_000, 1_200_000)))
            )
        }
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val rv = a.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler)
                rv.layoutAnimation = null
                val types = a.findViewById<android.widget.LinearLayout>(R.id.bodyTypes)
                types.getChildAt(1).performClick() // 视频
                // 切分类会播放列表入场动画；静止截图前关掉，避免行还在动画起始态
                rv.layoutAnimation = null
                rv.requestLayout()
                org.robolectric.shadows.ShadowLooper.idleMainLooper(
                    400, java.util.concurrent.TimeUnit.MILLISECONDS
                )
                shot(a.window.decorView, 1080, 2280, "/tmp/shot_filter_video.png")
            }
        }
    }

    @Test
    fun renderSheet() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(ctx, PromptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/Movies/Interstellar.2014.2160p.mkv")
        }
        ActivityScenario.launch<PromptActivity>(intent).use {
            val d = ShadowDialog.getLatestDialog() ?: return@use
            shot(d.window!!.decorView, 1080, 2280, "/tmp/shot_sheet.png")
        }
    }

    /** 添加重复任务时的冲突选择框 */
    @Test
    fun renderConflict() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val dir = a.filesDir.absolutePath
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(
                        DownloadTask(
                            id = "dup", url = "https://example.com/Movies/Interstellar.2014.2160p.mkv",
                            fileName = "Interstellar.2014.2160p.mkv", dir = dir,
                            totalBytes = 8_000_000_000, status = Status.DOWNLOADING,
                            segments = mutableListOf(Segment(0, 0, 8_000_000_000, 3_100_000_000)),
                        )
                    )
                }
                AddCoordinator(a).submit(
                    "https://example.com/Movies/Interstellar.2014.2160p.mkv",
                    "Interstellar.2014.2160p.mkv", manual = true, startNow = true,
                )
                val d = ShadowDialog.getLatestDialog() ?: return@onActivity
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val decor = d.window!!.decorView
                decor.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST),
                )
                println("CONFLICT_H ${decor.measuredHeight}")
                shot(decor, 1080, decor.measuredHeight.coerceAtLeast(600), "/tmp/shot_conflict.png")
                d.dismiss()
            }
        }
    }

    /** 关于页：图标 / 名称 / 版本 + 各入口卡片 */
    @Test
    fun renderAbout() {
        ActivityScenario.launch(AboutActivity::class.java).use { sc ->
            sc.onActivity { a ->
                shot(a.window.decorView, 1080, 2280, "/tmp/shot_about.png")

                // 全页长图：确认底部致谢卡片与页脚也能正常排版
                val content = a.findViewById<View>(android.R.id.content)
                val body = (content as android.view.ViewGroup).getChildAt(0) as android.view.ViewGroup
                val scroll = body.getChildAt(1) as android.widget.ScrollView
                val page = scroll.getChildAt(0)
                page.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                shot(
                    page, 1080, page.measuredHeight,
                    "/tmp/shot_about_full.png",
                )
            }
        }
    }

    /** 版本更新说明：自下而上的面板 */
    @Test
    fun renderChangelog() {
        ActivityScenario.launch(AboutActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.findViewById<View>(R.id.rowChangelog).performClick()
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val d = ShadowDialog.getLatestDialog() ?: return@onActivity
                shot(d.window!!.decorView, 1080, 2280, "/tmp/shot_changelog.png")
                d.dismiss()
            }
        }
    }

    /** 崩溃日志页：圆润卡片风格 */
    @Test
    fun renderCrashLog() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        CrashHandler.recordSoftError(
            ctx, "下载分段校验",
            IllegalStateException("下载分段校验失败：连接提前中断"),
        )
        ActivityScenario.launch(CrashLogActivity::class.java).use { sc ->
            sc.onActivity { a ->
                shot(a.window.decorView, 1080, 2280, "/tmp/shot_crash.png")
            }
        }
    }
}
