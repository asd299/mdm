package com.misterdownloadmanager.mobile

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * 多线程分段下载引擎（受 Mister Download Manager 的分段并发下载思想启发）：
 * - 探测文件大小与 Range 支持，将文件拆成多个分段并发下载；
 * - 每个分段独立记录已下载偏移，支持暂停/续传；
 * - 网络错误自动重试；完成后合并 .part 文件并尽力保存到公共下载目录。
 */
class DownloadEngine(private val context: Context, private val scope: CoroutineScope) {

    private val jobs = mutableMapOf<String, Job>()

    /** 各任务当前打开的 HTTP 连接：暂停时强制断开，让阻塞中的 read 立刻抛错返回 */
    private val conns = mutableMapOf<String, MutableList<HttpURLConnection>>()

    /** 本次会话的下载起始时刻（elapsedRealtime），用于累计活动时长 */
    private val activeSince = mutableMapOf<String, Long>()

    val speedBps = AtomicLong(0)

    /** 出错重试次数：设置里可关闭（关闭后仅重试 3 次） */
    private val maxRetries = context.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        .getBoolean("auto_retry", true).let { if (it) 30 else 3 }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) MisterDownloadManagerMobile/1.0"
        private const val MIN_SEG_SIZE = 4L * 1024 * 1024   // 4MB 以下不分段
        private const val BUF = 64 * 1024

        /** 应用私有下载目录：仅在自选保存目录 / 系统不允许写公共目录时作为暂存区 */
        fun downloadDir(context: Context): String =
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)?.absolutePath
                ?: context.filesDir.absolutePath

        fun guessMime(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return mapOf(
                "apk" to "application/vnd.android.package-archive",
                "mp4" to "video/mp4", "mkv" to "video/x-matroska", "mp3" to "audio/mpeg",
                "flac" to "audio/flac", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
                "png" to "image/png", "gif" to "image/gif", "webp" to "image/webp",
                "pdf" to "application/pdf", "zip" to "application/zip",
                "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
                "txt" to "text/plain", "html" to "text/html", "iso" to "application/octet-stream",
            ).getOrDefault(ext, "application/octet-stream")
        }
    }

    fun isRunning(id: String) = synchronized(jobs) { jobs[id]?.isActive == true }

    fun start(task: DownloadTask, onComplete: () -> Unit) {
        if (isRunning(task.id)) return
        task.status = Status.DOWNLOADING
        task.error = null
        if (task.startedAt <= 0L) task.startedAt = System.currentTimeMillis()
        task.finishedAt = 0L
        synchronized(activeSince) { activeSince[task.id] = SystemClock.elapsedRealtime() }
        Repo.save(context)
        val job = scope.launch(Dispatchers.IO) {
            try {
                runTask(task)
            } catch (e: CancellationException) {
                // 取消可能来自「切到移动网络，任务转入等待 Wi-Fi」：
                // 这种挂起有明确状态，不能被这里改回「已暂停」
                if (task.status != Status.WAITING_WIFI) task.status = Status.PAUSED
                Repo.save(context)
            } catch (e: Exception) {
                task.status = Status.FAILED
                task.error = e.message ?: e.javaClass.simpleName
                Repo.save(context)
            } finally {
                // 累计本次活动时长（暂停、失败、完成都会走到这里）
                val since = synchronized(activeSince) { activeSince.remove(task.id) }
                if (since != null) {
                    task.activeMillis += (SystemClock.elapsedRealtime() - since).coerceAtLeast(0L)
                }
                synchronized(jobs) { jobs.remove(task.id) }
                Repo.save(context)
                onComplete()
            }
        }
        synchronized(jobs) { jobs[task.id] = job }
    }

    fun pause(id: String) {
        // 1) 先断开该任务所有活动连接，阻塞中的 read 会立刻抛 IOException
        synchronized(conns) {
            conns.remove(id)?.forEach { c -> try { c.disconnect() } catch (_: Exception) {} }
        }
        // 2) 取消协程
        synchronized(jobs) { jobs[id]?.cancel() }
        // 3) 立即置为暂停状态，UI 马上反馈，不必等 IO 线程退出
        synchronized(Repo.tasks) {
            Repo.find(id)?.let { t ->
                if (t.status == Status.DOWNLOADING || t.status == Status.QUEUED) {
                    t.status = Status.PAUSED
                    Repo.save(context)
                }
            }
        }
    }

    fun pauseAll() {
        val ids = synchronized(jobs) { jobs.keys.toList() }
        ids.forEach { pause(it) }
    }

    // ---------- 核心流程 ----------

    private suspend fun runTask(task: DownloadTask) = withContext(Dispatchers.IO) {
        if (task.totalBytes < 0 || task.segments.isEmpty()) probe(task)

        // 落点：默认目录直写公共「下载」，下载中的文件也不落在应用数据里
        val target = ensureTarget(task)
        if (target == null) {
            val part = task.partFile
            if (!part.exists()) part.createNewFile()
        } else {
            // 续传前校验：目标文件被外部删除/截断时，已有进度作废，从零重下
            val onDisk = target.size()
            if (onDisk < task.downloadedBytes) {
                target.truncate()
                task.segments.forEach { it.downloaded = 0 }
            }
        }

        val active = task.segments.filter { it.remaining > 0 }
        if (active.isEmpty()) {
            finish(task, target)
            return@withContext
        }
        coroutineScope {
            active.forEach { seg ->
                launch {
                    var retries = 0
                    while (seg.remaining > 0 && isActive) {
                        try {
                            downloadSegment(task, seg, target)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!isActive) throw CancellationException()
                            retries++
                            if (retries > maxRetries) throw IOException("重试次数过多：${e.message}")
                            delay(1200L * minOf(retries, 6))
                        }
                    }
                }
            }
        }
        finish(task, target)
    }

    /** 打开连接下载一个分段 */
    private fun downloadSegment(task: DownloadTask, seg: Segment, target: PublicTarget?) {
        val conn = URL(task.url).openConnection() as HttpURLConnection
        synchronized(conns) { conns.getOrPut(task.id) { mutableListOf() }.add(conn) }
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            if (seg.start > 0 || seg.end >= 0) {
                val rangeEnd = if (seg.end < 0) "" else seg.end.toString()
                conn.setRequestProperty("Range", "bytes=${seg.start + seg.downloaded}-$rangeEnd")
            }
            val code = conn.responseCode
            if (code != 206 && code != 200) throw IOException("HTTP $code")
            if (code == 200 && (seg.start > 0 || seg.downloaded > 0))
                throw IOException("服务器不支持断点续传")

            conn.inputStream.use { input ->
                val writer = if (target != null) target.writer() else FileSegmentWriter(task.partFile)
                writer.use { w ->
                    val buf = ByteArray(BUF)
                    var pos = seg.start + seg.downloaded
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        w.writeAt(pos, buf, 0, n)
                        pos += n
                        seg.downloaded += n
                        speedBps.addAndGet(n.toLong())
                    }
                    // 未知长度：EOF 即结束；已知长度：不足则抛错触发重试
                    if (seg.end >= 0 && seg.downloaded < seg.end - seg.start + 1)
                        throw IOException("连接提前中断")
                }
            }
        } finally {
            synchronized(conns) { conns[task.id]?.remove(conn) }
            conn.disconnect()
        }
    }

    /** 探测大小 / Range 支持 / 文件名，并构建分段 */
    private fun probe(task: DownloadTask) {
        var length = -1L
        var acceptRanges = false
        var disposition: String? = null
        var url = URL(task.url)

        // 先尝试 HEAD（跟随重定向）
        var redirects = 0
        head@ while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc == null || ++redirects > 6) break@head
                    url = URL(url, loc)
                    continue
                }
                if (code in 200..299) {
                    length = conn.contentLengthLong
                    acceptRanges = conn.getHeaderField("Accept-Ranges")?.contains("bytes") == true
                    disposition = conn.getHeaderField("Content-Disposition")
                    conn.disconnect()
                } else conn.disconnect()
            } catch (_: Exception) {
                try { conn.disconnect() } catch (_: Exception) {}
            }
            break@head
        }

        // HEAD 拿不到信息时，用 GET + Range: bytes=0-0 探测
        if (length <= 0) {
            try {
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 15000
                conn.readTimeout = 15000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Range", "bytes=0-0")
                val code = conn.responseCode
                if (code == 206) {
                    val cr = conn.getHeaderField("Content-Range")   // bytes 0-0/12345
                    val total = cr?.substringAfter('/')?.trim()?.toLongOrNull()
                    if (total != null && total > 0) {
                        length = total
                        acceptRanges = true
                    }
                } else if (code == 200) {
                    length = conn.contentLengthLong
                }
                if (disposition == null) disposition = conn.getHeaderField("Content-Disposition")
                conn.disconnect()
            } catch (_: Exception) {
            }
        }

        // 手动指定的文件名优先；只有自动占位名（空 / download_xxxx.bin）才用服务器返回的名字
        if (!task.manualName && (task.fileName.isBlank() || task.fileName.startsWith("download_"))) {
            val existingPublic = if (PublicStore.eligible(context)) PublicStore.existingNames(context) else emptySet()
            val guessed = pickFileName(disposition, url, task, existingPublic)
            if (guessed != task.fileName) {
                // 名字变了，旧的临时文件与分段进度都作废
                if (task.fileName.isNotBlank()) File(task.dir, "${task.fileName}.mdm.part").delete()
                task.publicTarget?.let { PublicStore.deleteToken(context, it) }
                task.publicTarget = null
                task.fileName = guessed
                task.segments.forEach { it.downloaded = 0 }
            }
        }

        task.totalBytes = length
        task.segments.clear()
        if (length > MIN_SEG_SIZE && acceptRanges) {
            val prefs = context.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
            val prefSegs = prefs.getInt("seg_count", 8)
            val count = maxOf(4L, minOf(prefSegs.toLong(), length / MIN_SEG_SIZE)).toInt()
            val chunk = length / count
            var start = 0L
            for (i in 0 until count) {
                val end = if (i == count - 1) length - 1 else start + chunk - 1
                task.segments.add(Segment(i, start, end, 0))
                start = end + 1
            }
        } else {
            // 未知大小或服务器不支持 Range：单分段下载
            task.segments.add(Segment(0, 0, if (length > 0) length - 1 else -1, 0))
        }
        Repo.save(context)
    }

    /**
     * 取得（或创建）本任务的公共落点。
     *
     * 返回 null 表示走私有目录暂存（自选保存目录、Android 9 及以下没有写权限）。
     * 复用时如果系统把文件名改成了 `a (1).mp4`，要同步回任务，否则列表里显示的名字
     * 和磁盘上的文件对不上——这正是之前「文件名不同步」的根因之一。
     */
    private fun ensureTarget(task: DownloadTask): PublicTarget? {
        task.publicTarget?.let { token ->
            val restored = PublicStore.restore(context, token)
            if (restored != null) return restored
            task.publicTarget = null
        }
        if (!PublicStore.eligible(context)) return null
        val created = PublicStore.create(context, task.fileName, guessMime(task.fileName)) ?: return null
        task.publicTarget = created.token
        if (created.displayName.isNotBlank() && created.displayName != task.fileName) {
            task.fileName = created.displayName
        }
        Repo.save(context)
        return created
    }

    /** 完成处理：校验、收尾落盘（公共目录直接解除 pending；暂存文件复制出去后删除） */
    private fun finish(task: DownloadTask, target: PublicTarget?) {
        if (target != null) {
            val actual = target.size()
            if (task.totalBytes > 0 && actual != task.totalBytes)
                throw IOException("文件不完整（$actual/${task.totalBytes}）")
            if (!target.publish()) throw IOException("无法完成写入：${target.location}")
            // Android 9 及以下是公共目录里的普通文件，需要通知媒体库才会出现在「下载」里
            if (target.token.startsWith("/")) {
                MediaScannerConnection.scanFile(context, arrayOf(target.token), null, null)
            }
            if (target.displayName.isNotBlank()) task.fileName = target.displayName
            task.publicSaved = true
            task.savedLocation = target.location
            task.savedUri = target.token
            task.saveError = null
            task.status = Status.COMPLETED
            task.finishedAt = System.currentTimeMillis()
            speedBps.set(0)
            Repo.save(context)
            return
        }

        if (task.totalBytes > 0 && task.downloadedBytes != task.totalBytes)
            throw IOException("文件不完整（${task.downloadedBytes}/${task.totalBytes}）")
        val part = task.partFile
        val final = task.finalFile
        // 保存阶段被暂停/取消时，.part 可能已经改名；此时不能拿新建的空 .part 覆盖成品
        val expected = task.totalBytes
        val partOk = part.exists() && (expected <= 0 || part.length() == expected)
        val finalOk = final.exists() && (expected <= 0 || final.length() == expected)
        if (partOk) {
            if (final.exists()) final.delete()
            if (!part.renameTo(final)) throw IOException("无法重命名 .part 文件")
        } else if (!finalOk) {
            throw IOException("临时文件不完整（下载被中断），请重新下载")
        }
        val saved = SaveStore.save(context, final, task.fileName, guessMime(task.fileName))
        task.publicSaved = saved.ok
        task.savedLocation = saved.location
        task.savedUri = saved.uri
        task.saveError = saved.error
        if (saved.uri == null) {
            MediaScannerConnection.scanFile(context, arrayOf(final.absolutePath), null, null)
        }
        // 已落到用户能看到的位置后，私有目录里的副本必须删掉：
        // 否则同一份文件既在「下载」目录、又计入应用数据，正是用户反馈的「占空间」问题。
        if (saved.ok) {
            final.delete()
            task.publicTarget = null
        }
        task.status = Status.COMPLETED
        task.finishedAt = System.currentTimeMillis()
        speedBps.set(0)
        Repo.save(context)
    }

    // ---------- 工具 ----------

    private fun pickFileName(
        disposition: String?,
        url: URL,
        task: DownloadTask,
        existingPublic: Set<String> = emptySet(),
    ): String {
        var name: String? = null
        disposition?.let {
            val m = Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(it)?.groupValues?.get(1)
            if (!m.isNullOrBlank()) name = URLDecoder.decode(m.trim('"'), "UTF-8")
        }
        if (name.isNullOrBlank()) {
            val path = url.path?.trimEnd('/')
            val last = path?.substringAfterLast('/')
            if (!last.isNullOrBlank()) name = URLDecoder.decode(last, "UTF-8")
        }
        if (name.isNullOrBlank()) name = "download_${UUID.randomUUID().toString().take(6)}.bin"
        return uniqueName(PublicStore.sanitize(name!!), File(task.dir), task.id, existingPublic)
    }

    /** 自动探测出来的名字要避开：同目录下已有的 .part / 成品文件、列表里其它任务、以及公共下载目录里的同名文件 */
    private fun uniqueName(
        name: String,
        dir: File,
        selfId: String?,
        existingPublic: Set<String> = emptySet(),
    ): String {
        var candidate = name
        var i = 1
        while (i < 1000) {
            val partTaken = File(dir, "$candidate.mdm.part").exists()
            val fileTaken = File(dir, candidate).exists()
            val publicTaken = existingPublic.contains(candidate.lowercase())
            val taskTaken = synchronized(Repo.tasks) {
                Repo.tasks.any {
                    it.id != selfId && it.fileName.equals(candidate, true)
                }
            }
            if (!partTaken && !fileTaken && !taskTaken && !publicTaken) return candidate
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)}($i)${name.substring(dot)}" else "$name($i)"
            i++
        }
        return candidate
    }
}
