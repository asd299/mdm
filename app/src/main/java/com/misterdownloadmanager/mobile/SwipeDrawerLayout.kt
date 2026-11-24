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
 * - 松手后看「最后一段的方向」吸附：往开的方向挪过一点点就完全展开，中途往左滑回来
 *   则直接关闭（不再要求拖过 42%，之前只滑一点点会弹回去，很别扭）；
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

    /** 上一帧进度，用来判断手指方向 */
    private var prevOffset = 0f

    /** 本次手势中「最近一次反向」时的进度：松手后和它比方向 */
    private var turnOffset = 0f
    private var dir = 0

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density

    /** 反向要超过这么多才算数，避免末尾抖动把方向判反 */
    private val turnDeadZone = 4f * density

    /** 甩动速度门槛（px/s），超过就按甩的方向吸附 */
    private val flingVelocity = 650f * density

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
            val p = ((left + w).toFloat() / w).coerceIn(0f, 1f)
            trackDirection(p)
            applyOffset(p)
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            val w = drawer?.width ?: return
            val p = ((releasedChild.left + w).toFloat() / w).coerceIn(0f, 1f)
            // 甩动优先，其次看最后一段的移动方向
            val settleOpen = when {
                xvel > flingVelocity -> true
                xvel < -flingVelocity -> false
                p > turnOffset -> true
                p < turnOffset -> false
                else -> isOpen
            }
            settle(settleOpen)
        }
    })

    /**
     * 记录手指方向与拐点。
     *
     * 只要方向没变，拐点就停在手势起点；一旦反向超过 [turnDeadZone]，
     * 拐点挪到反向前的位置。松手时进度比拐点大 → 展开，比拐点小 → 关闭。
     */
    private fun trackDirection(p: Float) {
        val d = p - prevOffset
        prevOffset = p
        if (abs(d) < 1e-4f) return
        val nd = if (d > 0f) 1 else -1
        if (dir == 0) {
            dir = nd
            turnOffset = p - d
        } else if (nd != dir) {
            // 反向还没走过死区：当抖动忽略
            if (abs(p - turnOffset) * (drawer?.width ?: 1) > turnDeadZone) {
                dir = nd
                turnOffset = p
            }
        }
    }

    init {
        dragHelper.setEdgeTrackingEnabled(ViewDragHelper.EDGE_LEFT)
        // 稍微降低触发速度，轻甩即可吸附
        dragHelper.setMinVelocity(550f * density)
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
                // 新手势：方向与拐点从当前位置重新开始
                prevOffset = currentOffset()
                turnOffset = prevOffset
                dir = 0
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

    /** 当前进度：0 关闭、1 全开 */
    private fun currentOffset(): Float {
        val d = drawer ?: return if (isOpen) 1f else 0f
        val w = d.width
        if (w <= 0) return if (isOpen) 1f else 0f
        return ((d.left + w).toFloat() / w).coerceIn(0f, 1f)
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
