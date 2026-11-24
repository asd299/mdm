package com.misterdownloadmanager.mobile

import android.graphics.Path

/**
 * iOS 风格的「连续圆角」（smooth corns）路径。
 *
 * 普通 `addRoundRect` 画的是圆弧圆角：切点处曲率从 0 直接跳到 1/r，转角显得突兀。
 * iOS 的 continuous corner 在切点处曲率从 0 平滑过渡，观感更「软」也更整。
 *
 * 这里用一段三次贝塞尔近似：端点仍在圆角切点上，两个控制点沿两边各回退 [K]×半径，
 * 曲线在 45° 方向比圆弧略靠外（更饱满），但进出角完全平顺。全项目共用同一算法，
 * 小到徽标、大到抽屉都是同一种圆角语言。
 */
object SmoothCornerPath {

    /** 控制点相对半径的回退比例：0 为直角，越大越接近圆弧 */
    private const val K = 0.44f

    /** 四角半径依次为 左上 / 右上 / 右下 / 左下 */
    fun build(
        path: Path,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        topLeft: Float,
        topRight: Float,
        bottomRight: Float,
        bottomLeft: Float,
    ) {
        path.reset()
        val w = right - left
        val h = bottom - top
        if (w <= 0f || h <= 0f) return

        val maxR = minOf(w, h) / 2f
        val rTL = topLeft.coerceIn(0f, maxR)
        val rTR = topRight.coerceIn(0f, maxR)
        val rBR = bottomRight.coerceIn(0f, maxR)
        val rBL = bottomLeft.coerceIn(0f, maxR)

        path.moveTo(left + rTL, top)
        // 上边 → 右上角
        path.lineTo(right - rTR, top)
        if (rTR > 0f) {
            path.cubicTo(
                right - rTR + K * rTR, top,
                right, top + rTR - K * rTR,
                right, top + rTR,
            )
        }
        // 右边 → 右下角
        path.lineTo(right, bottom - rBR)
        if (rBR > 0f) {
            path.cubicTo(
                right, bottom - rBR + K * rBR,
                right - rBR + K * rBR, bottom,
                right - rBR, bottom,
            )
        }
        // 下边 → 左下角
        path.lineTo(left + rBL, bottom)
        if (rBL > 0f) {
            path.cubicTo(
                left + rBL - K * rBL, bottom,
                left, bottom - rBL + K * rBL,
                left, bottom - rBL,
            )
        }
        // 左边 → 左上角
        path.lineTo(left, top + rTL)
        if (rTL > 0f) {
            path.cubicTo(
                left, top + rTL - K * rTL,
                left + rTL - K * rTL, top,
                left + rTL, top,
            )
        }
        path.close()
    }

    /** 四角同半径的快捷方式 */
    fun build(path: Path, left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
        build(path, left, top, right, bottom, radius, radius, radius, radius)
}
