package com.misterdownloadmanager.mobile

import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashHandlerTest {

    @Test
    fun softErrorIsCopiedToClipboard() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        CrashHandler.recordSoftError(ctx, "单元测试", IllegalStateException("boom-test-123"))
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("剪切板未包含错误信息：$text", text.contains("boom-test-123"))
        assertTrue("日志缺少位置信息", text.contains("单元测试"))
    }

    @Test
    fun crashLogActivityLaunches() {
        ActivityScenario.launch(CrashLogActivity::class.java).use { }
    }
}
