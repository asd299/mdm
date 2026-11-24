package com.misterdownloadmanager.mobile

import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import org.xmlpull.v1.XmlPullParser

/**
 * 平滑圆角（iOS continuous corner）背景 Drawable。
 *
 * 用法（在 res/drawable 里就能带参数，布局不用改）：
 * ```
 * <drawable class="com.misterdownloadmanager.mobile.SmoothShapeDrawable"
 *     xmlns:app="http://schemas.android.com/apk/res-auto"
 *     app:smColor="@color/ios_card"
 *     app:smRadius="16dp" />
 * ```
 * 支持整圆 / 分角半径、描边与线性渐变；[getOutline] 上报同一条平滑路径，
 * 所以卡片阴影、`clipToOutline` 的裁剪也都是平滑圆角，不会在角上露出直角。
 */
class SmoothShapeDrawable @JvmOverloads constructor(
    initRadius: Float = 0f,
    initFill: Int = Color.TRANSPARENT,
) : Drawable() {

    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private var radius = initRadius
    private var rTL = -1f
    private var rTR = -1f
    private var rBR = -1f
    private var rBL = -1f

    private var fillColor = initFill
    private var strokeWidth = 0f
    private var strokeColor = Color.TRANSPARENT
    private var gradientStart = Color.TRANSPARENT
    private var gradientEnd = Color.TRANSPARENT
    private var gradientAngle = 315f
    private var hasGradient = false

    private var shader: Shader? = null
    private var alphaValue = 255
    private var filter: ColorFilter? = null

    /** 描边向内收半个线宽，避免边框被 View 边界切掉一半 */
    private val inset: Float
        get() = strokeWidth / 2f

    override fun inflate(
        r: Resources,
        parser: XmlPullParser,
        attrs: AttributeSet,
        theme: Resources.Theme?,
    ) {
        super.inflate(r, parser, attrs, theme)
        val a = r.obtainAttributes(attrs, R.styleable.SmoothShapeDrawable)
        fillColor = a.getColor(R.styleable.SmoothShapeDrawable_smColor, Color.TRANSPARENT)
        radius = a.getDimension(R.styleable.SmoothShapeDrawable_smRadius, 0f)
        rTL = a.getDimension(R.styleable.SmoothShapeDrawable_smRadiusTopLeft, -1f)
        rTR = a.getDimension(R.styleable.SmoothShapeDrawable_smRadiusTopRight, -1f)
        rBR = a.getDimension(R.styleable.SmoothShapeDrawable_smRadiusBottomRight, -1f)
        rBL = a.getDimension(R.styleable.SmoothShapeDrawable_smRadiusBottomLeft, -1f)
        strokeWidth = a.getDimension(R.styleable.SmoothShapeDrawable_smStrokeWidth, 0f)
        strokeColor = a.getColor(R.styleable.SmoothShapeDrawable_smStrokeColor, Color.TRANSPARENT)
        val gStart = a.getColor(R.styleable.SmoothShapeDrawable_smGradientStart, Color.TRANSPARENT)
        val gEnd = a.getColor(R.styleable.SmoothShapeDrawable_smGradientEnd, Color.TRANSPARENT)
        gradientAngle = a.getFloat(R.styleable.SmoothShapeDrawable_smGradientAngle, 315f)
        a.recycle()

        // 起止色都给了才算渐变，否则按单色填充
        hasGradient = gStart != Color.TRANSPARENT || gEnd != Color.TRANSPARENT
        gradientStart = gStart
        gradientEnd = gEnd

        strokePaint.strokeWidth = strokeWidth
        strokePaint.color = strokeColor
        invalidateCache()
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        if (path.isEmpty) rebuild()
        if (path.isEmpty) return

        fillPaint.alpha = alphaValue
        fillPaint.colorFilter = filter
        if (hasGradient && shader != null) {
            fillPaint.shader = shader
        } else {
            fillPaint.shader = null
            fillPaint.color = fillColor
        }
        canvas.drawPath(path, fillPaint)

        if (strokeWidth > 0f && Color.alpha(strokeColor) > 0) {
            strokePaint.alpha = alphaValue
            strokePaint.colorFilter = filter
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun rebuild() {
        val b = bounds
        if (b.isEmpty) {
            path.reset()
            return
        }
        val i = inset
        val l = b.left + i
        val t = b.top + i
        val r = b.right - i
        val bottom = b.bottom - i
        if (r <= l || bottom <= t) {
            path.reset()
            return
        }
        val radiusTL = if (rTL >= 0f) rTL else radius
        val radiusTR = if (rTR >= 0f) rTR else radius
        val radiusBR = if (rBR >= 0f) rBR else radius
        val radiusBL = if (rBL >= 0f) rBL else radius
        SmoothCornerPath.build(path, l, t, r, bottom, radiusTL, radiusTR, radiusBR, radiusBL)

        shader = if (hasGradient) makeGradient(l, t, r, bottom) else null
    }

    private fun makeGradient(l: Float, t: Float, r: Float, b: Float): Shader {
        val rad = Math.toRadians(gradientAngle.toDouble())
        val dx = (Math.cos(rad) * (r - l) / 2.0).toFloat()
        val dy = (Math.sin(rad) * (b - t) / 2.0).toFloat()
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        return LinearGradient(
            cx - dx, cy + dy, cx + dx, cy - dy,
            gradientStart, gradientEnd, Shader.TileMode.CLAMP
        )
    }

    private fun invalidateCache() {
        path.reset()
        invalidateSelf()
    }

    override fun getOutline(outline: Outline) {
        if (path.isEmpty) rebuild()
        if (path.isEmpty) {
            outline.setRect(bounds)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            outline.setPath(path)
        } else {
            // 圆角矩形是凸路径，API 21–29 用 setConvexPath 也能拿到平滑轮廓
            @Suppress("DEPRECATION")
            outline.setConvexPath(path)
        }
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        filter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /** 运行时加 / 去描边（例如列表项多选高亮） */
    fun setStroke(widthPx: Float, color: Int) {
        strokeWidth = widthPx
        strokeColor = color
        strokePaint.strokeWidth = widthPx
        strokePaint.color = color
        invalidateCache()
    }

    /** 用于界面里直接设置扁平色（例如状态变化时） */
    fun setFillColor(color: Int) {
        fillColor = color
        hasGradient = false
        invalidateSelf()
    }
}
