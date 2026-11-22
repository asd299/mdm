package com.misterdownloadmanager.mobile

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局崩溃捕获：
 * 1. 把完整堆栈写入应用私有目录
 * 2. 立即写入系统剪切板（用户可直接粘贴反馈）
 * 3. 下次启动时提示查看 / 再次复制
 */
object CrashHandler {

    private const val DIR = "crash"
    private const val LAST = "last_crash.txt"
    private const val SEEN = ".seen"

    /** 安装全局异常处理器，需在 Application.onCreate 中调用 */
    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val report = buildReport(app, thread, throwable)
                writeFile(app, report)
                copyToClipboard(app, report)
            } catch (_: Throwable) {
                // 崩溃处理本身绝不能再抛异常
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private var lastSoftCopyAt = 0L

    /**
     * 非致命错误：始终记录到日志文件；剪切板写入做节流（60 秒一次），
     * 避免界面刷新类小错误反复覆盖用户剪切板。
     */
    fun recordSoftError(context: Context, where: String, throwable: Throwable) {
        try {
            val text = buildSoftReport(context, where, throwable)
            appendHistory(context, text)
            val now = System.currentTimeMillis()
            if (now - lastSoftCopyAt > 60_000L) {
                lastSoftCopyAt = now
                copyToClipboard(context, text)
            }
        } catch (_: Throwable) {
        }
    }

    fun copyToClipboard(context: Context, text: String): Boolean = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("MDM 日志", text))
        true
    } catch (_: Throwable) {
        false
    }

    /** 读取最近一次崩溃日志；没有崩溃则回退为错误历史（最近 8KB） */
    fun readLast(context: Context): String? {
        val dir = File(context.filesDir, DIR)
        return try {
            val last = File(dir, LAST)
            if (last.exists() && last.readText().isNotBlank()) {
                last.readText()
            } else {
                val history = File(dir, "history.log")
                if (!history.exists()) null else {
                    val text = history.readText()
                    if (text.isBlank()) null else text.takeLast(8 * 1024)
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    fun hasUnseen(context: Context): Boolean {
        val dir = File(context.filesDir, DIR)
        return File(dir, LAST).exists() && !File(dir, SEEN).exists()
    }

    fun markSeen(context: Context) {
        try {
            File(File(context.filesDir, DIR), SEEN).writeText("1")
        } catch (_: Throwable) {
        }
    }

    fun clear(context: Context) {
        try {
            val dir = File(context.filesDir, DIR)
            File(dir, LAST).delete()
            File(dir, SEEN).delete()
            File(dir, "history.log").delete()
        } catch (_: Throwable) {
        }
    }

    // ---------- 内部 ----------

    private fun writeFile(context: Context, text: String) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        File(dir, LAST).writeText(text)
        File(dir, SEEN).delete()
        appendHistory(context, text)
    }

    private fun appendHistory(context: Context, text: String) {
        try {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val f = File(dir, "history.log")
            if (f.exists() && f.length() > 256 * 1024) f.delete()
            f.appendText("\n\n" + text)
        } catch (_: Throwable) {
        }
    }

    private fun header(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Throwable) {
            "?"
        }
        return buildString {
            append("时间：").append(
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            ).append('\n')
            append("应用：Mister 下载管理器 v").append(version).append('\n')
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("系统：Android ").append(Build.VERSION.RELEASE)
                .append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            append("包名：").append(context.packageName).append('\n')
        }
    }

    private fun buildReport(context: Context, thread: Thread, throwable: Throwable): String =
        buildString {
            append(header(context))
            append("线程：").append(thread.name).append('\n')
            append("类型：未捕获异常（应用闪退）\n")
            append("\n---------- 堆栈 ----------\n")
            append(stackOf(throwable))
        }

    private fun buildSoftReport(context: Context, where: String, throwable: Throwable): String =
        buildString {
            append(header(context))
            append("位置：").append(where).append('\n')
            append("类型：运行时错误（未闪退）\n")
            append("\n---------- 堆栈 ----------\n")
            append(stackOf(throwable))
        }

    private fun stackOf(t: Throwable): String {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        return sw.toString()
    }
}
