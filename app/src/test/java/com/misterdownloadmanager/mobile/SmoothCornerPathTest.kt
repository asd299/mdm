package com.misterdownloadmanager.mobile

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 平滑圆角路径：贴住边界、角部内收、内部实心 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SmoothCornerPathTest {

    /** Path 没有直接可用的「点在内部」判断（Kotlin 里 contains 会解析成区间），用 Region 代替 */
    private fun Path.containsPoint(x: Int, y: Int): Boolean {
        val region = Region()
        region.setPath(this, Region(0, 0, 1000, 1000))
        return region.contains(x, y)
    }

    @Test
    fun pathFitsBoundsAndCutsCorners() {
        val path = Path()
        SmoothCornerPath.build(path, 0f, 0f, 100f, 80f, 20f)

        val bounds = RectF()
        path.computeBounds(bounds, true)
        assertFalse("路径不应为空", path.isEmpty)
        assertEquals(0f, bounds.left, 0.01f)
        assertEquals(0f, bounds.top, 0.01f)
        assertEquals(100f, bounds.right, 0.01f)
        assertEquals(80f, bounds.bottom, 0.01f)

        assertTrue("中心应在路径内", path.containsPoint(50, 40))
        assertFalse("直角处应被圆角切掉", path.containsPoint(1, 1))
    }

    @Test
    fun zeroRadiusKeepsRectCorners() {
        val path = Path()
        SmoothCornerPath.build(path, 0f, 0f, 100f, 80f, 0f)
        assertTrue("直角矩形应包含角点", path.containsPoint(1, 1))
    }

    /** 只圆右上、右下时，左侧仍是直角，配合侧边栏 */
    @Test
    fun onlyRightCornersCanBeRounded() {
        val path = Path()
        SmoothCornerPath.build(
            path, 0f, 0f, 100f, 80f,
            topLeft = 0f, topRight = 20f, bottomRight = 20f, bottomLeft = 0f
        )
        assertTrue("左上仍是直角", path.containsPoint(1, 1))
        assertTrue("左下仍是直角", path.containsPoint(1, 79))
        assertFalse("右上应被切掉", path.containsPoint(99, 1))
        assertFalse("右下应被切掉", path.containsPoint(99, 79))
    }
}
