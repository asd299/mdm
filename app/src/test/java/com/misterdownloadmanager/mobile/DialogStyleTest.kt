package com.misterdownloadmanager.mobile

import android.content.Context
import android.content.Intent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DialogStyleTest {

    /** 主题表面色必须是纯白（而不是 Material3 默认的浅紫白） */
    @Test
    fun themeSurfaceIsNeutralWhite() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val expected = a.getColor(R.color.ios_card)
                val surface = MaterialColors.getColor(a, com.google.android.material.R.attr.colorSurface, 0)
                assertEquals(expected, surface)
            }
        }
    }

    /** 底部面板底色、圆角、标题颜色都要走 iOS 配色 */
    @Test
    fun bottomSheetUsesIosColors() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(ctx, PromptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/demo.zip")
        }
        ActivityScenario.launch<PromptActivity>(intent).use {
            val dialog = ShadowDialog.getLatestDialog()
            val sheet = dialog?.window?.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            )
            assertTrue("未找到底部面板", sheet != null)
            val bg = sheet!!.background
            assertTrue("面板背景应由 MaterialShapeDrawable 提供圆角", bg is MaterialShapeDrawable)
            assertEquals(
                ctx.getColor(R.color.ios_card),
                (bg as MaterialShapeDrawable).fillColor?.defaultColor,
            )

            val tv = sheet.findViewById<android.widget.TextView>(R.id.tvSize)
            assertTrue("未找到状态文字", tv != null)
            assertEquals(ctx.getColor(R.color.ios_blue), tv!!.currentTextColor)
        }
    }
}
