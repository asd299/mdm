package com.misterdownloadmanager.mobile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 悬浮下载确认窗：别的应用把链接交给 MDM 时（分享文本 / 直接打开 http 链接 / 快捷添加），
 * 在当前应用上方弹出确认对话框，点「开始/添加」后立即下载并关闭，不打开主界面。
 * 与主界面共用 [AddCoordinator]，因此同样有重复链接 / 重名文件的冲突选择。
 */
class PromptActivity : AppCompatActivity() {

    private var submitted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = extractUrl(intent)
        if (url.isBlank()) {
            finish()
            return
        }

        val dlg = AddDownloadDialog.newInstance(url)
        val coordinator = AddCoordinator(this) { finish() }
        dlg.setOnSubmit { u, name, manual, startNow ->
            submitted = true
            coordinator.submit(u, name, manual, startNow)
        }
        // 用户直接关掉确认窗才结束页面；提交后若弹出冲突选择框，页面要留着
        dlg.onClosed = { if (!submitted) finish() }
        dlg.show(supportFragmentManager, "prompt")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!submitted) {
            val url = extractUrl(intent)
            if (url.isNotBlank()) {
                val dlg = AddDownloadDialog.newInstance(url)
                val coordinator = AddCoordinator(this) { finish() }
                dlg.setOnSubmit { u, name, manual, startNow ->
                    submitted = true
                    coordinator.submit(u, name, manual, startNow)
                }
                dlg.onClosed = { if (!submitted) finish() }
                dlg.show(supportFragmentManager, "prompt")
            }
        }
    }

    /** VIEW 用 data；SEND / QUICK_ADD 从 EXTRA_TEXT 里抠第一个 http 链接 */
    companion object {
        fun extractUrl(intent: Intent?): String {
            if (intent == null) return ""
            if (intent.action == Intent.ACTION_VIEW) return intent.data?.toString().orEmpty()
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return ""
            return Regex("https?://\\S+").find(text)?.value ?: text
        }
    }
}
