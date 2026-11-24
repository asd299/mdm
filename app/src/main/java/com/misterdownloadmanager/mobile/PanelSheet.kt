package com.misterdownloadmanager.mobile

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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
 * 遮罩不用 `FLAG_DIM_BEHIND`：那是 WindowManager 对「窗口背后整块屏幕」压暗，
 * 连状态栏一起压，展开时头顶会黑一条。这里改成在载体 [host] 上自己画一层半透明黑，
 * 只覆盖窗口内容区，状态栏保持原样；窗口的状态栏 / 导航栏颜色也显式设成透明。
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

    /** 全屏透明载体：自绘遮罩 + 装面板，点面板外的空白处关闭 */
    private val host = FrameLayout(activity)

    private val lp = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, halfHeight, Gravity.BOTTOM
    )

    private var expanded = false
    private var animator: ValueAnimator? = null

    /** 关闭后回调，宿主用来释放引用 */
    var onDismiss: (() -> Unit)? = null

    val isShowing: Boolean get() = dialog.isShowing

    init {
        host.id = R.id.panelHost
        host.setBackgroundColor(SCRIM)
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
            // 状态栏 / 导航栏由本窗口绘制且透明：Dialog 默认会用主题里的深色 statusBarColor
            // 把顶上涂黑一条，这里显式改掉；深浅图标颜色跟宿主页面保持一致
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            statusBarColor = Color.TRANSPARENT
            navigationBarColor = Color.TRANSPARENT
            // 用兼容层设置深浅图标：API 30+ 走 WindowInsetsController，
            // 旧系统内部回落 systemUiVisibility，不再手写废弃常量
            WindowCompat.getInsetsController(this, decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
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
        // 先压到全透明再显示，避免遮罩在首帧亮一下
        host.alpha = 0f
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
        // 遮罩淡入，不跟着面板一起硬闪
        host.animate().alpha(1f).setDuration(SLIDE_MS).start()
    }

    fun dismiss() {
        if (!dialog.isShowing) return
        // 可能还在滑入 / 换高度，先打断再从当前位置滑下去
        animator?.cancel()
        content.animate().cancel()
        host.animate().cancel()
        val h = content.height
        if (h <= 0) {
            dialog.dismiss()
            return
        }
        host.animate().alpha(0f).setDuration(DISMISS_MS).start()
        content.animate()
            .translationY(h.toFloat())
            .setDuration(DISMISS_MS)
            .withEndAction { dialog.dismiss() }
            .start()
    }

    /** 立即收起（Activity 销毁时用，不等滑出动画） */
    fun dismissNow() {
        animator?.cancel()
        content.animate().cancel()
        host.animate().cancel()
        if (dialog.isShowing) dialog.dismiss()
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

        /** 自绘遮罩：只盖窗口内容区，不碰状态栏 */
        val SCRIM = Color.argb((DIM * 255).toInt(), 0, 0, 0)
    }
}
