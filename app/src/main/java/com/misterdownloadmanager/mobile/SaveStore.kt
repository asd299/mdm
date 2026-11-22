package com.misterdownloadmanager.mobile

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException

/**
 * 统一的「保存到用户所选目录」实现。
 *
 * 之前的实现只走 SAF 一条路，失败后静默回退到系统「下载」目录，
 * 用户看到路径已经改了、文件却不在那里，完全无从排查。
 * 这里改为多策略 + 写入校验 + 明确错误：
 *
 * 1. SAF（DocumentsContract.createDocument）——通用，支持 SD 卡与云盘；
 * 2. 若所选目录来自「内部存储」provider，则用 MediaStore + RELATIVE_PATH 落到同一文件夹
 *    （等价于用户看到的真实路径，且不需要额外权限）；
 * 3. 仍然失败才回退系统「下载」目录，并把原因写进任务里展示给用户。
 */
object SaveStore {

    const val PREFS = "mdm_prefs"
    const val KEY_TREE = "custom_dir_uri"
    const val KEY_LABEL = "custom_dir_label"

    private const val AUTHORITY_EXTERNAL = "com.android.externalstorage.documents"

    /** 保存结果：ok 表示是否写进了用户选定的目标位置（未设置自定义目录时 d 默认目录也算 ok） */
    data class Saved(
        val ok: Boolean,
        val location: String,
        val uri: String? = null,
        val error: String? = null,
    )

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun treeUri(context: Context): Uri? = try {
        prefs(context).getString(KEY_TREE, null)?.let { Uri.parse(it) }
    } catch (_: Exception) {
        null
    }

    fun isCustom(context: Context): Boolean = treeUri(context) != null

    /** 侧边栏 / 新建框里展示的目标位置 */
    fun label(context: Context): String {
        val uri = treeUri(context) ?: return context.getString(R.string.save_path_default_name)
        return resolveLabel(context, uri)
    }

    /** 把 tree Uri 转成人能读的位置，如「内部存储/Download/MDM」 */
    fun resolveLabel(context: Context, uri: Uri): String {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (_: Exception) {
            null
        }
        if (docId != null && docId.contains(':')) {
            val vol = docId.substringBefore(':')
            val path = docId.substringAfter(':').trim('/')
            if (vol.isNotEmpty()) {
                val base = volumeLabel(vol)
                return if (path.isEmpty()) base else "$base/$path"
            }
        }
        return queryName(context, documentUri(uri) ?: uri) ?: uri.toString()
    }

    /** tree Uri 不能直接 query，必须换成 document Uri */
    private fun documentUri(tree: Uri): Uri? = try {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        DocumentsContract.buildDocumentUriUsingTree(tree, docId)
    } catch (_: Exception) {
        null
    }

    private fun volumeLabel(volume: String): String = when {
        volume.equals("primary", true) -> "内部存储"
        volume.equals("home", true) -> "文档"
        else -> "外部存储（$volume）"
    }

    /**
     * 选择目录后立即验证：能不能在这里建文件。
     * 返回 null 表示可用；否则返回给用户看的原因。
     */
    fun verify(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        val hasPersisted = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        val docUri = documentUri(uri)
        val supportsCreate = try {
            if (docUri == null) -1 else queryFlags(context, docUri)
        } catch (e: SecurityException) {
            return "授权已失效，请重新选择该目录"
        } catch (_: Exception) {
            -1
        }
        if (hasPersisted && supportsCreate >= 0 &&
            supportsCreate and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
        ) return null

        // 没有持久化权限或 provider 没上报 FLAG_SUPPORTS_CREATE：实际写一次最可靠
        return try {
            val parent = docUri ?: return "无法解析该目录"
            val probe = DocumentsContract.createDocument(
                resolver, parent, "text/plain", "mdm_write_test.txt"
            ) ?: return "无法在该目录创建文件"
            try {
                resolver.openOutputStream(probe)?.use { it.write("ok".toByteArray()) }
                    ?: return "无法写入该目录"
            } finally {
                try {
                    DocumentsContract.deleteDocument(resolver, probe)
                } catch (_: Exception) {
                }
            }
            null
        } catch (e: SecurityException) {
            "授权已失效，请重新选择该目录"
        } catch (e: Exception) {
            "无法写入该目录：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 把完成的文件保存到目标位置。
     * [src] 是应用私有目录里的成品文件，[name] 是期望文件名。
     */
    fun save(context: Context, src: File, name: String, mime: String): Saved {
        if (!src.exists()) {
            return Saved(false, "", null, "源文件不存在")
        }
        val tree = treeUri(context)
        if (tree != null) {
            saveViaSaf(context, tree, src, name, mime)?.let { return it }
            val rel = relativePathOf(tree)
            if (rel != null) {
                saveViaMediaStore(context, src, name, mime, rel)?.let { return it }
            }
            val fallback = saveToDefault(context, src, name, mime)
            val why = "所选目录写入失败（可能授权已失效或该目录不支持写入）"
            return if (fallback != null) {
                fallback.copy(
                    ok = false,
                    error = "$why，已改存到系统下载目录：${fallback.location}"
                )
            } else {
                Saved(false, "", null, "$why，且系统下载目录也写入失败")
            }
        }
        return saveToDefault(context, src, name, mime)
            ?: Saved(false, "", null, "写入系统下载目录失败")
    }

    // ---------- 策略 1：SAF ----------

    private fun saveViaSaf(
        context: Context,
        tree: Uri,
        src: File,
        name: String,
        mime: String,
    ): Saved? {
        val resolver = context.contentResolver
        return try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: return null
            var written = false
            try {
                resolver.openOutputStream(created)?.use { out ->
                    src.inputStream().use { it.copyTo(out, 128 * 1024) }
                    out.flush()
                } ?: throw IOException("无法打开目标文件")
                val size = querySize(context, created)
                if (size in 0 until src.length()) {
                    throw IOException("写入不完整（$size/${src.length()} 字节）")
                }
                written = true
            } finally {
                if (!written) {
                    try {
                        DocumentsContract.deleteDocument(resolver, created)
                    } catch (_: Exception) {
                    }
                }
            }
            val actualName = queryName(context, created) ?: name
            Saved(
                ok = true,
                location = "${resolveLabel(context, tree)}/$actualName",
                uri = created.toString(),
            )
        } catch (e: Exception) {
            CrashHandler.recordSoftError(context, "SAF 保存", e)
            null
        }
    }

    // ---------- 策略 2：MediaStore（内部存储 provider 的等价真实路径） ----------

    /** "primary:Download/MDM" -> "Download/MDM"；只有内部存储（主卷）走 MediaStore */
    fun relativePathOf(tree: Uri): String? {
        if (tree.authority != AUTHORITY_EXTERNAL) return null
        val docId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            return null
        }
        val vol = docId.substringBefore(':', "")
        if (!vol.equals("primary", true)) return null
        return docId.substringAfter(':', "").trim('/').ifEmpty { null }
    }

    @RequiresApi(29)
    private fun saveViaMediaStore(
        context: Context,
        src: File,
        name: String,
        mime: String,
        relative: String,
    ): Saved? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relative)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            CrashHandler.recordSoftError(context, "MediaStore 保存", e)
            null
        } ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out, 128 * 1024) }
                out.flush()
            } ?: throw IOException("无法打开目标文件")
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            val size = querySize(context, uri)
            if (size in 0 until src.length()) throw IOException("写入不完整")
            val actualName = queryName(context, uri) ?: name
            Saved(true, "内部存储/$relative/$actualName", uri.toString())
        } catch (e: Exception) {
            CrashHandler.recordSoftError(context, "MediaStore 保存", e)
            try {
                resolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
            null
        }
    }

    // ---------- 策略 3：系统「下载」目录 ----------

    private fun saveToDefault(context: Context, src: File, name: String, mime: String): Saved? {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (_: Exception) {
                null
            } ?: return null
            return try {
                resolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out, 128 * 1024) }
                } ?: throw IOException("无法写入")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
                Saved(true, "内部存储/Download/${queryName(context, uri) ?: name}", uri.toString())
            } catch (_: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                null
            }
        }
        // Android 9 及以下：需要 WRITE_EXTERNAL_STORAGE
        return try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) return null
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val dst = File(dir, name)
            src.copyTo(dst, overwrite = true)
            Saved(true, dst.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    // ---------- 工具 ----------

    private fun querySize(context: Context, uri: Uri): Long = try {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private fun queryFlags(context: Context, uri: Uri): Int = try {
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getInt(0) else -1 } ?: -1
    } catch (e: SecurityException) {
        throw e
    } catch (_: Exception) {
        -1
    }

    /** 选择目录后把可持久化的读写授权拿到手，并把展示用的名字缓存下来 */
    fun remember(context: Context, uri: Uri): Boolean {
        val granted = try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (_: Exception) {
            // 同一会话内临时授权仍然可用，后续以 verify 的结果为准
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        }
        val label = resolveLabel(context, uri)
        prefs(context).edit()
            .putString(KEY_TREE, uri.toString())
            .putString(KEY_LABEL, label)
            .apply()
        return granted
    }

    fun forget(context: Context) {
        treeUri(context)?.let { uri ->
            try {
                context.contentResolver.releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
        prefs(context).edit().remove(KEY_TREE).remove(KEY_LABEL).apply()
    }

    /** 新建下载框里展示的目标目录（完成后文件真正落地的地方） */
    fun destinationLabel(context: Context): String {
        val uri = treeUri(context) ?: return context.getString(R.string.save_path_default_name)
        val label = resolveLabel(context, uri)
        val ok = try {
            val docUri = documentUri(uri) ?: return label
            queryFlags(context, docUri) // 权限还在就能查到，否则抛 SecurityException
            true
        } catch (_: Exception) {
            false
        }
        return if (ok) label else "$label（授权已失效，请重新选择）"
    }
}
