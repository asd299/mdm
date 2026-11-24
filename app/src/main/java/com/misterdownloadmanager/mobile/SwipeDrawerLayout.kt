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
import androidx.dynamicanimation.animation.FloatPropertyCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 跟手展开的侧边栏容器，替代 DrawerLayout。
 *
 * DrawerLayout 只在屏幕左边缘约 20dp 内响应拖拽，很难碰到，于是之前只能在识别到右滑后
 * 直接 `openDrawer()`——抽屉是「弹」出来的，不跟手。这里改成：
 *
 * - 屏幕**任意位置**右滑都能拖动抽屉，位移完全跟着手指；
 * - 松手后看「最后一段的方向」吸附：往开的方向挪过一点点就完全展开，中途往左滑回来
 *   则直接关闭（不再要求拖过 42%）；
 * - 吸附动画是**弹簧**：阻尼比 1（临界阻尼，不会来回晃），并带上手指离开时的速度，
 *   轻甩即走、猛甩也不至于飞出去；
 * - 拖到全开 / 全关后继续拉，走橡皮筋阻尼（越拉越沉），松手由弹簧带着回弹到边界；
 * - 打开后可在抽屉上左滑关闭，点遮罩也能关；
 * - 内容区随进度轻微右移，形成 iOS 那种前后层次。
 *
 * 性能：拖动阶段由 ViewDragHelper 改 `left`（它只能这么走），但**松手后的弹簧只动
 * `translationX`**。`offsetLeftAndRight` 每帧都会让父容器整棵树失效重绘，带着
 * RecyclerView 跑会明显掉帧；`translationX` 只是 RenderNode 的合成变换，动画期间
 * draw / layout 都不发生，同样的弹簧参数看起来会顺很多。
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

    /** 手指速度上限：再猛也不让抽屉甩太远，回弹幅度可控 */
    private val maxSpringVelocity = 8000f * density

    /** 越界橡皮筋：最多能拉出面板宽度的这么多 */
    private val maxOverscrollRatio = 0.18f

    /** 越界后跟手的比例（1 = 完全跟手，越小越沉） */
    private val overscrollDamping = 0.42f

    /** 弹簧刚度：临界阻尼下约 200ms 到位，比补间动画跟手 */
    private val springStiffness = SpringForce.STIFFNESS_MEDIUM

    /** 当前吸附动画（null = 没有动画在跑） */
    private var spring: SpringAnimation? = null

    /** 最近一次吸附的参数，测试用来确认「阻尼比 1 + 带上了手指速度」 */
    internal var lastSpringTarget = 0f
        private set
    internal var lastSpringVelocity = 0f
        private set
    internal var lastSpringDamping = 0f
        private set
    internal var lastSpringToOpen = false
        private set

    /** 松手时正在从 ViewDragHelper 收尾：abort() 会再回一次 onViewReleased，要忽略 */
    private var releasing = false

    /**
     * 弹簧只驱动 `translationX`。
     *
     * 吸附开始前先把抽屉的 `left` 摆到边界（全开 0 / 全关 -w），再把「视觉位置与边界
     * 的差」写进 translationX，所以动画全程只改合成层，不触发任何 layout。
     */
    private val drawerShift = object : FloatPropertyCompat<View>("drawerShift") {
        override fun getValue(view: View): Float = view.translationX

        override fun setValue(view: View, value: Float) {
            view.translationX = value
            val w = view.width
            if (w > 0) applyOffset(((view.left + value + w) / w).coerceIn(0f, 1f))
        }
    }

    private val dragHelper: ViewDragHelper = ViewDragHelper.create(this, 1.05f, object : ViewDragHelper.Callback() {
        override fun tryCaptureView(child: View, pointerId: Int): Boolean = child === drawer

        override fun getViewHorizontalDragRange(child: View): Int = child.width

        override fun clampViewPositionHorizontal(child: View, left: Int, dx: Int): Int {
            val w = child.width
            if (w <= 0) return 0
            val maxOver = w * maxOverscrollRatio
            return when {
                // 已经全开还继续往右拉：橡皮筋
                left > 0 -> rubberBand(left.toFloat(), maxOver).roundToInt()
                // 已经全关还继续往左拉：同样回弹
                left < -w -> (-w - rubberBand((-left - w).toFloat(), maxOver)).roundToInt()
                else -> left
            }
        }

        override fun clampViewPositionVertical(child: View, top: Int, dy: Int): Int = 0

        override fun onViewPositionChanged(changedView: View, left: Int, top: Int, dx: Int, dy: Int) {
            // 拖动阶段完全由 left 决定位置，动画残留的合成位移必须先归零
            if (changedView.translationX != 0f) changedView.translationX = 0f
            val w = drawer?.width ?: return
            if (w <= 0) return
            val p = ((left + w).toFloat() / w).coerceIn(0f, 1f)
            trackDirection(p)
            applyOffset(p)
        }

        override fun onViewReleased(releasedChild: View, xvel: Float, yvel: Float) {
            if (releasing) return
            val w = releasedChild.width
            if (w <= 0) return
            val p = ((releasedChild.left + w).toFloat() / w).coerceIn(0f, 1f)
            // 甩动优先，其次看最后一段的移动方向
            val settleOpen = when {
                xvel > flingVelocity -> true
                xvel < -flingVelocity -> false
                p > turnOffset -> true
                p < turnOffset -> false
                else -> isOpen
            }
            // 先让 ViewDragHelper 交还控制权，再由弹簧接管剩下的位移
            releasing = true
            dragHelper.abort()
            releasing = false
            settle(settleOpen, xvel)
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
                // 弹簧没跑完就按下去：停在当前位置，交给手指继续拖
                val animating = spring != null
                cancelSpring()
                // 把合成位移折算回 left，后面 ViewDragHelper 改 left 不会叠加出跳变
                flattenTranslation()
                if (animating) isOpen = currentOffset() >= 0.5f
                pendingState = null
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
        flattenTranslation()
        val down = MotionEvent.obtain(ev)
        down.setAction(MotionEvent.ACTION_DOWN)
        down.setLocation(startX, startY)
        dragHelper.processTouchEvent(down)
        down.recycle()
        dragHelper.captureChildView(d, ev.getPointerId(0))
        dragHelper.processTouchEvent(ev)
    }

    override fun computeScroll() {
        // 拖拽时的位移是即时的；只有 ViewDragHelper 自己接管过 settle 才需要这里推进
        if (dragHelper.continueSettling(true)) {
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    /**
     * 吸附到全开 / 全关。
     *
     * [velocity] 是松手瞬间的手指速度（px/s），直接喂给弹簧，所以轻甩就会顺着甩的方向走，
     * 而不是从零速重新起步。
     */
    private fun settle(toOpen: Boolean, velocity: Float = 0f) {
        val d = drawer ?: return
        val w = d.width
        cancelSpring()
        if (w <= 0) {
            isOpen = toOpen
            return
        }
        isOpen = toOpen
        pendingState = toOpen
        lastSpringToOpen = toOpen

        // 视觉位置（left + 合成位移）在动画开始前后必须完全一致，否则会跳一帧
        val visualLeft = d.left + d.translationX
        val targetLeft = if (toOpen) 0 else -w
        d.offsetLeftAndRight(targetLeft - d.left)
        d.translationX = visualLeft - targetLeft
        // 弹簧把 translationX 拉回 0：抽屉正好落在边界，全程只走合成，不重排内容
        startSpring(0f, velocity)
    }

    private fun startSpring(target: Float, velocity: Float) {
        val d = drawer ?: return
        val v = velocity.coerceIn(-maxSpringVelocity, maxSpringVelocity)
        lastSpringTarget = target
        lastSpringVelocity = v
        lastSpringDamping = SpringForce.DAMPING_RATIO_NO_BOUNCY

        val anim = SpringAnimation(d, drawerShift, target)
        anim.spring = SpringForce(target).apply {
            stiffness = springStiffness
            // 阻尼比 1：临界阻尼，不会越过目标来回晃；只剩「松手速度」带来的自然冲量
            dampingRatio = SpringForce.DAMPING_RATIO_NO_BOUNCY
        }
        anim.setStartVelocity(v)
        anim.addEndListener { animation, canceled, _, _ ->
            if (animation === spring) {
                spring = null
                if (!canceled) {
                    pendingState?.let { open ->
                        pendingState = null
                        val view = drawer ?: return@addEndListener
                        listeners.toList().forEach {
                            if (open) it.onDrawerOpened(view) else it.onDrawerClosed(view)
                        }
                    }
                }
            }
        }
        spring = anim
        anim.start()
    }

    private fun cancelSpring() {
        spring?.let { if (it.isRunning) it.cancel() }
        spring = null
    }

    /** 把合成位移折回 left，保证「拖拽改 left」与「弹簧改 translationX」不叠加 */
    private fun flattenTranslation() {
        val d = drawer ?: return
        val shift = d.translationX
        if (shift == 0f) return
        d.offsetLeftAndRight(shift.roundToInt())
        d.translationX = 0f
    }

    /** 测试用：跳过动画直接落到边界（Robolectric 不跑补间帧） */
    internal fun snapTo(open: Boolean) {
        val d = drawer ?: return
        cancelSpring()
        pendingState = null
        isOpen = open
        d.translationX = 0f
        val target = if (open) 0 else -d.width
        d.offsetLeftAndRight(target - d.left)
        applyOffset(if (open) 1f else 0f)
    }

    /** 边界外的橡皮筋：跟手比例衰减，最多拉出 [maxOverscrollRatio] 的面板宽度 */
    private fun rubberBand(over: Float, maxOver: Float): Float {
        if (over <= 0f) return 0f
        return (over * overscrollDamping).coerceAtMost(maxOver)
    }

    /** 当前进度：0 关闭、1 全开（含合成位移） */
    private fun currentOffset(): Float {
        val d = drawer ?: return if (isOpen) 1f else 0f
        val w = d.width
        if (w <= 0) return if (isOpen) 1f else 0f
        return ((d.left + d.translationX + w).toFloat() / w).coerceIn(0f, 1f)
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
        if (dw <= 0) return
        // 动画期间 left 已固定在目标边界，translationX 负责剩下的视觉位移；
        // 拖拽中保持手指给的位置（可能越界），不要被重新布局拉回边界
        val left = if (dragging) d.left else if (isOpen) 0 else -dw
        d.layout(left, 0, left + dw, h)
        applyOffset(currentOffset())
    }

    override fun onDetachedFromWindow() {
        cancelSpring()
        dragHelper.cancel()
        super.onDetachedFromWindow()
    }
}
