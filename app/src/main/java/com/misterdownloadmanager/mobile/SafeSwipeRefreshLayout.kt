package com.misterdownloadmanager.mobile

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * 下拉刷新容器，给「进入下拉」加一道方向与距离的闸门。
 *
 * 系统 [SwipeRefreshLayout] 只要在列表顶部、手指向下挪过 `touchSlop`（约 8dp）
 * 就会接管手势。长按列表项的那 500ms 里，手指几乎不可能完全静止，
 * 稍微下移就会被判成下拉，结果长按没触发、反而冒出刷新圈——这正是
 * 「长按着长按着就刷新了」的来源。
 *
 * 这里在每次 MOVE 时先看总位移：竖向不足 [START_SLOP_DP] 或更像横向滑动时，
 * 让 `canChildScrollUp()` 暂时返回 true，父类便不会开始拖拽；位移够了再放行。
 * 长按成功后宿主还会禁用它并调用 `requestDisallowInterceptTouchEvent`，双保险。
 */
class SafeSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    /** 由宿主注入：列表是否还能向上滚（滚上去就不该下拉） */
    var canScrollUp: (() -> Boolean)? = null

    private var downX = 0f
    private var downY = 0f

    /** 本次 MOVE 是否还不够格开始下拉 */
    private var blocked = false

    /** 本次手势是否已经放行给父类：放行后不再拦截，避免拖到一半被冻住 */
    private var started = false

    private val startSlopPx = START_SLOP_DP * resources.displayMetrics.density

    /** 本次手势是否被闸门挡住（单测用） */
    internal val isPullBlocked: Boolean get() = blocked

    init {
        setOnChildScrollUpCallback { _, _ -> blocked || canScrollUp?.invoke() == true }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                blocked = false
                started = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!started) {
                    val dy = ev.y - downY
                    val dx = ev.x - downX
                    // 竖向位移不够（手抖 / 长按）或横向更多（左滑选择）：不让刷新接手
                    if (dy >= startSlopPx && kotlin.math.abs(dx) <= dy) {
                        started = true
                        blocked = false
                    } else {
                        blocked = true
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                blocked = false
                started = false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    private companion object {
        /** 至少下拉这么多才开始跟手，比系统 touchSlop 更保守 */
        const val START_SLOP_DP = 16f
    }
}
