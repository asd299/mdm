package com.misterdownloadmanager.mobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 「仅限 Wi-Fi」下载策略。
 *
 * 之前只在添加任务的对话框里用一个 Toast 拦住「开始下载」，任务本身不受约束：
 * 已经排队的任务、点「继续」的任务照样走移动网络，列表里也看不出任何区别。
 * 现在统一收敛到这里，服务端与界面共用同一套判断：
 * - 开启开关且当前不在 Wi-Fi → 任务进入 [Status.WAITING_WIFI]，连上 Wi-Fi 自动开始；
 * - 用户明确选择「仍然继续」的任务带 [DownloadTask.ignoreWifi]，不再被挂起。
 */
object WifiPolicy {

    const val PREFS = "mdm_prefs"
    const val KEY_WIFI_ONLY = "wifi_only"

    /** 设置里是否开启了「仅限 Wi-Fi」 */
    fun wifiOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_WIFI_ONLY, false)

    /** 测试用的网络探针：设置后优先使用，避免依赖真实 ConnectivityManager */
    internal var wifiProbe: ((Context) -> Boolean)? = null

    /** 当前是否连接着 Wi-Fi（有网即可，不要求已验证） */
    fun onWifi(context: Context): Boolean = wifiProbe?.invoke(context) ?: try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    } catch (_: Exception) {
        // 拿不到网络信息时不要卡住下载
        true
    }

    /** 这个任务现在是否应该挂着等 Wi-Fi */
    fun shouldWait(context: Context, task: DownloadTask): Boolean =
        !task.ignoreWifi && wifiOnly(context) && !onWifi(context)
}
