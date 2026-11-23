package com.misterdownloadmanager.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.customview.widget.ViewDragHelper
import kotlin.math.abs

/**
 * 跟手展开的侧边栏容器，替代 DrawerLayout。
 *
 * DrawerLayout 只在屏幕左边缘约 20dp 内响应拖拽，很难碰到，于是之前只能在识别到右滑后
 * 直接 `openDrawer()`——抽屉是「弹」出来的，不跟手。这里改成：
 *
 * - 屏幕**任意位置**右滑都能拖动抽屉，位移完全跟着手指；
 * - 松手后按「拖过 42%」或甩动速度吸附到开 / 关，动画由 ViewDragHelper 收尾；
 * - 打开后可在抽屉上左滑关闭，点遮罩也能关；
 * - 内容区随进度轻微右移，形成 iOS 那种前后层次。
 */
class SwipeDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    interface Listener {
        fun onDrawerSlide(drawerView: View, slideOffset: Float) {}
        fun onDrawerOpened(drawerView: View) {}
        fun onDrawerClosed(drawerView: View) {}
    }

    private val listeners = mutableListOf<Listener>()
    private var drawer: View? = null
    private var content: View? = null
    private var scrim: View? = null

    private var startX = 0f
    private var startY = 0f
    private var tracking = false
    private var dragging = false
    private var isOpen = false
    private var pendingState: Boolean? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val dragHelper = ViewDragHelper.create(this, 1.05f, object : ViewDragHelper.Callback() {
        override fun tryCaptureView(child: View, pointerId: Int): Boolean = child === drawer

        override fun getViewHorizontalDragRange(child: View): Int = child.width

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            val w = drawer?.width ?: return 0
            return left.coerceIn(-w, 0)
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int = 0

        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            val w = drawer?.width ?: return
            applyOffset(((left + w).toFloat() / w).coerceIn(0f, 1f))
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val w = drawer?.width ?: return
            val p = ((releasedChild.left + w).toFloat() / w).coerceIn(0f, 1f)
            // 甩动优先，其次看拖过的比例
            val settleOpen = when {
                xvel > 700f -> true
                xvel < -700f -> false
                else -> p > 0.42f
            }
            settle(settleOpen)
        }
    })

    init {
        dragHelper.setEdgeTrackingEnabled(ViewDragHelper.EDGE_LEFT)
        // 稍微降低触发速度，轻甩即可吸附
        dragHelper.setMinVelocity(600f * resources.displayMetrics.density)
        isClickable = false
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        content = getChildAt(0)
        scrim = getChildAt(1)
        drawer = getChildAt(2)
        scrim?.setOnClickListener { closeDrawer() }
        scrim?.alpha = 0f
        scrim?.isClickable = false
    }

    // ---------- 公开 API：保持和 DrawerLayout 的用法一致 ----------

    fun openDrawer(@Suppress("UNUSED_PARAMETER") gravity: Int = GravityCompat.START) = settle(true)

    fun closeDrawer(@Suppress("UNUSED_PARAMETER") gravity: Int = GravityCompat.START) = settle(false)

    fun isDrawerOpen(@Suppress("UNUSED_PARAMETER") gravity: Int = GravityCompat.START): Boolean = isOpen

    fun addDrawerListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeDrawerListener(listener: Listener) {
        listeners.remove(listener)
    }

    // ---------- 手势 ----------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (drawer == null) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = ev.x
                startY = ev.y
                tracking = true
                dragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging && tracking) {
                    val dx = ev.x - startX
                    val dy = ev.y - startY
                    val horizontal = abs(dx) > abs(dy) * 1.4f
                    if (horizontal && ((!isOpen && dx > slop) || (isOpen && dx < -slop))) {
                        beginDrag(ev)
                    }
                }
                if (dragging) return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                dragging = false
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return false
        dragHelper.processTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
            dragging = false
        }
        return true
    }

    /**
     * 先把「按下点」喂给 ViewDragHelper，再捕获并处理当前 MOVE，
     * 这样第一帧的位移从手指起点算起，抽屉不会突然跳一段。
     */
    private fun beginDrag(ev: MotionEvent) {
        val d = drawer ?: return
        dragging = true
        val down = MotionEvent.obtain(ev)
        down.setAction(MotionEvent.ACTION_DOWN)
        down.setLocation(startX, startY)
        dragHelper.processTouchEvent(down)
        down.recycle()
        dragHelper.captureChildView(d, ev.getPointerId(0))
        dragHelper.processTouchEvent(ev)
    }

    override fun computeScroll() {
        if (dragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this)
            return
        }
        pendingState?.let { open ->
            pendingState = null
            val d = drawer ?: return
            listeners.toList().forEach {
                if (open) it.onDrawerOpened(d) else it.onDrawerClosed(d)
            }
        }
    }

    private fun settle(toOpen: Boolean) {
        val d = drawer ?: return
        isOpen = toOpen
        val target = if (toOpen) 0 else -d.width
        if (dragHelper.smoothSlideViewTo(d, target, 0)) {
            pendingState = toOpen
            ViewCompat.postInvalidateOnAnimation(this)
        } else {
            d.offsetLeftAndRight(target - d.left)
            applyOffset(if (toOpen) 1f else 0f)
        }
    }

    /** 抽屉进度：0 关闭、1 全开 */
    private fun applyOffset(p: Float) {
        scrim?.apply {
            alpha = p * 0.38f
            isClickable = p > 0.01f
        }
        // 内容轻微跟随，iOS 抽屉的层次感；幅度很小，不会露出底色
        content?.translationX = p * (drawer?.width ?: 0) * 0.16f
        val d = drawer ?: return
        listeners.toList().forEach { it.onDrawerSlide(d, p) }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        content?.layout(0, 0, w, h)
        scrim?.layout(0, 0, w, h)
        val d = drawer ?: return
        val dw = d.measuredWidth
        val left = if (isOpen) 0 else -dw
        d.layout(left, 0, left + dw, h)
    }

    override fun onDetachedFromWindow() {
        dragHelper.cancel()
        super.onDetachedFromWindow()
    }
}
