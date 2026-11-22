package com.misterdownloadmanager.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
        const val EXTRA_URL = "url"
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        const val EXTRA_START = "start"

        fun intent(context: android.content.Context, action: String, extra: Pair<String, String>? = null): Intent {
            val i = Intent(context, DownloadService::class.java).setAction(action)
            if (extra != null) i.putExtra(extra.first, extra.second)
            return i
        }
    }

    private lateinit var scope: CoroutineScope
    private lateinit var engine: DownloadEngine
    private var ticker: Job? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engine = DownloadEngine(this, scope)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        when (intent?.action) {
            ACTION_ADD -> intent.getStringExtra(EXTRA_URL)?.let {
                addTask(it, intent.getStringExtra(EXTRA_NAME), intent.getBooleanExtra(EXTRA_START, true))
            }
            ACTION_PAUSE -> intent.getStringExtra(EXTRA_ID)?.let { engine.pause(it) }
            ACTION_RESUME -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                Repo.find(id)?.let { t -> if (t.status != Status.COMPLETED) engine.start(t) { onIdleCheck() } }
            }
            ACTION_PAUSE_ALL -> {
                engine.pauseAll()
                synchronized(Repo.tasks) { Repo.tasks.forEach { if (it.status == Status.QUEUED) it.status = Status.PAUSED } }
                Repo.save(this)
            }
            ACTION_RESUME_ALL -> synchronized(Repo.tasks) {
                Repo.tasks.filter { it.status == Status.PAUSED || it.status == Status.FAILED || it.status == Status.QUEUED }
                    .forEach { t -> engine.start(t) { onIdleCheck() } }
            }
            ACTION_DELETE -> intent.getStringExtra(EXTRA_ID)?.let { id ->
                Repo.find(id)?.let { t ->
                    engine.pause(t.id)
                    Repo.remove(this, t)
                }
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

    private fun addTask(url: String, name: String? = null, startNow: Boolean = true) {
        val dir = DownloadEngine.downloadDir(this)
        if (synchronized(Repo.tasks) { Repo.tasks.any { it.url == url && it.status != Status.FAILED } }) return
        val task = DownloadTask(
            id = UUID.randomUUID().toString(),
            url = url,
            fileName = name?.takeIf { it.isNotBlank() } ?: "",
            dir = dir,
        )
        Repo.add(this, task)
        if (startNow) engine.start(task) { onIdleCheck() }
    }

    private fun onIdleCheck() {
        val running = Repo.tasks.any { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }
        if (!running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startTickerIfNeeded() {
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
        val downloading = Repo.tasks.filter { it.status == Status.DOWNLOADING }
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
                if (downloading.isEmpty()) getString(R.string.all_paused)
                else downloading.joinToString("、") { it.fileName }
            )
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, downloading.isNotEmpty() && known.isEmpty())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        engine.pauseAll()
        scope.cancel()
        Repo.save(this)
        super.onDestroy()
    }
}
