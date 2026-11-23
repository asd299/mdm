package com.misterdownloadmanager.mobile

import android.view.MenuItem
import android.view.MotionEvent
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** 下拉刷新指示器能正常收起，以及点搜索框外退出搜索 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RefreshAndSearchTest {

    private fun fieldOf(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)

    private fun callRefresh(a: MainActivity) {
        MainActivity::class.java
            .getDeclaredMethod("refreshList")
            .apply { isAccessible = true }
            .invoke(a)
    }

    /**
     * 手势触发下拉时，SwipeRefreshLayout 会先把 isRefreshing 置为 true 再回调刷新。
     * 旧实现用 isRefreshing 做防重入，导致回调直接 return、指示器永远转下去。
     */
    @Test
    fun spinnerStopsEvenWhenLayoutAlreadyRefreshing() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val swipe = a.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
                // 模拟真实手势路径：框架内部先进入刷新态
                swipe.isRefreshing = true

                callRefresh(a)
                waitUntil(5_000) { !swipe.isRefreshing }

                assertFalse("刷新结束后指示器必须收起", swipe.isRefreshing)
            }
        }
    }

    /** 刷新过程中的异常不能让指示器卡住：界面刷新抛错也要走 finally 收起 */
    @Test
    fun spinnerStopsAfterRepeatedRefresh() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val swipe = a.findViewById<SwipeRefreshLayout>(R.id.swipeRefresh)
                repeat(2) {
                    swipe.isRefreshing = true
                    callRefresh(a)
                    waitUntil(5_000) { !swipe.isRefreshing }
                    assertFalse(swipe.isRefreshing)
                }
            }
        }
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
    }

    /** 搜索框展开后点列表空白处：收起输入框、清空关键字 */
    @Test
    fun tapOutsideCollapsesSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.invalidateOptionsMenu()
                ShadowLooper.idleMainLooper()

                val item = fieldOf(a, "searchItem") as? MenuItem
                val sv = fieldOf(a, "searchView") as? SearchView
                assertNotNull("搜索菜单项应已创建", item)
                assertNotNull("SearchView 应已创建", sv)

                item!!.expandActionView()
                ShadowLooper.idleMainLooper()
                sv!!.isIconified = false
                sv.setQuery("mp4", false)
                ShadowLooper.idleMainLooper()

                val adapter = a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter
                assertEquals("mp4", adapter.query)

                // 点列表区域（搜索框之外）
                tapAt(a, 20f, 1_600f)

                assertTrue("点空白后输入框应收起", sv.isIconified)
                assertEquals("退出搜索后应清空关键字", "", adapter.query)
                assertFalse("菜单项不应再处于展开状态", item.isActionViewExpanded)
            }
        }
    }

    /** 点搜索框内部不应被当成「点空白」 */
    @Test
    fun tapInsideSearchKeepsSearchOpen() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.invalidateOptionsMenu()
                ShadowLooper.idleMainLooper()

                val item = fieldOf(a, "searchItem") as MenuItem
                val sv = fieldOf(a, "searchView") as SearchView
                item.expandActionView()
                ShadowLooper.idleMainLooper()
                sv.isIconified = false
                sv.setQuery("mp4", false)
                ShadowLooper.idleMainLooper()

                val loc = IntArray(2)
                sv.getLocationOnScreen(loc)
                tapAt(a, loc[0] + sv.width / 2f, loc[1] + sv.height / 2f)

                assertFalse("点输入框本身不应收起", sv.isIconified)
                assertEquals("mp4", (a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter).query)
            }
        }
    }

    /** 返回键也应先退出搜索，而不是直接退出页面 */
    @Test
    fun backKeyCollapsesSearchFirst() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                a.invalidateOptionsMenu()
                ShadowLooper.idleMainLooper()

                val item = fieldOf(a, "searchItem") as MenuItem
                val sv = fieldOf(a, "searchView") as SearchView
                item.expandActionView()
                ShadowLooper.idleMainLooper()
                sv.isIconified = false
                sv.setQuery("mp4", false)
                ShadowLooper.idleMainLooper()

                a.onBackPressed()
                ShadowLooper.idleMainLooper()

                assertTrue("返回键应收起搜索框", sv.isIconified)
                assertEquals(
                    "",
                    (a.findViewById<RecyclerView>(R.id.recycler).adapter as DownloadsAdapter).query
                )
            }
        }
    }

    private fun tapAt(a: MainActivity, x: Float, y: Float) {
        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(0L, 10L, MotionEvent.ACTION_UP, x, y, 0)
        a.dispatchTouchEvent(down)
        a.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        ShadowLooper.idleMainLooper()
    }
}
