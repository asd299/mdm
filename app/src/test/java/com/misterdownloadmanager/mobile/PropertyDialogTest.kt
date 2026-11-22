package com.misterdownloadmanager.mobile

import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PropertyDialogTest {

    private val addedAt = 1_795_356_000_000L

    private fun sample(dir: String) = DownloadTask(
        id = "prop1",
        url = "https://example.com/d/Scene_8.3.8_com.scene.tool_signed99y.apk" +
            "?response-content-disposition=attachment",
        fileName = "Scene_8.3.8_com.scene.tool_signed99y.apk",
        dir = dir,
        totalBytes = 17_542_040,
        segments = mutableListOf(Segment(0, 0, 17_542_039, 17_542_040)),
        status = Status.COMPLETED,
        publicSaved = true,
        addedAt = addedAt,
        startedAt = addedAt,
        finishedAt = addedAt + 60_000,
        activeMillis = 12_000,
    )

    private fun openProps(a: MainActivity, t: DownloadTask): Dialog? {
        val m = MainActivity::class.java
            .getDeclaredMethod("showProperties", DownloadTask::class.java)
        m.isAccessible = true
        m.invoke(a, t)
        return ShadowDialog.getLatestDialog()
    }

    private fun rows(d: Dialog): List<Pair<String, String>> {
        val list = d.findViewById<LinearLayout>(R.id.propList)!!
        return (0 until list.childCount).map { i ->
            val row = list.getChildAt(i)
            row.findViewById<TextView>(R.id.tvLabel).text.toString() to
                row.findViewById<TextView>(R.id.tvValue).text.toString()
        }
    }

    private fun fieldOf(o: Any, name: String): Any? {
        var c: Class<*>? = o.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(o)
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        return null
    }

    /** 直接读 AlertController.mItems：不依赖 ListView 是否已经布局 */
    private fun alertItems(d: Dialog): List<String> {
        val controller = fieldOf(d, "mAlert")!!
        val items = fieldOf(controller, "mItems") as Array<*>
        return items.map { it.toString() }
    }

    @Test
    fun propertiesShowAllFields() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = sample(a.filesDir.absolutePath)
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }
                val d = openProps(a, task)!!
                val map = rows(d).toMap()

                assertEquals(task.fileName, map["文件名"])
                assertEquals(task.url, map["下载链接"])
                assertEquals(task.finalFile.absolutePath, map["路径"])
                assertEquals("已完成", map["状态"])
                assertTrue("大小异常：${map["大小"]}", map["大小"]!!.contains("16.7 MB"))
                assertTrue("大小字节数异常：${map["大小"]}", map["大小"]!!.contains("17542040 字节"))
                assertTrue("已完成异常：${map["已完成"]}", map["已完成"]!!.contains("17542040 字节"))
                assertEquals("1.4 MB/s", map["平均速度"])
                assertEquals("12 秒", map["活动时间"])
                assertNotEquals("-", map["添加日期"])
                assertNotEquals("-", map["完成日期"])
                assertEquals("已复制到系统下载目录", map["附加信息"])
            }
        }
    }

    /** 路径必须显示文件真正落地的位置，而不是应用私有临时目录 */
    @Test
    fun propertiesShowRealSaveLocation() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = sample(a.filesDir.absolutePath)
                task.savedLocation = "内部存储/Download/MDM/${task.fileName}"
                task.finalFile.writeText("x")
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }
                val map = rows(openProps(a, task)!!).toMap()

                assertEquals("内部存储/Download/MDM/${task.fileName}", map["路径"])
                assertEquals(task.finalFile.absolutePath, map["临时文件"])
            }
        }
    }

    /** 没能写进所选目录时，属性页要明确给出原因 */
    @Test
    fun propertiesShowSaveError() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = sample(a.filesDir.absolutePath)
                task.saveError = "所选目录写入失败，已改存到系统下载目录"
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }
                val map = rows(openProps(a, task)!!).toMap()

                assertEquals(task.saveError, map["附加信息"])
            }
        }
    }

    @Test
    fun tappingRowCopiesValue() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = sample(a.filesDir.absolutePath)
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }
                val d = openProps(a, task)!!
                val list = d.findViewById<LinearLayout>(R.id.propList)!!
                // 第 2 行是下载链接
                list.getChildAt(1).performClick()
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                assertEquals(task.url, cm.primaryClip?.getItemAt(0)?.text?.toString())
            }
        }
    }

    /** 长按菜单里应有“属性”，且不再出现“分享”（分享移入属性页） */
    @Test
    fun longPressMenuHasPropertiesInsteadOfShare() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = sample(a.filesDir.absolutePath)
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }
                val m = MainActivity::class.java.getDeclaredMethod(
                    "buildItemMenu", DownloadTask::class.java, Boolean::class.javaPrimitiveType
                )
                m.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val pair = m.invoke(a, task, true) as Pair<List<String>, List<() -> Unit>>
                val items = pair.first
                val actions = pair.second
                assertTrue("菜单里没有属性：$items", items.contains("属性"))
                assertTrue("菜单里没有多选：$items", items.contains("多选"))
                assertTrue("分享应已从菜单移除：$items", !items.contains("分享"))
                assertEquals("菜单项与动作数量不一致", items.size, actions.size)
            }
        }
    }

    @Test
    fun renderProperties() {
        ActivityScenario.launch(MainActivity::class.java).use { sc ->
            sc.onActivity { a ->
                val task = sample(a.filesDir.absolutePath)
                synchronized(Repo.tasks) { Repo.tasks.add(0, task) }
                val d = openProps(a, task)!!
                val decor = d.window!!.decorView as ViewGroup
                val content = decor.findViewById<ViewGroup>(android.R.id.content)
                val root = content.getChildAt(0)
                root.measure(
                    View.MeasureSpec.makeMeasureSpec(1012, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val h = root.measuredHeight
                root.layout(0, 0, 1012, h)
                val bmp = Bitmap.createBitmap(1012, h, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                File("/tmp/shot_props.png").outputStream()
                    .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
}
