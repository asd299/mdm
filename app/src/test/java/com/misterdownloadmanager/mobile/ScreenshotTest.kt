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
}
