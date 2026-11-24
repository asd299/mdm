package com.misterdownloadmanager.mobile

import android.graphics.Path
import android.graphics.RectF

/**
 * 全项目共用的圆角路径。
 *
 * 统一走 `Path.addRoundRect` 的标准圆弧圆角：切点处就是真正的 1/4 圆，
 * 与 Material 卡片、系统控件的圆角语言一致。之前尝试过「连续圆角」的
 * 三次贝塞尔近似（控制点内收），在小半径上角部会显得发尖、发斜，
 * 实际观感不如标准圆弧，这里回退到圆弧实现。
 *
 * 用法统一，小到徽标、大到抽屉都是同一套圆角绘制，方便 `getOutline` /
 * `clipToOutline` 与绘制结果完全对齐。
 */
object SmoothCornerPath {

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

        // 单角半径不能超过短边一半，否则相邻两角会互相吃掉
        val maxR = minOf(w, h) / 2f
        val radii = floatArrayOf(
            topLeft.coerceIn(0f, maxR), topLeft.coerceIn(0f, maxR),
            topRight.coerceIn(0f, maxR), topRight.coerceIn(0f, maxR),
            bottomRight.coerceIn(0f, maxR), bottomRight.coerceIn(0f, maxR),
            bottomLeft.coerceIn(0f, maxR), bottomLeft.coerceIn(0f, maxR),
        )
        path.addRoundRect(RectF(left, top, right, bottom), radii, Path.Direction.CW)
    }

    /** 四角同半径的快捷方式 */
    fun build(path: Path, left: Float, top: Float, right: Float, bottom: Float, radius: Float) =
        build(path, left, top, right, bottom, radius, radius, radius, radius)
}
