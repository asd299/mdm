package com.misterdownloadmanager.mobile

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 分段写入器：把一段字节写到文件的指定偏移。
 *
 * 抽象出这一层，是为了让「下载中的临时文件」也能直接落在公共下载目录：
 * - 旧任务/自定义目录：写应用私有目录里的 .mdm.part（[FileSegmentWriter]）；
 * - 默认目录：Android 10+ 写 MediaStore 的 pending 条目（[UriSegmentWriter]）。
 */
interface SegmentWriter : Closeable {
    fun writeAt(position: Long, buf: ByteArray, offset: Int, length: Int)
}

class FileSegmentWriter(file: File) : SegmentWriter {
    private val raf = RandomAccessFile(file, "rw")
    override fun writeAt(position: Long, buf: ByteArray, offset: Int, length: Int) {
        raf.seek(position)
        raf.write(buf, offset, length)
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Exception) {
        }
    }
}

/** MediaStore 里的文件没有 File 句柄，用 fd 的 channel 做随机写 */
class UriSegmentWriter(context: Context, uri: Uri) : SegmentWriter {
    private val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
        ?: throw IOException("无法打开下载文件")
    private val channel: FileChannel = FileOutputStream(pfd.fileDescriptor).channel

    override fun writeAt(position: Long, buf: ByteArray, offset: Int, length: Int) {
        channel.position(position)
        val bb = ByteBuffer.wrap(buf, offset, length)
        while (bb.hasRemaining()) channel.write(bb)
    }

    override fun close() {
        try {
            channel.close()
        } catch (_: Exception) {
        }
        try {
            pfd.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * 一个任务在公共「下载」目录里的落点。
 *
 * 目标是「下载开始就写在用户能看到的地方」——这样应用管理里的「用户数据」只有几 KB 的
 * 任务列表，卸载应用也不会连带删掉已下载的文件。
 */
abstract class PublicTarget {
    /** 给用户看的位置，如「内部存储/Download/a.mp4」 */
    abstract val location: String

    /** 真正的文件名（可能与请求名不同：系统重名会自动加后缀） */
    abstract val displayName: String

    /** 持久化到任务里的标识：content:// uri 或绝对路径 */
    abstract val token: String

    abstract fun writer(): SegmentWriter
    abstract fun size(): Long

    /** 清空内容（文件被外部删改后重新开始时用） */
    abstract fun truncate()

    /** 下载完成后收尾（MediaStore 需要解除 IS_PENDING） */
    abstract fun publish(): Boolean

    /** 清理：删除这个落点（失败任务、改名重建时用） */
    abstract fun delete()
}

/** Android 10+：MediaStore 条目 */
class MediaTarget(
    private val context: Context,
    private val uri: Uri,
    override val displayName: String,
) : PublicTarget() {
    override val location: String get() = "内部存储/Download/$displayName"
    override val token: String get() = uri.toString()

    override fun writer(): SegmentWriter = UriSegmentWriter(context, uri)

    override fun size(): Long = try {
        // 用 fd 的 statSize 而不是 query SIZE：写入过程中 MediaStore 的 SIZE 列可能还没刷新，
        // 拿它做完整性校验会把刚下完的文件判成「不完整」。
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
    } catch (_: Exception) {
        0L
    }

    override fun publish(): Boolean = try {
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
            null, null,
        ) >= 0
    } catch (_: Exception) {
        false
    }

    override fun truncate() {
        try {
            context.contentResolver.openFileDescriptor(uri, "rwt")?.use { }
        } catch (_: Exception) {
        }
    }

    override fun delete() {
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }
}

/** Android 9 及以下：直接写公共「下载」目录里的普通文件 */
class FileTarget(private val file: File) : PublicTarget() {
    override val location: String get() = file.absolutePath
    override val displayName: String get() = file.name
    override val token: String get() = file.absolutePath

    override fun writer(): SegmentWriter = FileSegmentWriter(file)

    override fun size(): Long = if (file.exists()) file.length() else 0L

    override fun truncate() {
        try {
            FileOutputStream(file).use { }
        } catch (_: Exception) {
        }
    }

    override fun publish(): Boolean = true

    override fun delete() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }
}

/**
 * 下载落点的选择与恢复。
 *
 * 只有满足以下条件才直写公共目录：用户没有自选保存目录（SAF），且系统允许写入
 * （Android 10+ 走 MediaStore 无需权限，Android 9 及以下需要 WRITE_EXTERNAL_STORAGE）。
 * 其余情况回退到应用私有目录，完成后复制出去并立刻删除私有副本。
 */
object PublicStore {

    private const val MIME_FALLBACK = "application/octet-stream"

    /** 纯逻辑版本，方便单测：SDK 级别 + 是否自选目录 + 是否有写权限 */
    fun shouldWritePublic(sdk: Int, customDir: Boolean, hasWritePermission: Boolean): Boolean =
        !customDir && (sdk >= 29 || hasWritePermission)

    fun eligible(context: Context): Boolean = shouldWritePublic(
        Build.VERSION.SDK_INT,
        SaveStore.isCustom(context),
        ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED,
    )

    /** 公共「下载」目录里已有的文件名（小写），用于添加任务时判断重名 */
    fun existingNames(context: Context): Set<String> {
        if (Build.VERSION.SDK_INT >= 29) {
            return try {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    null, null, null,
                )?.use { c ->
                    val out = HashSet<String>()
                    val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    while (c.moveToNext()) {
                        if (idx >= 0 && !c.isNull(idx)) c.getString(idx)?.let { out.add(it.lowercase()) }
                    }
                    out
                } ?: emptySet()
            } catch (_: Exception) {
                emptySet()
            }
        }
        val dir = publicDir() ?: return emptySet()
        return try {
            dir.listFiles()?.mapTo(HashSet()) { it.name.lowercase() } ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun publicDir(): File? = try {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    } catch (_: Exception) {
        null
    }

    /**
     * 新建一个落点。[name] 是期望文件名，返回的对象里带着系统实际采用的名称。
     * 返回 null 表示公共目录不可用，调用方应回退到私有目录。
     */
    fun create(context: Context, name: String, mime: String): PublicTarget? {
        val safe = sanitize(name)
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mime.ifBlank { MIME_FALLBACK })
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                CrashHandler.recordSoftError(context, "创建公共下载文件", e)
                null
            } ?: return null
            // 系统遇到同名文件会自动改名（a.mp4 → a (1).mp4），以查询结果为准
            val actual = SaveStore.queryName(context, uri) ?: safe
            return MediaTarget(context, uri, actual)
        }
        val dir = publicDir() ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.canWrite()) return null
        val file = uniqueFile(dir, safe)
        return try {
            if (!file.exists() && !file.createNewFile()) return null
            FileTarget(file)
        } catch (_: Exception) {
            null
        }
    }

    /** 恢复一个已存在的落点（续传时用）；不可用返回 null，让调用方重建 */
    fun restore(context: Context, token: String): PublicTarget? = try {
        if (token.startsWith("content:")) {
            val uri = Uri.parse(token)
            val name = SaveStore.queryName(context, uri)
            if (name == null) null else MediaTarget(context, uri, name)
        } else {
            val f = File(token)
            if (f.parentFile?.exists() == true) FileTarget(f) else null
        }
    } catch (_: Exception) {
        null
    }

    /** 删除任务时清理落点：未完成的条目不该留在公共目录里 */
    fun deleteToken(context: Context, token: String?) {
        if (token.isNullOrBlank()) return
        restore(context, token)?.delete()
    }

    /**
     * 按文件名删除公共「下载」目录里的文件。
     * 「覆盖下载」要靠它把旧文件真正删掉，否则系统会改名成 `a (1).mp4`。
     */
    fun deleteByName(context: Context, name: String) {
        if (name.isBlank()) return
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                        "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                    arrayOf(name, "${Environment.DIRECTORY_DOWNLOADS}%"),
                )
            } catch (_: Exception) {
            }
            return
        }
        try {
            File(publicDir() ?: return, name).delete()
        } catch (_: Exception) {
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        var i = 1
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        while (candidate.exists() && i < 1000) {
            candidate = File(dir, "$base($i)$ext")
            i++
        }
        return candidate
    }

    fun sanitize(name: String): String {
        var n = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim(' ', '.')
        if (n.isBlank()) n = "download.bin"
        return n
    }
}
