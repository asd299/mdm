package com.misterdownloadmanager.mobile

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SaveStoreTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    private fun tree(docId: String) = Uri.parse(
        "content://com.android.externalstorage.documents/tree/" +
            Uri.encode(docId)
    )

    @Test
    fun treeLabelIsHumanReadable() {
        assertEquals("内部存储/Download/MDM", SaveStore.resolveLabel(ctx, tree("primary:Download/MDM")))
        assertEquals("内部存储", SaveStore.resolveLabel(ctx, tree("primary:")))
        assertEquals("外部存储（1A2B-3C4D）/Movies", SaveStore.resolveLabel(ctx, tree("1A2B-3C4D:Movies")))
    }

    @Test
    fun relativePathOnlyForPrimaryExternalStorage() {
        assertEquals("Download/MDM", SaveStore.relativePathOf(tree("primary:Download/MDM")))
        assertNull("SD 卡不走 MediaStore", SaveStore.relativePathOf(tree("1A2B-3C4D:Movies")))
        assertNull(
            "其他 provider 不走 MediaStore",
            SaveStore.relativePathOf(Uri.parse("content://com.android.providers.downloads.documents/tree/downloads"))
        )
    }

    @Test
    fun customDirIsReportedAndCanBeCleared() {
        val uri = tree("primary:Download/MDM")
        SaveStore.remember(ctx, uri)
        assertTrue(SaveStore.isCustom(ctx))
        assertEquals("内部存储/Download/MDM", SaveStore.label(ctx))
        SaveStore.forget(ctx)
        assertFalse(SaveStore.isCustom(ctx))
        assertEquals("系统下载目录（默认）", SaveStore.label(ctx))
    }

    @Test
    fun missingSourceFailsWithReason() {
        val saved = SaveStore.save(
            ctx, File(ctx.cacheDir, "not-exists.bin"), "not-exists.bin", "application/octet-stream"
        )
        assertFalse(saved.ok)
        assertEquals("源文件不存在", saved.error)
    }

    @Test
    fun taskJsonKeepsSaveLocationAndError() {
        val t = DownloadTask(
            id = "a", url = "https://e.com/f.apk", fileName = "f.apk", dir = ctx.filesDir.absolutePath,
            status = Status.COMPLETED,
            savedLocation = "内部存储/Download/MDM/f.apk",
            savedUri = "content://x/y",
            saveError = "所选目录写入失败",
        )
        val back = DownloadTask.fromJson(t.toJson())
        assertEquals(t.savedLocation, back.savedLocation)
        assertEquals(t.savedUri, back.savedUri)
        assertEquals(t.saveError, back.saveError)
    }
}
