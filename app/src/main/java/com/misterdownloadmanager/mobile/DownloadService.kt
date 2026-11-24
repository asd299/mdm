package com.misterdownloadmanager.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 前台下载服务：管理任务队列、更新进度通知。
 * 支持动作：添加 / 暂停 / 继续 / 删除。
 */
class DownloadService : Service() {

    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIF_ID = 100

        const val ACTION_ADD = "com.misterdownloadmanager.mobile.ADD"
        const val ACTION_PAUSE = "com.misterdownloadmanager.mobile.PAUSE"
        const val ACTION_RESUME = "com.misterdownloadmanager.mobile.RESUME"
        const val ACTION_DELETE = "com.misterdownloadmanager.mobile.DELETE"
        const val ACTION_PAUSE_ALL = "com.misterdownloadmanager.mobile.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.misterdownloadmanager.mobile.RESUME_ALL"
        /** 设置里切换了「仅限 Wi-Fi」或网络状态变化：重新评估等待中的任务 */
        const val ACTION_RECHECK_WIFI = "com.misterdownloadmanager.mobile.RECHECK_WIFI"
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"
        const val EXTRA_DELETE_FILE = "deleteFile"
        const val EXTRA_NAME = "name"
        const val EXTRA_START = "start"
        const val EXTRA_MANUAL = "manual"
        const val EXTRA_ALLOW_DUP = "allowDup"
        /** 用户确认「仍然继续」：本次忽略仅限 Wi-Fi 限制 */
        const val EXTRA_FORCE = "force"

        fun intent(context: android.content.Context, action: String, extra: Pair<String, String>? = null): Intent {
            val i = Intent(context, DownloadService::class.java).setAction(action)
            if (extra != null) i.putExtra(extra.first, extra.second)
            return i
        }

        /** 删除任务：deleteFile 决定是否连已下载的文件一起删 */
        fun deleteIntent(context: android.content.Context, id: String, deleteFile: Boolean): Intent =
            Intent(context, DownloadService::class.java)
                .setAction(ACTION_DELETE)
                .putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_DELETE_FILE, deleteFile)
    }

    private lateinit var scope: CoroutineScope
    private lateinit var engine: DownloadEngine
    private var ticker: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 正在收尾：通知已被撤掉，ticker 不许再把它画回来 */
    private var stopping = false

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engine = DownloadEngine(this, scope)
        createChannel()
        watchNetwork()
        // 服务（重新）启动时可能已有等待中的任务，网络恰好已恢复
        mainHandler.post { evaluateWifi() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 新的命令意味着又可能有活儿要干，解除上一次的收尾态
        stopping = false
        startAsForeground()
        when (intent?.action) {
            ACTION_ADD -> intent.getStringExtra(EXTRA_URL)?.let {
                addTask(
                    it,
                    intent.getStringExtra(EXTRA_NAME),
                    intent.getBooleanExtra(EXTRA_MANUAL, false),
                    intent.getBooleanExtra(EXTRA_START, true),
                    intent.getBooleanExtra(EXTRA_ALLOW_DUP, false),
                )
            }
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                // 手动暂停 = 取消「仍然继续」的豁免，下次继续时重新按策略判断
                Repo.find(id)?.let { t ->
                    t.ignoreWifi = false
                    if (t.status == Status.WAITING_WIFI) {
                        t.status = Status.PAUSED
                        Repo.save(this)
                    }
                }
                engine.pause(id)
            }
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                Repo.find(id)?.let { t ->
                    startTask(t, force = intent.getBooleanExtra(EXTRA_FORCE, false))
                }
            }
            ACTION_RECHECK_WIFI -> evaluateWifi()
            ACTION_PAUSE_ALL -> {
                engine.pauseAll()
                synchronized(Repo.tasks) {
                    Repo.tasks.forEach {
                        if (it.status == Status.QUEUED || it.status == Status.WAITING_WIFI) {
                            it.status = Status.PAUSED
                            it.ignoreWifi = false
                        }
                    }
                }
                Repo.save(this)
            }
            ACTION_RESUME_ALL -> synchronized(Repo.tasks) {
                Repo.tasks.filter {
                    it.status == Status.PAUSED || it.status == Status.FAILED ||
                        it.status == Status.QUEUED || it.status == Status.WAITING_WIFI
                }.forEach { t -> startTask(t) }
            }
            ACTION_DELETE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                Repo.find(id)?.let { t ->
                    engine.pause(t.id)
                    // 默认删文件；用户取消勾选「删除源文件」时只移除记录
                    val deleteFile = intent.getBooleanExtra(EXTRA_DELETE_FILE, true)
                    Repo.remove(this, t, deleteFile)
                }
                // 删除后立刻刷新通知，不能等 ticker / 收尾检查：
                // 删的是最后一个任务时通知必须当场消失，否则会一直挂着「正在下载」
                if (hasActiveWork()) updateNotification() else stopAndClearNotification()
            }
        }
        startTickerIfNeeded()
        // 队列为空且无进行中任务时自动停止
        scope.launch {
            delay(1500)
            onIdleCheck()
        }
        return START_STICKY
    }

    /**
     * @param allowDup 为 true 时跳过 URL 去重（冲突已在 UI 层由用户确认过，比如覆盖/换名）
     */
    private fun addTask(
        url: String,
        name: String? = null,
        manualName: Boolean = false,
        startNow: Boolean = true,
        allowDup: Boolean = false,
    ) {
        val dir = DownloadEngine.downloadDir(this)
        if (!allowDup && synchronized(Repo.tasks) {
                Repo.tasks.any { it.url == url && it.status != Status.FAILED }
            }
        ) return
        val trimmed = name?.trim().orEmpty()
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = trimmed,
            dir = dir,
            manualName = manualName && trimmed.isNotEmpty(),
        )
        Repo.add(this, task)
        if (startNow) startTask(task)
    }

    /**
     * 统一的启动入口：先过「仅限 Wi-Fi」策略。
     * 不满足条件时任务停在 [Status.WAITING_WIFI]，连上 Wi-Fi 后由网络回调自动续跑。
     */
    private fun startTask(task: DownloadTask, force: Boolean = false) {
        if (task.status == Status.COMPLETED) return
        if (force) task.ignoreWifi = true
        if (WifiPolicy.shouldWait(this, task)) {
            task.status = Status.WAITING_WIFI
            task.error = null
            Repo.save(this)
            onIdleCheck()
            return
        }
        engine.start(task) { onIdleCheck() }
    }

    /** 网络 / 开关变化后重新分配：该等的挂起，能跑的续跑 */
    private fun evaluateWifi() {
        val wifiOnly = WifiPolicy.wifiOnly(this)
        val onWifi = WifiPolicy.onWifi(this)
        if (wifiOnly && !onWifi) {
            // 只挂起真正在跑的；QUEUED 是「加入队列但没开始」，不该被 Wi-Fi 策略带走
            val running = synchronized(Repo.tasks) {
                Repo.tasks.filter { it.status == Status.DOWNLOADING }.toList()
            }
            running.filter { !it.ignoreWifi }.forEach { task ->
                engine.pause(task.id)
                synchronized(Repo.tasks) {
                    Repo.find(task.id)?.let { if (it.status == Status.PAUSED) it.status = Status.WAITING_WIFI }
                }
                Repo.save(this)
            }
        } else {
            val waiting = synchronized(Repo.tasks) {
                Repo.tasks.filter { it.status == Status.WAITING_WIFI }.toList()
            }
            waiting.forEach { startTask(it) }
        }
        onIdleCheck()
    }

    /** 监听默认网络：Wi-Fi 恢复 / 断开都重新评估一次 */
    private fun watchNetwork() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { evaluateWifi() }
            }

            override fun onLost(network: Network) {
                // 切换网络的瞬间 activeNetwork 可能还是旧值，稍等再判断
                mainHandler.postDelayed({ evaluateWifi() }, 600)
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                mainHandler.post { evaluateWifi() }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
        }
    }

    /** 是否还有需要服务继续存活的任务（等待 Wi-Fi 也算：网络恢复时要有人叫醒它们） */
    private fun hasActiveWork(): Boolean = synchronized(Repo.tasks) {
        Repo.tasks.any {
            it.status == Status.DOWNLOADING || it.status == Status.QUEUED || it.status == Status.WAITING_WIFI
        }
    }

    private fun onIdleCheck() {
        if (!hasActiveWork()) stopAndClearNotification()
    }

    /**
     * 没有活儿了：先撤掉通知再退出前台。
     *
     * 之前只调 stopForeground：ticker 还在每 700ms notify 一次，会把刚撤掉的
     * 「正在下载」又画回来，服务销毁后这条常驻通知就留在通知栏里清不掉。
     */
    private fun stopAndClearNotification() {
        if (stopping) return
        stopping = true
        ticker?.cancel()
        ticker = null
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startTickerIfNeeded() {
        if (stopping) return
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                updateNotification()
                delay(700)
            }
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun startAsForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun buildNotification(): Notification {
        // 快照：下载线程会实时改任务状态，直接遍历可变的 Repo.tasks 会偶发并发修改异常，
        // 异常一旦打断 ticker，通知就永远停在旧内容上
        val snapshot = synchronized(Repo.tasks) { Repo.tasks.toList() }
        val downloading = snapshot.filter { it.status == Status.DOWNLOADING }
        val waitingWifi = snapshot.count { it.status == Status.WAITING_WIFI }
        val known = downloading.filter { it.totalBytes > 0 }
        val done = downloading.sumOf { it.downloadedBytes }
        val total = downloading.sumOf { if (it.totalBytes > 0) it.totalBytes else 0 }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                when {
                    downloading.isNotEmpty() -> downloading.joinToString("、") { it.fileName }
                    waitingWifi > 0 -> getString(R.string.notif_waiting_wifi, waitingWifi)
                    else -> getString(R.string.all_paused)
                }
            )
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, downloading.isNotEmpty() && known.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification() {
        if (stopping) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // 已经没有活动任务：撤掉常驻通知，别留下「正在下载」的僵尸条目
        if (!hasActiveWork()) {
            nm.cancel(NOTIF_ID)
            return
        }
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopping = true
        ticker?.cancel()
        ticker = null
        // 服务被系统回收时也不该留下一条「正在下载」的常驻通知
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        networkCallback?.let {
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        engine.pauseAll()
        scope.cancel()
        Repo.save(this)
        super.onDestroy()
    }
}
