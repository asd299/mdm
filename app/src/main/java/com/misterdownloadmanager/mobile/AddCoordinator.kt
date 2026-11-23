package com.misterdownloadmanager.mobile

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.net.URLDecoder

/**
 * 添加任务的统一入口：先做重复检测，冲突时弹窗让用户选「跳过 / 换个文件名 / 覆盖下载」，
 * 没有冲突才真正入队。主界面与悬浮确认窗共用同一套逻辑。
 */
class AddCoordinator(
    private val activity: AppCompatActivity,
    /** 任意结束路径（入队 / 跳过 / 取消）都会回调一次，供悬浮窗关闭自身 */
    private val onDone: (() -> Unit)? = null,
) {

    fun submit(url: String, name: String, manual: Boolean, startNow: Boolean) {
        val dir = File(DownloadEngine.downloadDir(activity))
        val tasks = synchronized(Repo.tasks) { Repo.tasks.toList() }
        val conflict = ConflictChecker.find(url, name, tasks, dir)
        if (conflict == null) {
            enqueue(url, name, manual, startNow)
        } else {
            showConflict(conflict, url, manual, startNow, dir)
        }
    }

    private fun showConflict(
        conflict: Conflict,
        url: String,
        manual: Boolean,
        startNow: Boolean,
        dir: File,
    ) {
        val lines = mutableListOf<String>()
        conflict.linkTask?.let { t ->
            lines += activity.getString(
                R.string.conflict_link,
                statusText(t.status),
                t.fileName.ifBlank { t.url },
            )
        }
        conflict.nameTask?.let { t ->
            lines += activity.getString(R.string.conflict_name, t.fileName, statusText(t.status))
        }
        if (conflict.fileOnDisk) {
            lines += activity.getString(R.string.conflict_file_on_disk, conflict.fileName)
        }

        val options = arrayOf(
            activity.getString(R.string.conflict_skip),
            activity.getString(R.string.conflict_rename),
            activity.getString(R.string.conflict_overwrite),
        )

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.conflict_title)
            .setMessage(lines.joinToString("\n"))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        Toast.makeText(activity, R.string.conflict_skipped, Toast.LENGTH_SHORT).show()
                        onDone?.invoke()
                    }

                    1 -> {
                        val tasks = synchronized(Repo.tasks) { Repo.tasks.toList() }
                        val base = conflict.fileName.ifBlank { guessName(url) }
                        val fresh = ConflictChecker.numberedName(base, tasks, dir)
                        enqueue(url, fresh, manual = true, startNow = startNow)
                    }

                    else -> {
                        // 覆盖：先删掉旧任务（连同它的 .part / 成品），再按原文件名重新下载
                        val old = conflict.linkTask ?: conflict.nameTask
                        if (old != null) {
                            // 已经保存到系统下载目录 / SAF 目录的副本，尽力一起删掉
                            old.savedUri?.let { uri ->
                                try {
                                    activity.contentResolver.delete(android.net.Uri.parse(uri), null, null)
                                } catch (_: Exception) {
                                }
                            }
                            activity.startService(
                                DownloadService.intent(
                                    activity,
                                    DownloadService.ACTION_DELETE,
                                    DownloadService.EXTRA_ID to old.id,
                                )
                            )
                        }
                        enqueue(url, conflict.fileName, manual, startNow)
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onDone?.invoke() }
            .setOnCancelListener { onDone?.invoke() }
            .show()
    }

    private fun enqueue(url: String, name: String, manual: Boolean, startNow: Boolean) {
        activity.startService(
            DownloadService.intent(activity, DownloadService.ACTION_ADD).apply {
                putExtra(DownloadService.EXTRA_URL, url)
                putExtra(DownloadService.EXTRA_NAME, name)
                putExtra(DownloadService.EXTRA_MANUAL, manual)
                putExtra(DownloadService.EXTRA_START, startNow)
                // 冲突已经在上面处理过了，这里不再做 URL 去重
                putExtra(DownloadService.EXTRA_ALLOW_DUP, true)
            }
        )
        Toast.makeText(
            activity,
            if (startNow) R.string.add_started else R.string.add_queued,
            Toast.LENGTH_SHORT,
        ).show()
        onDone?.invoke()
    }

    private fun statusText(status: Status): String = activity.getString(
        when (status) {
            Status.QUEUED -> R.string.queued_status
            Status.DOWNLOADING -> R.string.status_downloading
            Status.PAUSED -> R.string.status_paused
            Status.COMPLETED -> R.string.status_completed
            Status.FAILED -> R.string.status_failed
        }
    )

    private fun guessName(url: String): String {
        val last = try {
            java.net.URL(url).path?.trimEnd('/')?.substringAfterLast('/')
        } catch (_: Exception) {
            null
        }
        val decoded = last?.takeIf { it.isNotBlank() }?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (_: Exception) {
                it
            }
        }
        return decoded ?: "download.bin"
    }
}
