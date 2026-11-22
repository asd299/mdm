package com.misterdownloadmanager.mobile

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 顶部工具栏的列表操作按钮：位置应在搜索按钮左侧，且侧边栏不再重复这些入口 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolbarActionsTest {

    @Test
    fun listActionsComeBeforeSearch() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val toolbar = a.findViewById<Toolbar>(R.id.toolbar)
                val menu = toolbar.menu

                val pause = menu.findItem(R.id.action_pause_all)
                val resume = menu.findItem(R.id.action_resume_all)
                val clear = menu.findItem(R.id.action_clear_done)
                val search = menu.findItem(R.id.action_search)
                val sort = menu.findItem(R.id.action_sort)

                assertNotNull("缺少「全部暂停」", pause)
                assertNotNull("缺少「全部继续」", resume)
                assertNotNull("缺少「清除已完成」", clear)
                assertNotNull("缺少搜索", search)

                assertTrue("「全部暂停」应排在搜索左侧", pause.order < search.order)
                assertTrue("「全部继续」应排在搜索左侧", resume.order < search.order)
                assertTrue("「清除已完成」应排在搜索左侧", clear.order < search.order)
                assertTrue("搜索仍在排序左侧", search.order < sort.order)
            }
        }
    }

    @Test
    fun expandingSearchHidesListActions() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val toolbar = a.findViewById<Toolbar>(R.id.toolbar)
                val search = toolbar.menu.findItem(R.id.action_search)
                assertTrue("搜索框应能展开", search.expandActionView())
                assertTrue(
                    "搜索展开时应收起列表操作，避免挤进溢出菜单",
                    !toolbar.menu.findItem(R.id.action_pause_all).isVisible
                )
                assertTrue(
                    "搜索展开时应收起排序",
                    !toolbar.menu.findItem(R.id.action_sort).isVisible
                )
                search.collapseActionView()
                assertTrue(
                    "收起搜索后列表操作应恢复",
                    toolbar.menu.findItem(R.id.action_pause_all).isVisible
                )
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

                assertEquals("侧边栏「更多」只应保留 2 项", 2, labels.size)
                val pause = a.getString(R.string.pause_all)
                val resume = a.getString(R.string.resume_all)
                val clear = a.getString(R.string.clear_finished)
                assertTrue("「$pause」不应再出现在侧边栏", pause !in labels)
                assertTrue("「$resume」不应再出现在侧边栏", resume !in labels)
                assertTrue("「$clear」不应再出现在侧边栏", clear !in labels)
            }
        }
    }
}
