package com.misterdownloadmanager.mobile

import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 「仅限 Wi-Fi」策略、等待状态展示、删除弹窗勾选框与下拉闸门。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WifiAndGestureTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun prefs() = ctx.getSharedPreferences(WifiPolicy.PREFS, Context.MODE_PRIVATE)

    private fun task() = DownloadTask(
        id = "wifi-1",
        url = "https://example.com/a.bin",
        fileName = "a.bin",
        dir = ctx.filesDir.absolutePath,
    )

    @After
    fun tearDown() {
        WifiPolicy.wifiProbe = null
        prefs().edit().clear().commit()
    }

    /** 只在「开了开关 + 不在 Wi-Fi + 没选仍然继续」时才等待 */
    @Test
    fun waitingNeedsSwitchMobileNetworkAndNoExemption() {
        val t = task()
        WifiPolicy.wifiProbe = { false }

        prefs().edit().putBoolean(WifiPolicy.KEY_WIFI_ONLY, true).commit()
        assertTrue("移动网络 + 仅限 Wi-Fi 应等待", WifiPolicy.shouldWait(ctx, t))

        t.ignoreWifi = true
        assertFalse("用户选了仍然继续就不该等", WifiPolicy.shouldWait(ctx, t))

        t.ignoreWifi = false
        WifiPolicy.wifiProbe = { true }
        assertFalse("已在 Wi-Fi 应直接下载", WifiPolicy.shouldWait(ctx, t))

        WifiPolicy.wifiProbe = { false }
        prefs().edit().putBoolean(WifiPolicy.KEY_WIFI_ONLY, false).commit()
        assertFalse("没开开关不应等待", WifiPolicy.shouldWait(ctx, t))
    }

    /** 列表项状态列显示「等待 Wi-Fi」，右侧按钮仍是「继续」 */
    @Test
    fun waitingTaskShowsStatusInList() {
        Repo.init(ctx)
        val t = task().apply { status = Status.WAITING_WIFI }
        synchronized(Repo.tasks) {
            Repo.tasks.clear()
            Repo.tasks.add(t)
        }
        val themed = ContextThemeWrapper(ctx, R.style.Theme_MDM)
        val adapter = DownloadsAdapter({}, {}, {})
        adapter.refresh()
        val holder = adapter.onCreateViewHolder(FrameLayout(themed), 0)
        adapter.onBindViewHolder(holder, 0)

        assertEquals("等待 Wi-Fi", holder.status.text.toString())
        assertEquals(
            themed.getString(R.string.resume),
            holder.action.contentDescription?.toString()
        )
        assertEquals("等待中操作按钮组照常显示", ViewGroup.VISIBLE, holder.ops.visibility)
    }

    /** 删除弹窗的勾选框：与标题同样 24dp 缩进，图标 22dp + 10dp 间距 */
    @Test
    fun deleteCheckBoxHasProperInsets() {
        val activity = org.robolectric.Robolectric.buildActivity(MainActivity::class.java)
            .setup().get()
        val m = MainActivity::class.java.getDeclaredMethod("buildDeleteConfirmView")
        m.isAccessible = true
        val row = m.invoke(activity) as ViewGroup
        val box = row.findViewById<android.widget.CheckBox>(R.id.checkDeleteFile)
        val label = row.getChildAt(1) as TextView
        val d = activity.resources.displayMetrics.density
        val pad = (24 * d).toInt()

        assertEquals("整行与标题同为 24dp 缩进", pad, row.paddingStart)
        assertEquals(pad, row.paddingEnd)
        assertEquals("勾选框与文字只留 10dp", (10 * d).toInt(), (label.layoutParams as ViewGroup.MarginLayoutParams).marginStart)
        assertEquals(22 * d, box.layoutParams.width.toFloat(), 0.5f)
        assertTrue("默认勾选", box.isChecked)
        row.performClick()
        assertFalse("点整行可取消勾选", box.isChecked)
    }

    /** 手指只挪几像素（长按时的抖动）时，下拉刷新不能接管手势 */
    @Test
    fun smallMoveIsBlockedButRealPullIsAllowed() {
        val themed = ContextThemeWrapper(ctx, R.style.Theme_MDM)
        val layout = SafeSwipeRefreshLayout(themed)
        layout.canScrollUp = { false }

        fun move(dy: Float, action: Int) = MotionEvent.obtain(
            0L, 0L, action, 100f, 100f + dy, 0
        ).also { layout.onInterceptTouchEvent(it); it.recycle() }

        move(0f, MotionEvent.ACTION_DOWN)
        move(6f, MotionEvent.ACTION_MOVE)
        assertTrue("6dp 属于手抖，应挡住", layout.isPullBlocked)

        move(0f, MotionEvent.ACTION_UP)
        move(0f, MotionEvent.ACTION_DOWN)
        move(48f, MotionEvent.ACTION_MOVE)
        assertFalse("明确了向下拖，应放行", layout.isPullBlocked)

        move(0f, MotionEvent.ACTION_UP)
    }

    /** 横向滑动（左滑选择）不能被当成下拉 */
    @Test
    fun horizontalMoveIsNotAPull() {
        val themed = ContextThemeWrapper(ctx, R.style.Theme_MDM)
        val layout = SafeSwipeRefreshLayout(themed)
        layout.canScrollUp = { false }

        val down = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_DOWN, 40f, 100f, 0)
        layout.onInterceptTouchEvent(down)
        down.recycle()
        val move = MotionEvent.obtain(0L, 0L, MotionEvent.ACTION_MOVE, 220f, 122f, 0)
        layout.onInterceptTouchEvent(move)
        move.recycle()

        assertTrue("横滑距离远大于竖移，应挡住", layout.isPullBlocked)
    }

    /** 等待状态下通知文案说明在等 Wi-Fi，而不是「已暂停」 */
    @Test
    fun waitingStatusHasOwnLabel() {
        assertEquals("等待 Wi-Fi", ctx.getString(R.string.status_waiting_wifi))
        assertTrue(ctx.getString(R.string.notif_waiting_wifi, 2).contains("2"))
        // 状态列文本不被「已暂停」误用
        val layout = ContextThemeWrapper(ctx, R.style.Theme_MDM)
        val tv = TextView(layout)
        tv.setText(R.string.status_waiting_wifi)
        assertEquals("等待 Wi-Fi", tv.text.toString())
    }
}
