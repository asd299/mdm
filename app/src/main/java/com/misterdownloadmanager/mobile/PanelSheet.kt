package com.misterdownloadmanager.mobile

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

/**
 * 贴底的底部面板：属性页、版本说明共用同一套行为。
 *
 * 面板自己算高度、钉在窗口底部（半屏 62% / 拉满 82%），不走 BottomSheetBehavior——
 * 把窗口设成 82% 高再让行为去「半屏」时，窗口会被居中、面板又只有内容那么高，
 * 面板底边就离屏幕底差一截，缝隙里露出半透明遮罩。这里窗口留全屏，只让面板贴底。
 *
 * 调用方只管把内容视图交进来，再把「抓手区域」绑成 [bindToggle]、「关闭」绑成 [bindClose]。
 */
class PanelSheet(
    private val activity: Activity,
    private val content: View,
    private val halfRatio: Float = HALF_RATIO,
    private val fullRatio: Float = FULL_RATIO,
) {

    private val screenH = activity.resources.displayMetrics.heightPixels
    private val halfHeight = (screenH * halfRatio).toInt()
    private val fullHeight = (screenH * fullRatio).toInt()

    private val dialog = Dialog(activity, R.style.Theme_MDM_Sheet)
    private val lp = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, halfHeight, Gravity.BOTTOM
    )

    private var expanded = false
    private var animator: ValueAnimator? = null

    /** 关闭后回调，宿主用来释放引用 */
    var onDismiss: (() -> Unit)? = null

    val isShowing: Boolean get() = dialog.isShowing

    init {
        // 全屏透明载体：点面板外的空白处关闭
        val host = FrameLayout(activity)
        host.id = R.id.panelHost
        host.setOnClickListener { dismiss() }
        host.addView(content, lp)
        dialog.setContentView(host)
        // 点面板内部不穿透到载体，否则一碰面板就关
        content.isClickable = true
        dialog.setOnDismissListener {
            animator?.cancel()
            animator = null
            onDismiss?.invoke()
        }

        // 面板贴底，但正文要避开导航栏手势区
        val basePadBottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadBottom + bottom)
            insets
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(DIM)
        }
    }

    /** 抓手 / 标题区域：点一下在半屏与拉满之间切换 */
    fun bindToggle(view: View) {
        view.setOnClickListener { toggle() }
    }

    fun bindClose(view: View) {
        view.setOnClickListener { dismiss() }
    }

    /** 半屏 ⇄ 拉满，底边始终贴着屏幕底 */
    fun toggle() {
        expanded = !expanded
        animateHeight(if (expanded) fullHeight else halfHeight)
    }

    fun show() {
        if (dialog.isShowing) return
        dialog.show()
        ViewCompat.requestApplyInsets(content)

        // 自下而上滑入：等第一次布局完成，才有面板高度可用来做起点
        content.doOnLayout {
            if (!dialog.isShowing) return@doOnLayout
            content.translationY = content.height.toFloat()
            content.animate()
                .translationY(0f)
                .setDuration(SLIDE_MS)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    fun dismiss() {
        if (!dialog.isShowing) return
        // 可能还在滑入 / 换高度，先打断再从当前位置滑下去
        animator?.cancel()
        content.animate().cancel()
        val h = content.height
        if (h <= 0) {
            dialog.dismiss()
            return
        }
        content.animate()
            .translationY(h.toFloat())
            .setDuration(DISMISS_MS)
            .withEndAction { dialog.dismiss() }
            .start()
    }

    private fun animateHeight(target: Int) {
        animator?.cancel()
        if (lp.height == target) return
        animator = ValueAnimator.ofInt(lp.height, target).apply {
            duration = TOGGLE_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                content.requestLayout()
            }
            start()
        }
    }

    private companion object {
        /** 默认停在 62% 屏高，点抓手 / 标题拉满到 82% */
        const val HALF_RATIO = 0.62f
        const val FULL_RATIO = 0.82f
        const val DIM = 0.35f
        const val SLIDE_MS = 280L
        const val TOGGLE_MS = 240L
        const val DISMISS_MS = 180L
    }
}
