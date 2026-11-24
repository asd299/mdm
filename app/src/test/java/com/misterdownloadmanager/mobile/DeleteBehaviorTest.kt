package com.misterdownloadmanager.mobile

import android.app.Dialog
import android.widget.CheckBox
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File

/**
 * 删除行为：
 * - 确认框里默认勾选「同时删除已下载的文件」；
 * - 取消勾选后只移除任务记录，磁盘上的成品文件要保留；
 * - 保留勾选时照旧把文件删掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class DeleteBehaviorTest {

    private fun completedTask(dir: String, id: String): DownloadTask {
        val at = 1_700_000_000_000L
        val t = DownloadTask(
            id = id,
            url = "https://example.com/$id.mp4",
            fileName = "$id.mp4",
            dir = dir,
            totalBytes = 10,
            segments = mutableListOf(Segment(0, 0, 9, 10)),
            status = Status.COMPLETED,
            addedAt = at,
            startedAt = at,
            finishedAt = at + 1_000,
            activeMillis = 1_000,
        )
        t.finalFile.parentFile?.mkdirs()
        t.finalFile.writeText("payload")
        return t
    }

    /** 单个删除的确认框默认勾选删除文件 */
    @Test
    fun confirmDialogChecksDeleteFileByDefault() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = completedTask(a.filesDir.absolutePath, "del1")
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }

                val m = MainActivity::class.java
                    .getDeclaredMethod("confirmDelete", DownloadTask::class.java)
                m.isAccessible = true
                m.invoke(a, task)

                val d: Dialog = ShadowDialog.getLatestDialog()
                assertNotNull("应弹出删除确认框", d)
                val check = d.findViewById<CheckBox>(R.id.checkDeleteFile)
                assertNotNull("确认框里应有删除文件勾选框", check)
                assertTrue("默认应勾选删除文件", check!!.isChecked)
                d.dismiss()
            }
        }
    }

    /** 取消勾选：任务记录没了，但文件还在 */
    @Test
    fun removeWithoutFilesKeepsDownloadedFile() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = completedTask(a.filesDir.absolutePath, "del2")
                val file: File = task.finalFile
                assertTrue(file.exists())
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }

                Repo.remove(a, task, deleteFiles = false)

                assertNull("任务记录应被移除", Repo.find(task.id))
                assertTrue("取消勾选后文件应保留：${file.absolutePath}", file.exists())
            }
        }
    }

    /** 保持勾选：文件一起删掉 */
    @Test
    fun removeWithFilesDeletesDownloadedFile() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = completedTask(a.filesDir.absolutePath, "del3")
                val file: File = task.finalFile
                assertTrue(file.exists())
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }

                Repo.remove(a, task, deleteFiles = true)

                assertNull("任务记录应被移除", Repo.find(task.id))
                assertFalse("勾选删除文件后文件应被删除", file.exists())
            }
        }
    }
}
