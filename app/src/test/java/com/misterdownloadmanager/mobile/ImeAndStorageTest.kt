package com.misterdownloadmanager.mobile

import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertArrayEquals
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

/** 输入法收起与搜索的关系、以及下载文件直写公共目录的判定 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ImeAndStorageTest {

    private fun fieldOf(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun openSearch(a: MainActivity, query: String) {
        a.invalidateOptionsMenu()
        ShadowLooper.idleMainLooper()
        val item = fieldOf(a, "searchItem") as MenuItem
        val sv = fieldOf(a, "searchView") as SearchView
        item.expandActionView()
        ShadowLooper.idleMainLooper()
        sv.isIconified = false
        sv.setQuery(query, false)
        ShadowLooper.idleMainLooper()
    }

    /**
     * 用户按键盘上的收起键：输入法消失、搜索框还开着、里面没字——
     * 这时应该直接退出搜索，而不是留一个空输入框在那儿。
     */
    @Test
    fun imeHiddenWithEmptyQueryCollapsesSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                openSearch(a, "")
                val item = fieldOf(a, "searchItem") as MenuItem
                val sv = fieldOf(a, "searchView") as SearchView

                a.onImeHidden()
                ShadowLooper.idleMainLooper()

                assertTrue("收起输入法后应退出搜索", sv.isIconified)
                assertFalse("搜索菜单项应收回图标", item.isActionViewExpanded)
                assertEquals(
                    "",
                    (a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter).query
                )
            }
        }
    }

    /** 有文字时收起输入法：保留搜索状态与结果，只是不再有焦点 */
    @Test
    fun imeHiddenWithQueryKeepsSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                openSearch(a, "mp4")
                val item = fieldOf(a, "searchItem") as MenuItem
                val sv = fieldOf(a, "searchView") as SearchView

                a.onImeHidden()
                ShadowLooper.idleMainLooper()

                assertFalse("有关键字时应保留搜索", sv.isIconified)
                assertTrue("搜索框保持展开", item.isActionViewExpanded)
                assertEquals("关键字必须保留", "mp4", sv.query.toString())
                assertEquals(
                    "mp4",
                    (a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter).query
                )
            }
        }
    }

    /** 搜索本来就没打开时，输入法收起不应影响界面（比如切后台回来） */
    @Test
    fun imeHiddenWithoutSearchDoesNothing() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.invalidateOptionsMenu()
                ShadowLooper.idleMainLooper()
                val item = fieldOf(a, "searchItem") as MenuItem

                a.onImeHidden()
                ShadowLooper.idleMainLooper()

                assertFalse(item.isActionViewExpanded)
            }
        }
    }

    // ---------- 落点选择 ----------

    /** Android 10+ 不需要写权限也能直写公共下载目录；9 及以下必须有权限 */
    @Test
    fun publicWritableRules() {
        assertTrue(PublicStore.shouldWritePublic(34, customDir = false, hasWritePermission = false))
        assertFalse(PublicStore.shouldWritePublic(28, customDir = false, hasWritePermission = false))
        assertTrue(PublicStore.shouldWritePublic(28, customDir = false, hasWritePermission = true))
        // 用户自选了保存目录：先把文件放在私有暂存区，完成后再复制过去
        assertFalse(PublicStore.shouldWritePublic(34, customDir = true, hasWritePermission = true))
    }

    /** 文件名里的非法字符不能让落盘失败 */
    @Test
    fun publicNameIsSanitized() {
        assertEquals("a_b_c.mp4", PublicStore.sanitize("a/b:c.mp4"))
        assertEquals("download.bin", PublicStore.sanitize("  .  "))
        assertFalse(PublicStore.sanitize("x\n y").contains('\n'))
    }

    /** 落点标识必须能随任务一起持久化，重启后还能续传 */
    @Test
    fun taskPublicTargetSurvivesJson() {
        val task = DownloadTask(
            id = "t1", url = "https://example.com/a.mp4", fileName = "a.mp4",
            dir = "/tmp", publicTarget = "content://media/external/downloads/42",
        )
        val restored = DownloadTask.fromJson(task.toJson())
        assertEquals("content://media/external/downloads/42", restored.publicTarget)
        assertTrue(restored.writesPublic)

        val local = DownloadTask(id = "t2", url = "u", fileName = "b.mp4", dir = "/tmp")
        assertNull(DownloadTask.fromJson(local.toJson()).publicTarget)
        assertFalse(local.writesPublic)
    }

    /** 任务直写公共目录后，冲突检测要能看见「下载」目录里的同名文件 */
    @Test
    fun conflictSeesFileInPublicDownloads() {
        val dir = File("/tmp/mdm-none")
        val hit = ConflictChecker.find(
            url = "https://example.com/new.zip",
            fileName = "movie.mp4",
            tasks = emptyList(),
            dir = dir,
            existingNames = setOf("movie.mp4"),
        )
        assertNotNull("公共目录里的同名文件应算冲突", hit)
        assertTrue(hit!!.hasNameConflict)
        assertEquals(ConflictKind.NAME, hit.kind)

        val miss = ConflictChecker.find(
            url = "https://example.com/new.zip",
            fileName = "other.mp4",
            tasks = emptyList(),
            dir = dir,
            existingNames = setOf("movie.mp4"),
        )
        assertNull("不同名不应报冲突", miss)
    }

    /** 自动编号也要避开公共目录里已存在的名字 */
    @Test
    fun numberedNameSkipsPublicNames() {
        val name = ConflictChecker.numberedName(
            "movie.mp4", emptyList(), File("/tmp/mdm-none"), setOf("movie(1).mp4"),
        )
        assertEquals("movie(2).mp4", name)
    }

    /** 公共目录里的普通文件落点：多分段各写各的偏移，互不覆盖 */
    @Test
    fun fileTargetWritesAtOffsets() {
        val dir = File(System.getProperty("java.io.tmpdir"), "mdm-test-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val file = File(dir, "part.bin")
        val target = FileTarget(file)

        target.writer().use { it.writeAt(0, byteArrayOf(1, 2, 3, 4), 0, 4) }
        target.writer().use { it.writeAt(4, byteArrayOf(5, 6), 0, 2) }

        assertEquals("长度应按最大偏移增长", 6L, target.size())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), file.readBytes())
        assertTrue("普通文件不需要 pending 收尾", target.publish())

        target.truncate()
        assertEquals(0L, target.size())

        target.delete()
        assertFalse(file.exists())
        dir.delete()
    }
}
