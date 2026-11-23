package com.misterdownloadmanager.mobile

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 崩溃 / 错误日志查看页，支持一键复制到剪切板 */
class CrashLogActivity : AppCompatActivity() {

    private var content: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash_log)

        findViewById<MaterialToolbar>(R.id.crashToolbar).apply {
            setNavigationOnClickListener { finish() }
        }

        val tv = findViewById<TextView>(R.id.tvCrashLog)
        val scroll = findViewById<android.widget.ScrollView>(R.id.crashScroll)
        val btnCopy = findViewById<View>(R.id.btnCopy)
        val btnShare = findViewById<View>(R.id.btnShare)
        val btnClear = findViewById<View>(R.id.btnClear)

        content = CrashHandler.readLast(this).orEmpty()
        if (content.isBlank()) {
            tv.text = getString(R.string.crash_empty)
            btnCopy.isEnabled = false
            btnShare.isEnabled = false
            btnCopy.alpha = 0.45f
            btnShare.alpha = 0.45f
        } else {
            tv.text = content
            // textIsSelectable 的 TextView 拿到焦点后会自动把内容滚到中间，
            // 用户进来第一眼看不到日志开头，这里强制回到顶部。
            scroll.post { scroll.scrollTo(0, 0) }
            // 进入页面时顺便再放一次剪切板，方便直接粘贴
            CrashHandler.copyToClipboard(this, content)
        }
        CrashHandler.markSeen(this)

        btnCopy.setOnClickListener {
            if (content.isBlank()) return@setOnClickListener
            if (CrashHandler.copyToClipboard(this, content))
                toast(getString(R.string.crash_copied))
            else toast(getString(R.string.crash_copy_failed))
        }
        btnShare.setOnClickListener {
            if (content.isBlank()) return@setOnClickListener
            try {
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "MDM 崩溃日志")
                            .putExtra(Intent.EXTRA_TEXT, content),
                        getString(R.string.share)
                    )
                )
            } catch (_: Throwable) {
                toast(getString(R.string.crash_copy_failed))
            }
        }
        btnClear.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.crash_clear)
                .setMessage(R.string.crash_clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ ->
                    CrashHandler.clear(this)
                    content = ""
                    tv.text = getString(R.string.crash_empty)
                    toast(getString(R.string.crash_cleared))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    companion object {
        /** 出错时安全打开日志页（Activity 上下文异常也不会连带崩溃） */
        fun safeStart(context: android.content.Context) {
            try {
                context.startActivity(Intent(context, CrashLogActivity::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}
