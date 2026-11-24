package com.misterdownloadmanager.mobile

import android.content.Intent
import android.net.Uri
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** 重复链接/重名检测、编号文件名、手动命名优先、文件名变化同步到列表 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ConflictAndNamingTest {

    private fun task(
        id: String,
        name: String,
        status: Status,
        url: String = "https://example.com/$name",
        dir: String = "/tmp",
        manual: Boolean = false,
    ) = DownloadTask(
        id = id,
        url = url,
        fileName = name,
        dir = dir,
        status = status,
        manualName = manual,
    )

    @Test
    fun detectsDuplicateLink() {
        val existing = task("1", "a.mp4", Status.DOWNLOADING, url = "https://x.com/a.mp4")
        val c = ConflictChecker.find(
            "https://x.com/a.mp4#frag",
            "",
            listOf(existing),
            File("/tmp"),
        )
        assertNotNull("同链接应判定冲突", c)
        assertEquals(ConflictKind.LINK, c!!.kind)
        assertTrue(c.hasLinkConflict)
        assertFalse(c.hasNameConflict)
    }

    @Test
    fun failedTaskDoesNotBlockSameLink() {
        val failed = task("1", "a.mp4", Status.FAILED, url = "https://x.com/a.mp4")
        assertNull(
            "失败的任务允许用同一链接重试",
            ConflictChecker.find("https://x.com/a.mp4", "", listOf(failed), File("/tmp"))
        )
    }

    @Test
    fun detectsDuplicateNameAndDiskFile() {
        val existing = task("1", "movie.mp4", Status.COMPLETED, url = "https://x.com/1")
        val dir = File("/tmp/mdm-name-test").apply { mkdirs() }
        File(dir, "other.zip").writeText("x")

        val byName = ConflictChecker.find("https://x.com/2", "movie.mp4", listOf(existing), dir)
        assertNotNull(byName)
        assertEquals(ConflictKind.NAME, byName!!.kind)
        assertTrue(byName.hasNameConflict)

        val byDisk = ConflictChecker.find("https://x.com/3", "other.zip", emptyList(), dir)
        assertNotNull("目录里已有同名文件也算冲突", byDisk)
        assertTrue(byDisk!!.fileOnDisk)
    }

    @Test
    fun reportsBothKindsTogether() {
        val existing = task("1", "movie.mp4", Status.DOWNLOADING, url = "https://x.com/a")
        val c = ConflictChecker.find("https://x.com/a", "movie.mp4", listOf(existing), File("/tmp"))
        assertEquals(ConflictKind.BOTH, c!!.kind)
        assertTrue(c.hasLinkConflict)
        assertTrue(c.hasNameConflict)
    }

    @Test
    fun numberedNameSkipsTakenOnes() {
        val dir = File("/tmp/mdm-num-test").apply { mkdirs() }
        File(dir, "电影.mp4").writeText("x")
        val tasks = listOf(
            task("1", "电影(1).mp4", Status.COMPLETED),
            task("2", "电影(2).mp4", Status.PAUSED),
        )
        assertEquals("电影(3).mp4", ConflictChecker.numberedName("电影.mp4", tasks, dir))
        assertEquals("无扩展名(1)", ConflictChecker.numberedName("无扩展名", emptyList(), dir))
    }

    @Test
    fun manualNameSurvivesJsonRoundTrip() {
        val t = task("1", "我的名字.mp4", Status.PAUSED, manual = true)
        val back = DownloadTask.fromJson(JSONObject(t.toJson().toString()))
        assertTrue("手动命名标记要持久化", back.manualName)
        assertEquals("我的名字.mp4", back.fileName)
    }

    /** 其它应用点链接（VIEW）与分享文本（SEND）都要能取出下载地址 */
    @Test
    fun incomingIntentsYieldDownloadUrl() {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse("https://x.com/a.mp4?x=1"))
        assertEquals("https://x.com/a.mp4?x=1", PromptActivity.extractUrl(view))

        val share = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "看看这个 https://y.com/b 很不错")
        assertEquals("https://y.com/b", PromptActivity.extractUrl(share))
    }

    /** 有冲突时必须弹窗，而不是静默跳过或直接添加 */
    @Test
    fun conflictPopsSelectionDialog() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val existing = task(
                    "dup", "a.mp4", Status.DOWNLOADING,
                    url = "https://x.com/a.mp4", dir = a.filesDir.absolutePath
                )
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(existing)
                }

                AddCoordinator(a).submit("https://x.com/a.mp4", "a.mp4", manual = true, startNow = true)
                ShadowLooper.idleMainLooper()

                val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                assertNotNull("应弹出冲突选择框", dlg)
                assertTrue(dlg.isShowing)
                dlg.dismiss()
            }
        }
    }

    /** 探测到真实文件名后，列表里显示的名字必须跟着变（payload 不能只刷进度） */
    @Test
    fun adapterUpdatesNameWhenProbeRenamesTask() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val t = task("probe", "", Status.DOWNLOADING, dir = a.filesDir.absolutePath)
                synchronized(Repo.tasks) {
                    Repo.tasks.clear()
                    Repo.tasks.add(t)
                }
                val ad = a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter
                ad.refresh()
                ShadowLooper.idleMainLooper()

                val parent = FrameLayout(a)
                val vh = ad.onCreateViewHolder(parent, 0) as DownloadsAdapter.VH
                ad.onBindViewHolder(vh, 0)
                assertEquals(a.getString(R.string.status_waiting), vh.name.text.toString())

                // 模拟 probe 完成后改名
                t.fileName = "真实文件名.mp4"
                ad.refresh()
                ShadowLooper.idleMainLooper()
                ad.onBindViewHolder(vh, 0, mutableListOf<Any>(1))

                assertEquals("真实文件名.mp4", vh.name.text.toString())
                assertEquals("MP4", vh.ext.text.toString())
            }
        }
    }
}
