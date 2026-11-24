package com.misterdownloadmanager.mobile

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/** 1DM 风格下载确认窗：链接 / 另存为 / 大小 / 续传状态 / 存储空间 / 仅限Wi-Fi / 出错重试 */
class AddDownloadDialog : BottomSheetDialogFragment() {

    companion object {
        fun newInstance(prefill: String): AddDownloadDialog {
            return AddDownloadDialog().apply { this.prefill = prefill }
        }

        private val main = Handler(Looper.getMainLooper())

        /** 探测结果：文件名、大小（-1 未知）、是否支持断点续传 */
        class Probe(val fileName: String?, val size: Long, val resumable: Boolean)

        fun probe(url: String): Probe {
            var name: String? = null
            var size = -1L
            var resumable = false
            try {
                var u = URL(url)
                var redirects = 0
                loop@ while (true) {
                    val c = u.openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false
                    c.requestMethod = "HEAD"
                    c.connectTimeout = 6000
                    c.readTimeout = 6000
                    c.setRequestProperty("User-Agent", DownloadEngine.USER_AGENT)
                    try {
                        val code = c.responseCode
                        if (code in 300..399) {
                            val loc = c.getHeaderField("Location")
                            c.disconnect()
                            if (loc == null || ++redirects > 6) break@loop
                            u = URL(u, loc)
                            continue
                        }
                        if (code in 200..299) {
                            size = c.contentLengthLong
                            resumable = c.getHeaderField("Accept-Ranges")?.contains("bytes") == true
                            name = parseName(c.getHeaderField("Content-Disposition"))
                        }
                    } catch (_: Exception) {
                    }
                    c.disconnect()
                    break
                }
                if (size <= 0) {
                    try {
                        val c = u.openConnection() as HttpURLConnection
                        c.connectTimeout = 6000
                        c.readTimeout = 6000
                        c.setRequestProperty("User-Agent", DownloadEngine.USER_AGENT)
                        c.setRequestProperty("Range", "bytes=0-0")
                        when (c.responseCode) {
                            206 -> {
                                val cr = c.getHeaderField("Content-Range")
                                if (cr != null && cr.contains("/")) {
                                    val total = cr.substringAfter('/').trim().toLongOrNull()
                                    if (total != null && total > 0) {
                                        size = total
                                        resumable = true
                                    }
                                }
                            }
                            200 -> size = c.contentLengthLong
                        }
                        if (name == null) name = parseName(c.getHeaderField("Content-Disposition"))
                        c.disconnect()
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            if (name == null) {
                try {
                    val path = URL(url).path?.trimEnd('/')
                    val last = path?.substringAfterLast('/')
                    if (!last.isNullOrBlank()) name = URLDecoder.decode(last, "UTF-8")
                } catch (_: Exception) {
                }
            }
            return Probe(name, size, resumable)
        }

        private fun parseName(disposition: String?): String? {
            if (disposition == null) return null
            val m = Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(disposition) ?: return null
            return try {
                URLDecoder.decode(m.groupValues[1].trim('"'), "UTF-8")
            } catch (_: Exception) {
                m.groupValues[1]
            }
        }

        fun fmtSize(b: Long): String {
            if (b <= 0) return "未知"
            val kb = b / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
                mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
                kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
                else -> "$b B"
            }
        }
    }

    private var prefill: String = ""
    private var onStart2: ((url: String, name: String, manualName: Boolean, startNow: Boolean) -> Unit)? = null

    /** 探测到的服务器文件名：用户没改过就视为自动名，允许后续被真实名字替换 */
    private var probeName: String? = null

    /** 窗口关闭（无论是否提交）时回调，供悬浮 PromptActivity 结束自身 */
    var onClosed: (() -> Unit)? = null

    /** 统一使用 iOS 风底部面板主题（白底、28dp 顶部圆角、蓝色主按钮） */
    override fun getTheme(): Int = R.style.ThemeOverlay_MDM_BottomSheetDialog

    override fun onStart() {
        super.onStart()
        try {
            dialog?.window?.setDimAmount(0.42f)
        } catch (_: Throwable) {
        }
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        onClosed?.invoke()
    }

    /** 提交回调：url、文件名（可空）、是否用户手动命名、是否立即开始 */
    fun setOnSubmit(cb: (url: String, name: String, manualName: Boolean, startNow: Boolean) -> Unit) {
        onStart2 = cb
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val v = inflater.inflate(R.layout.dialog_add, container, false)
        val ctx = requireContext()
        val et = v.findViewById<EditText>(R.id.etUrl)
        val etName = v.findViewById<EditText>(R.id.etName)
        val tvResume = v.findViewById<TextView>(R.id.tvResume)
        val tvSize = v.findViewById<TextView>(R.id.tvSize)
        val tvStorage = v.findViewById<TextView>(R.id.tvStorage)
        val tvPath = v.findViewById<TextView>(R.id.tvPath)
        val cbWifi = v.findViewById<MaterialSwitch>(R.id.cbWifi)
        val cbRetry = v.findViewById<MaterialSwitch>(R.id.cbRetry)
        et.setText(prefill)

        val prefs = ctx.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        cbWifi.isChecked = prefs.getBoolean("wifi_only", false)
        cbRetry.isChecked = prefs.getBoolean("auto_retry", true)

        // 剪贴板检测
        if (prefill.isBlank()) {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                val url = Regex("https?://\\S+").find(text)?.value
                if (url != null) {
                    et.setText(url)
                    prefill = url
                }
            } catch (_: Exception) {
            }
        }

        // 保存路径与存储空间：显示真正落地目录（用户设置的自定义目录或系统下载目录）
        val dir = DownloadEngine.downloadDir(ctx)
        tvPath.text = SaveStore.destinationLabel(ctx)
        try {
            val stat = StatFs(dir)
            val free = stat.availableBytes
            tvStorage.text = getString(R.string.storage_free, fmtSize(free) + " / " + fmtSize(stat.totalBytes))
        } catch (_: Exception) {
            tvStorage.visibility = View.GONE
        }

        // 后台探测大小 / 续传支持 / 文件名
        val urlNow = prefill
        if (urlNow.isNotBlank()) {
            Thread {
                val p = probe(urlNow)
                main.post {
                    if (!isAdded) return@post
                    if (p.size > 0) {
                        tvSize.text = "${fmtSize(p.size)}（${p.size} 字节）"
                        tvResume.text = getString(if (p.resumable) R.string.resume_yes else R.string.resume_no)
                        tvResume.visibility = View.VISIBLE
                    } else {
                        tvSize.text = "大小：未知"
                    }
                    if (p.fileName != null && etName.text.isNullOrBlank()) {
                        probeName = p.fileName
                        etName.setText(p.fileName)
                    }
                }
            }.start()
        }

        cbWifi.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(WifiPolicy.KEY_WIFI_ONLY, checked).apply()
            // 与设置里的开关共用同一份偏好，改完立刻让服务重新评估等待中的任务
            ctx.startService(DownloadService.intent(ctx, DownloadService.ACTION_RECHECK_WIFI))
        }
        cbRetry.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("auto_retry", checked).apply()
        }

        val send = { startNow: Boolean ->
            val url = et.text?.toString()?.trim() ?: ""
            if (!Regex("^https?://\\S+$").matches(url)) {
                Toast.makeText(ctx, R.string.invalid_url, Toast.LENGTH_SHORT).show()
            } else {
                // 开了仅限 Wi-Fi 又不在 Wi-Fi：照常建任务，让它以「等待 Wi-Fi」挂起，
                // 不再用一个 Toast 把用户挡在门外（任务就此消失，反而更困惑）
                val waitWifi = startNow && cbWifi.isChecked && !isOnWifi(ctx)
                val name = etName.text?.toString()?.trim() ?: ""
                // 用户改过（或自己输入）的名字才锁定，自动填入的探测名仍允许被服务端真实名字替换
                val manual = name.isNotEmpty() && name != probeName
                onStart2?.invoke(url, name, manual, startNow)
                dismiss()
                if (waitWifi) Toast.makeText(ctx, R.string.add_waiting_wifi, Toast.LENGTH_LONG).show()
            }
        }
        // 「开始下载」在标题右侧（属性页关闭按钮的位置），底部只剩「取消 / 添加」
        v.findViewById<View>(R.id.btnStart).setOnClickListener { send(true) }
        v.findViewById<View>(R.id.btnAdd).setOnClickListener { send(false) }
        v.findViewById<View>(R.id.btnCancel).setOnClickListener { dismiss() }
        return v
    }

    private fun isOnWifi(ctx: Context): Boolean = WifiPolicy.onWifi(ctx)
}
