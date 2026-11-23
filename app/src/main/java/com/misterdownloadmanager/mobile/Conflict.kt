package com.misterdownloadmanager.mobile

import java.io.File

/** 冲突类型：只撞链接、只撞文件名，或两者都撞 */
enum class ConflictKind { LINK, NAME, BOTH }

/**
 * 一次「添加下载」的冲突情况。
 *
 * @param linkTask     已有同链接的任务（失败的任务不算，用户可能就是想重试）
 * @param nameTask     已有同文件名的任务
 * @param fileOnDisk   保存目录里已经有这个文件名（可能是以前下载留下的）
 */
class Conflict(
    val url: String,
    val fileName: String,
    val linkTask: DownloadTask?,
    val nameTask: DownloadTask?,
    val fileOnDisk: Boolean,
) {
    val kind: ConflictKind
        get() = when {
            linkTask != null && (nameTask != null || fileOnDisk) -> ConflictKind.BOTH
            linkTask != null -> ConflictKind.LINK
            else -> ConflictKind.NAME
        }

    val hasLinkConflict: Boolean get() = linkTask != null
    val hasNameConflict: Boolean get() = nameTask != null || fileOnDisk
}

/** 添加任务前的重复检测（纯逻辑，便于单元测试） */
object ConflictChecker {

    /** URL 归一化：去掉首尾空白与 #fragment，协议/域名大小写无关 */
    fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        val hash = trimmed.indexOf('#')
        return if (hash >= 0) trimmed.substring(0, hash) else trimmed
    }

    /**
     * 查找冲突；没有冲突返回 null。
     * 文件名对不上（同链接不同名）也算冲突，因为链接重复本身要提示。
     */
    fun find(
        url: String,
        fileName: String,
        tasks: List<DownloadTask>,
        dir: File,
    ): Conflict? {
        val target = normalizeUrl(url)
        val linkTask = tasks.firstOrNull {
            it.status != Status.FAILED && normalizeUrl(it.url).equals(target, ignoreCase = true)
        }
        val name = fileName.trim()
        val nameTask = if (name.isEmpty()) null else tasks.firstOrNull {
            it.fileName.equals(name, ignoreCase = true)
        }
        val onDisk = name.isNotEmpty() && File(dir, name).exists()
        if (linkTask == null && nameTask == null && !onDisk) return null
        return Conflict(url, name, linkTask, nameTask, onDisk)
    }

    /**
     * 生成不冲突的编号文件名：`电影.mp4` → `电影(1).mp4` → `电影(2).mp4`…
     * 同时避开任务列表里已占用的名字和目录里已存在的文件。
     */
    fun numberedName(name: String, tasks: List<DownloadTask>, dir: File): String {
        val trimmed = name.trim().ifEmpty { "download.bin" }
        val dot = trimmed.lastIndexOf('.')
        val base = if (dot > 0) trimmed.substring(0, dot) else trimmed
        val ext = if (dot > 0) trimmed.substring(dot) else ""
        var i = 1
        while (i < 1000) {
            val candidate = "$base($i)$ext"
            val taken = tasks.any { it.fileName.equals(candidate, ignoreCase = true) } ||
                File(dir, candidate).exists() ||
                File(dir, "$candidate.mdm.part").exists()
            if (!taken) return candidate
            i++
        }
        return "$base(${System.currentTimeMillis() % 10_000})$ext"
    }
}
