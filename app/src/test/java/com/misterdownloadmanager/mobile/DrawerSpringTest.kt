package com.misterdownloadmanager.mobile

import android.view.MotionEvent
import android.view.View
import androidx.core.view.GravityCompat
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 侧边栏吸附的弹簧参数：阻尼比 1、带上手指速度、越界橡皮筋 + 回弹。
 *
 * 只验证「交给弹簧的参数对不对」和「拖动越界的几何」，不依赖 Robolectric 跑动画帧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DrawerSpringTest {

    private fun drawerOf(a: MainActivity): SwipeDrawerLayout = a.findViewById(R.id.drawer)

    /** 摆好尺寸并按 1233x2673 布局，返回抽屉面板 */
    private fun layoutDrawer(a: MainActivity): View {
        val decor = a.window.decorView
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, 1233, 2673)
        val drawer = drawerOf(a)
        drawer.requestLayout()
        drawer.measure(
            View.MeasureSpec.makeMeasureSpec(1233, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2673, View.MeasureSpec.EXACTLY),
        )
        drawer.layout(0, 0, 1233, 2673)
        return a.findViewById(R.id.drawerPanel)
    }

    private fun send(a: MainActivity, action: Int, x: Float, y: Float, time: Long) {
        val e = MotionEvent.obtain(0L, time, action, x, y, 0)
        a.dispatchTouchEvent(e)
        e.recycle()
    }

    /** 右滑松手：弹簧阻尼比必须是 1，并且带上了手指离开时的速度 */
    @Test
    fun releaseFeedsFingerVelocityIntoCriticalSpring() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                layoutDrawer(a)
                val drawer = drawerOf(a)

                send(a, MotionEvent.ACTION_DOWN, 80f, 900f, 0)
                send(a, MotionEvent.ACTION_MOVE, 260f, 902f, 20)
                send(a, MotionEvent.ACTION_MOVE, 520f, 904f, 40)
                send(a, MotionEvent.ACTION_UP, 520f, 904f, 55)

                assertEquals("阻尼比应为 1（临界阻尼）", 1f, drawer.lastSpringDamping, 1e-4f)
                assertTrue("应带上向右的手指速度：${drawer.lastSpringVelocity}", drawer.lastSpringVelocity > 0f)
                assertEquals("向右甩应吸附到全开", 0f, drawer.lastSpringTarget, 0.5f)
                assertTrue("openDrawer 后应立即视为打开", drawer.isDrawerOpen(GravityCompat.START))
            }
        }
    }

    /** 左滑关闭：速度方向也要带进弹簧，目标是 -面板宽 */
    @Test
    fun closeCarriesNegativeVelocity() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val panel = layoutDrawer(a)
                val drawer = drawerOf(a)
                drawer.openDrawer(GravityCompat.START)
                // Robolectric 不跑帧：直接落到打开边界，再重排一次
                drawer.snapTo(true)
                layoutDrawer(a)
                assertEquals(0, panel.left)

                send(a, MotionEvent.ACTION_DOWN, 700f, 900f, 0)
                send(a, MotionEvent.ACTION_MOVE, 600f, 902f, 20)
                send(a, MotionEvent.ACTION_MOVE, 420f, 904f, 40)
                send(a, MotionEvent.ACTION_UP, 420f, 904f, 55)

                assertEquals(1f, drawer.lastSpringDamping, 1e-4f)
                assertTrue("应带上向左的速度：${drawer.lastSpringVelocity}", drawer.lastSpringVelocity < 0f)
                assertEquals("应吸附到关闭", -panel.width.toFloat(), drawer.lastSpringTarget, 0.5f)
            }
        }
    }

    /** 拉过全开位置：手指只能把抽屉带出去一小段（橡皮筋），松手弹簧弹回边界 */
    @Test
    fun pullPastEdgeRubberBandsThenSpringsBack() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val panel = layoutDrawer(a)
                val drawer = drawerOf(a)
                val w = panel.width
                assertTrue("测试前提：面板宽度有效 $w", w > 100)

                send(a, MotionEvent.ACTION_DOWN, 80f, 900f, 0)
                send(a, MotionEvent.ACTION_MOVE, 80f + w * 0.55f, 902f, 20)
                send(a, MotionEvent.ACTION_MOVE, 80f + w * 1.6f, 904f, 40)

                val over = panel.left
                assertTrue("允许拉出边界一点点：$over", over > 0)
                assertTrue(
                    "越界量应收敛在面板宽的 18% 内：$over / $w",
                    over <= (w * 0.18f).toInt() + 1,
                )

                send(a, MotionEvent.ACTION_UP, 80f + w * 1.6f, 904f, 55)
                assertEquals("松手后应弹回全开边界", 0f, drawer.lastSpringTarget, 0.5f)
                assertEquals("回弹同样用临界阻尼", 1f, drawer.lastSpringDamping, 1e-4f)
            }
        }
    }

    /** 已经有位移动画在跑时再点一下，不应残留 pending 回调 */
    @Test
    fun openDrawerStartsSpring() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                layoutDrawer(a)
                val drawer = drawerOf(a)
                drawer.openDrawer(GravityCompat.START)
                assertEquals(1f, drawer.lastSpringDamping, 1e-4f)
                assertEquals(0f, drawer.lastSpringVelocity, 1e-4f)
                assertEquals(0f, drawer.lastSpringTarget, 0.5f)
            }
        }
    }
}
