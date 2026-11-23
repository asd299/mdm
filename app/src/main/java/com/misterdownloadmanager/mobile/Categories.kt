package com.misterdownloadmanager.mobile

import androidx.annotation.ColorRes
import androidx.annotation.StringRes

/** 侧边栏分类：状态分类 + 文件类型分类 */
enum class Cat(@StringRes val titleRes: Int, @StringRes val emptyRes: Int) {
    ALL(R.string.nav_all, R.string.empty_all),
    RUNNING(R.string.nav_running, R.string.empty_running),
    DONE(R.string.nav_done, R.string.empty_done),
    PAUSED(R.string.nav_paused, R.string.empty_paused),
    FAILED(R.string.nav_failed, R.string.empty_failed),
    VIDEO(R.string.nav_video, R.string.empty_video),
    AUDIO(R.string.nav_audio, R.string.empty_audio),
    IMAGE(R.string.nav_image, R.string.empty_image),
    DOC(R.string.nav_doc, R.string.empty_doc),
    ARCHIVE(R.string.nav_archive, R.string.empty_archive),
    APK(R.string.nav_apk, R.string.empty_apk),
    OTHER(R.string.nav_other, R.string.empty_other);

    val isStatusCat: Boolean
        get() = this == RUNNING || this == DONE || this == PAUSED || this == FAILED || this == ALL
}

/** 排序方式（方向由列表的「降序 / 顺序」开关单独控制） */
enum class SortMode(@StringRes val labelRes: Int) {
    TIME(R.string.sort_time),
    NAME(R.string.sort_name),
    SIZE(R.string.sort_size),
    STATUS(R.string.sort_status);

    companion object {
        /** 兼容旧版本持久化的 TIME_DESC / TIME_ASC */
        fun fromPref(raw: String?): SortMode = when (raw) {
            "TIME_DESC", "TIME_ASC" -> TIME
            else -> values().firstOrNull { it.name == raw } ?: TIME
        }
    }
}

/** 文件类型识别与分类 */
object FileTypes {

    private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "ts", "m3u8", "rmvb", "3gp", "m4v", "mpg", "mpeg")
    private val AUDIO = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "wma", "opus", "amr", "ape")
    private val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "ico")
    private val DOC = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "epub", "csv", "md", "rtf")
    private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "zst")
    private val APK = setOf("apk", "xapk", "apks", "aab", "ipa", "exe", "dmg", "msi")

    fun extOf(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return if (e.length in 1..5) e else ""
    }

    /** 徽标文字：扩展名大写，最长 4 个字符 */
    fun badgeOf(name: String): String {
        val e = extOf(name)
        return if (e.isEmpty()) "?" else e.uppercase().take(4)
    }

    fun catOf(name: String): Cat {
        val e = extOf(name)
        return when {
            e in VIDEO -> Cat.VIDEO
            e in AUDIO -> Cat.AUDIO
            e in IMAGE -> Cat.IMAGE
            e in DOC -> Cat.DOC
            e in ARCHIVE -> Cat.ARCHIVE
            e in APK -> Cat.APK
            else -> Cat.OTHER
        }
    }

    fun colorOf(cat: Cat): Int = when (cat) {
        Cat.VIDEO -> R.color.cat_video
        Cat.AUDIO -> R.color.cat_audio
        Cat.IMAGE -> R.color.cat_image
        Cat.DOC -> R.color.cat_doc
        Cat.ARCHIVE -> R.color.cat_archive
        Cat.APK -> R.color.cat_apk
        else -> R.color.cat_other
    }

    /** 某分类是否包含该任务 */
    fun matches(cat: Cat, t: DownloadTask): Boolean = when (cat) {
        Cat.ALL -> true
        Cat.RUNNING -> t.status == Status.DOWNLOADING || t.status == Status.QUEUED
        Cat.DONE -> t.status == Status.COMPLETED
        Cat.PAUSED -> t.status == Status.PAUSED
        Cat.FAILED -> t.status == Status.FAILED
        else -> catOf(t.fileName) == cat
    }
}
