package com.misterdownloadmanager.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout

/**
 * 侧边栏面板容器：只有右侧两个角是圆角，并且**真的**把子视图裁进这两个圆角里。
 *
 * 之前面板用的是 `clipToOutline`，它依赖背景 shape 上报的 outline。可是按角分别设置半径的
 * shape 在部分版本上只上报一个矩形 outline，等于没裁：顶部那块不透明的白色区域正好压在
 * 右上角上，于是右上角看着是直角；下滑把白色区域滚走之后，才露出背景自己的圆角。
 *
 * 这里改成在 [dispatchDraw] 里用同一条圆角路径 `clipPath`，不依赖 outline 上报，
 * 软件渲染与硬件渲染都会老老实实裁剪——截图测试也能覆盖到。
 */
class DrawerPanelLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val clip = Path()
    private val bounds = RectF()
    private var radius = 0f

    init {
        radius = resources.getDimension(R.dimen.drawer_corner)
        // 阴影也跟着圆角走，否则 12dp 的投影会在右上角露出一个直角
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (clip.isEmpty) {
                    outline.setRect(0, 0, view.width, view.height)
                } else {
                    outline.setPath(clip)
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        buildClip(w, h)
    }

    private fun buildClip(w: Int, h: Int) {
        clip.reset()
        if (w <= 0 || h <= 0) return
        val r = radius.coerceAtMost(w / 2f).coerceAtMost(h / 2f)
        bounds.set(0f, 0f, w.toFloat(), h.toFloat())
        // 顺序：左上、右上、右下、左下 —— 只圆右侧
        clip.addRoundRect(
            bounds,
            floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f),
            Path.Direction.CW,
        )
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (clip.isEmpty) {
            super.dispatchDraw(canvas)
            return
        }
        val save = canvas.save()
        canvas.clipPath(clip)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
