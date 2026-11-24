package com.misterdownloadmanager.mobile

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 检查更新：读本项目 GitHub Release 的最新 tag，与当前版本比较。
 *
 * 版本号是「上游版本 + 迭代号」，例如 0.5.2.1001，按点分段逐段比数字，
 * 不做字符串比较（否则 0.5.2.1001 会被判成大于 0.5.2.999 之外的错误结果）。
 */
object UpdateChecker {

    private const val API = "https://api.github.com/repos/${Changelog.PROJECT_REPO}/releases/latest"
    private const val TIMEOUT_MS = 6000

    /** 把 v1.2.3 之类的 tag 归一化成纯数字段 */
    fun segments(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }

    /** latest 比 current 新则返回 true */
    fun isNewer(latest: String, current: String): Boolean = compare(latest, current) > 0

    /** 逐段比较：最新的段多且前面的段相等时，更长的版本更大 */
    fun compare(latest: String, current: String): Int {
        val a = segments(latest)
        val b = segments(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * 取最新 Release 的 tag（如 `v0.5.2.1002`）。
     * 网络异常、限流、仓库还没有 Release 时返回 null，由界面提示「检查失败」。
     */
    fun fetchLatestTag(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MisterDownloadManager-Android")
            }
            if (conn.responseCode !in 200..299) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body).optString("tag_name").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
