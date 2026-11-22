package com.misterdownloadmanager.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 悬浮下载确认窗：浏览器点击下载后唤起，在浏览器上方直接弹出确认对话框，
 * 点「开始/添加」后立即执行下载并关闭，不打开主界面（参考 1DM+ 行为）。
 */
class PromptActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val url = Regex("https?://\\S+").find(text)?.value ?: text
        if (url.isBlank()) {
            finish()
            return
        }

        val dlg = AddDownloadDialog.newInstance(url)
        dlg.setOnSubmit { u, name, startNow ->
            startService(
                DownloadService.intent(this, DownloadService.ACTION_ADD).apply {
                    putExtra(DownloadService.EXTRA_URL, u)
                    putExtra(DownloadService.EXTRA_NAME, name)
                    putExtra(DownloadService.EXTRA_START, startNow)
                }
            )
            Toast.makeText(this, if (startNow) "已开始下载" else "已添加到队列", Toast.LENGTH_SHORT).show()
            finish()
        }
        dlg.onClosed = { finish() }
        dlg.show(supportFragmentManager, "prompt")
    }
}
