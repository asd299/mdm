package com.misterdownloadmanager.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 任务状态 */
enum class Status { QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED }

/** 一个下载分段（Range 区间） */
class Segment(val index: Int, var start: Long, var end: Long, var downloaded: Long) {
    /** end = -1 表示未知长度、下载到流结束 */
    val remaining: Long
        get() = if (end < 0) Long.MAX_VALUE else (end - start + 1) - downloaded

    fun toJson(): JSONObject =
        JSONObject().put("i", index).put("s", start).put("e", end).put("d", downloaded)

    companion object {
        fun fromJson(o: JSONObject) = Segment(o.getInt("i"), o.getLong("s"), o.getLong("e"), o.getLong("d"))
    }
}

/** 下载任务 */
class DownloadTask(
    val id: String,
    val url: String,
    var fileName: String,
    val dir: String,
    var totalBytes: Long = -1,
    val segments: MutableList<Segment> = mutableListOf(),
    var status: Status = Status.QUEUED,
    var error: String? = null,
    var publicSaved: Boolean = false,
    val addedAt: Long = System.currentTimeMillis(),
    /** 第一次开始下载的时间（0 = 从未开始） */
    var startedAt: Long = 0L,
    /** 下载完成的时间（0 = 未完成） */
    var finishedAt: Long = 0L,
    /** 累计处于下载中的毫秒数（用于算平均速度） */
    var activeMillis: Long = 0L,
    /** 完成后文件真正保存到的位置（用户可读，如「内部存储/Download/MDM/a.mp4」） */
    var savedLocation: String = "",
    /** 保存到 SAF / MediaStore 后的 content Uri，便于后续打开与分享 */
    var savedUri: String? = null,
    /** 保存到所选目录失败的原因（失败时会回退到系统下载目录） */
    var saveError: String? = null,
    /** 文件名是否由用户手动指定：为 true 时探测到的服务器文件名不会覆盖它 */
    var manualName: Boolean = false,
    /**
     * 直写公共「下载」目录时的落点：content:// uri 或绝对路径。
     * 为空表示仍在应用私有目录里下载（自选保存目录、或 Android 9 及以下无写权限）。
     */
    var publicTarget: String? = null,
) {
    val downloadedBytes: Long get() = if (segments.isEmpty()) 0 else segments.sumOf { it.downloaded }

    /** 仅私有目录模式使用；直写公共目录时不产生 .part 文件 */
    val partFile: File get() = File(dir, "$fileName.mdm.part")
    val finalFile: File get() = File(dir, fileName)

    /** 是否正在直写公共下载目录 */
    val writesPublic: Boolean get() = !publicTarget.isNullOrBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("url", url)
        .put("fileName", fileName)
        .put("dir", dir)
        .put("totalBytes", totalBytes)
        .put("status", status.name)
        .put("error", error ?: "")
        .put("publicSaved", publicSaved)
        .put("addedAt", addedAt)
        .put("startedAt", startedAt)
        .put("finishedAt", finishedAt)
        .put("activeMillis", activeMillis)
        .put("savedLocation", savedLocation)
        .put("savedUri", savedUri ?: "")
        .put("saveError", saveError ?: "")
        .put("manualName", manualName)
        .put("publicTarget", publicTarget ?: "")
        .put("segments", JSONArray().apply { segments.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): DownloadTask {
            val segs = mutableListOf<Segment>()
            val arr = o.optJSONArray("segments")
            if (arr != null) for (i in 0 until arr.length()) segs.add(Segment.fromJson(arr.getJSONObject(i)))
            return DownloadTask(
                id = o.getString("id"),
                url = o.getString("url"),
                fileName = o.getString("fileName"),
                dir = o.getString("dir"),
                totalBytes = o.optLong("totalBytes", -1),
                segments = segs,
                status = try { Status.valueOf(o.getString("status")) } catch (_: Exception) { Status.PAUSED },
                error = o.optString("error").ifEmpty { null },
                publicSaved = o.optBoolean("publicSaved", false),
                addedAt = o.optLong("addedAt", System.currentTimeMillis()),
                startedAt = o.optLong("startedAt", 0L),
                finishedAt = o.optLong("finishedAt", 0L),
                activeMillis = o.optLong("activeMillis", 0L),
                savedLocation = o.optString("savedLocation", ""),
                savedUri = o.optString("savedUri", "").ifEmpty { null },
                saveError = o.optString("saveError", "").ifEmpty { null },
                manualName = o.optBoolean("manualName", false),
                publicTarget = o.optString("publicTarget", "").ifEmpty { null },
            )
        }
    }
}

/** 任务列表持久化与共享（UI 与服务共用同一份数据） */
object Repo {
    val lock = Any()
    lateinit var tasks: MutableList<DownloadTask>
        private set

    fun init(context: Context) {
        if (::tasks.isInitialized) return
        tasks = load(context)
    }

    private fun file(context: Context) = File(context.filesDir, "tasks.json")

    fun load(context: Context): MutableList<DownloadTask> {
        return try {
            val f = file(context)
            if (!f.exists()) mutableListOf()
            else {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { DownloadTask.fromJson(arr.getJSONObject(it)) }.toMutableList()
            }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun save(context: Context) {
        synchronized(lock) {
            try {
                val arr = JSONArray()
                synchronized(tasks) { tasks.forEach { arr.put(it.toJson()) } }
                val tmp = File(context.filesDir, "tasks.json.tmp")
                tmp.writeText(arr.toString())
                if (!file(context).delete() && file(context).exists()) return
                tmp.renameTo(file(context))
            } catch (_: Exception) {
            }
        }
    }

    fun find(id: String): DownloadTask? = synchronized(tasks) { tasks.firstOrNull { it.id == id } }

    fun add(context: Context, task: DownloadTask) {
        synchronized(tasks) { tasks.add(0, task) }
        save(context)
    }

    /**
     * 删除任务记录。
     *
     * @param deleteFiles 勾选「删除源文件」时为 true：连同已下载的成品一起删；
     *                    取消勾选时保留已完成的文件，只清掉应用内部的分片与未完成条目。
     */
    fun remove(context: Context, task: DownloadTask, deleteFiles: Boolean = true) {
        synchronized(tasks) { tasks.removeAll { it.id == task.id } }
        // 未完成的分片没有保留价值，任何情况下都清理
        task.partFile.delete()
        // 取消勾选时，已完成任务的成品（私有副本或公共目录里的文件）都要留住
        val keepFile = !deleteFiles && task.status == Status.COMPLETED
        if (!keepFile) {
            task.finalFile.delete()
            // 直写公共目录的任务，删除时把条目一起清掉，避免留下孤儿文件
            PublicStore.deleteToken(context, task.publicTarget)
        }
        save(context)
    }

    /**
     * 清理历史遗留：旧版本把成品同时留在应用私有目录，既占「应用数据」又是重复文件。
     *
     * 只处理「已完成 + 已成功保存到用户可见位置」的任务，并且在删除前确认目标文件真的
     * 还能打开，避免把唯一副本删掉。
     *
     * @return 删除的副本数量
     */
    fun cleanupPrivateCopies(context: Context): Int {
        var removed = 0
        val candidates = synchronized(tasks) {
            tasks.filter {
                it.status == Status.COMPLETED && it.publicSaved && !it.savedUri.isNullOrBlank()
            }
        }
        candidates.forEach { t ->
            val copy = t.finalFile
            if (!copy.exists()) return@forEach
            val token = t.savedUri ?: return@forEach
            val targetOk = try {
                if (token.startsWith("content:")) {
                    context.contentResolver
                        .openFileDescriptor(android.net.Uri.parse(token), "r")?.use { true } ?: false
                } else {
                    File(token).exists()
                }
            } catch (_: Exception) {
                false
            }
            if (targetOk && copy.delete()) removed++
        }
        return removed
    }

    /**
     * 下拉刷新：重新读取磁盘上的任务文件，把内存里还没有的任务补进来。
     *
     * 内存里的任务对象可能正在下载（进度/状态比磁盘上的新），所以同 id 一律以内存为准，
     * 只合并磁盘上新增的条目；这样刷新不会打断正在进行的下载。
     *
     * @return 本次新发现的任务数
     */
    fun reload(context: Context): Int {
        val loaded = load(context)
        var added = 0
        synchronized(tasks) {
            val known = tasks.mapTo(HashSet()) { it.id }
            val fresh = loaded.filter { it.id !in known }
            if (fresh.isNotEmpty()) {
                // 新任务放在前面（与 Repo.add 一致）；列表展示顺序由适配器排序决定
                tasks.addAll(0, fresh)
            }
            added = fresh.size
        }
        if (added > 0) save(context)
        return added
    }
}
