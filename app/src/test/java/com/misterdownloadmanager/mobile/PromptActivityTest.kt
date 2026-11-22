package com.misterdownloadmanager.mobile

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptActivityTest {

    @Test
    fun launchesWithSharedUrl() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(ctx, PromptActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com/demo.zip")
        }
        ActivityScenario.launch<PromptActivity>(intent).use { }
    }
}
