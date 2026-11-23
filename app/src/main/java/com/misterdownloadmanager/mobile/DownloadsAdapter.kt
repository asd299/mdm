package com.misterdownloadmanager.mobile

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.util.Locale

/**
 * 下载列表适配器。
 * 使用 DiffUtil + payload 做增量刷新：进度变化只重绘进度/状态区域，
 * 不重建整行、不触发默认的 change 动画，避免 500ms 定时刷新带来的闪烁与卡顿。
 */
class DownloadsAdapter(
    private val onPauseResume: (DownloadTask) -> Unit,
    private val onDelete: (DownloadTask) -> Unit,
    private val onOpen: (DownloadTask) -> Unit,
    private val onItemClick: (DownloadTask) -> Unit = {},
    private val onItemLongClick: (DownloadTask) -> Boolean = { false },
    private val onSelectionChanged: () -> Unit = {},
) : RecyclerView.Adapter<DownloadsAdapter.VH>() {

    companion object {
        private const val PAYLOAD_PROGRESS = 1
    }

    private val items: MutableList<DownloadTask> = mutableListOf()

    /**
     * 上一次真正绘制到界面上的内容快照。
     * Repo 里的 DownloadTask 是可变对象，DiffUtil 拿新旧列表比较时两边其实是同一个引用，
     * 直接比较字段永远相等（进度/状态变化全被丢弃）。因此必须自己留一份旧值来算差异。
     */
    private data class ItemState(
        val fileName: String,
        val status: Status,
        val totalBytes: Long,
        val downloadedBytes: Long,
        val error: String?,
        val publicSaved: Boolean,
        val savedLocation: String,
        val saveError: String?,
    )

    private val lastStates = HashMap<String, ItemState>()

    private fun stateOf(t: DownloadTask) = ItemState(
        t.fileName, t.status, t.totalBytes, t.downloadedBytes, t.error, t.publicSaved,
        t.savedLocation, t.saveError
    )
    val selectedIds = LinkedHashSet<String>()
    var selectionMode = false
        private set

    /** 区间选择的起点：第一次左滑选中的那一项 */
    var anchorId: String? = null
        private set

    /** 「任务」维度筛选（全部 / 正在下载 / 已完成 / 已暂停 / 失败） */
    var statusCat: Cat = Cat.ALL
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    /** 「按类型分类」维度筛选（全部 / 视频 / 音乐 / …） */
    var typeCat: Cat = Cat.ALL
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    /** 搜索关键词 */
    var query: String = ""
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    /** 排序方式 */
    var sortMode: SortMode = SortMode.TIME
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    /** true = 顺序（升序），false = 降序；作用于所有排序方式 */
    var sortAscending: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                refresh()
            }
        }

    // ---------- 刷新 ----------

    fun refresh() {
        updateSpeeds()

        val snapshot = synchronized(Repo.tasks) { Repo.tasks.toList() }
        val q = query.trim()
        val filtered = snapshot.filter { t ->
            FileTypes.matches(statusCat, typeCat, t) &&
                (q.isEmpty() || t.fileName.contains(q, true) || t.url.contains(q, true))
        }
        val base = when (sortMode) {
            SortMode.TIME -> filtered.sortedBy { it.addedAt }
            SortMode.NAME -> filtered.sortedBy { it.fileName.lowercase() }
            SortMode.SIZE -> filtered.sortedBy { if (it.totalBytes > 0) it.totalBytes else it.downloadedBytes }
            SortMode.STATUS -> filtered.sortedBy { statusOrder(it.status) }
        }
        val sorted = if (sortAscending) base else base.reversed()

        val alive = snapshot.mapTo(HashSet()) { it.id }
        selectedIds.retainAll(alive)
        if (anchorId != null && !selectedIds.contains(anchorId!!)) anchorId = selectedIds.firstOrNull()
        val hadSelection = selectionMode
        if (selectedIds.isEmpty()) selectionMode = false
        if (!selectionMode) anchorId = null
        val selectionChanged = hadSelection != selectionMode

        val old = items.toList()
        val oldStates = old.map { lastStates[it.id] }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = old.size
            override fun getNewListSize(): Int = sorted.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean =
                old[oldPos].id == sorted[newPos].id

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean =
                oldStates[oldPos] == stateOf(sorted[newPos])

            override fun getChangePayload(oldPos: Int, newPos: Int): Any? {
                val before = oldStates[oldPos] ?: return null
                val after = stateOf(sorted[newPos])
                // 文件名/保存位置变化会影响标题、徽标、副标题，必须整行重绑；
                // 只有进度类字段变化时才走轻量 payload
                return if (before.fileName == after.fileName &&
                    before.savedLocation == after.savedLocation
                ) PAYLOAD_PROGRESS else null
            }
        }, true)

        items.clear()
        items.addAll(sorted)
        // 记录本次绘制的内容，供下一轮比较
        sorted.forEach { lastStates[it.id] = stateOf(it) }
        lastStates.keys.retainAll(alive)

        if (selectionChanged) {
            notifyDataSetChanged()
            onSelectionChanged()
        } else {
            diff.dispatchUpdatesTo(this)
        }
    }

    private fun statusOrder(s: Status): Int = when (s) {
        Status.DOWNLOADING -> 0
        Status.QUEUED -> 1
        Status.PAUSED -> 2
        Status.FAILED -> 3
        Status.COMPLETED -> 4
    }

    fun filteredTasks(): List<DownloadTask> = items.toList()

    fun taskAt(position: Int): DownloadTask? = items.getOrNull(position)

    /** 按 id 找当前列表里的位置（滑动后重绑用，位置可能已变化） */
    fun positionOf(id: String): Int = items.indexOfFirst { it.id == id }

    // ---------- 多选 ----------

    fun toggle(task: DownloadTask) {
        if (selectedIds.contains(task.id)) selectedIds.remove(task.id) else selectedIds.add(task.id)
        selectionMode = selectedIds.isNotEmpty()
        if (!selectionMode) {
            anchorId = null
        } else if (anchorId == null || !selectedIds.contains(anchorId!!)) {
            anchorId = selectedIds.first()
        }
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun isSelected(id: String): Boolean = selectedIds.contains(id)

    /** 只选中这一项，并把它记为区间起点 */
    fun selectOnly(task: DownloadTask) {
        selectedIds.clear()
        selectedIds.add(task.id)
        anchorId = task.id
        selectionMode = true
        notifyDataSetChanged()
        onSelectionChanged()
    }

    /**
     * 从区间起点到 [toId] 全选（含两端）。
     * @return 本次区间覆盖的行数，找不到返回 0
     */
    fun selectRange(fromId: String, toId: String): Int {
        val a = items.indexOfFirst { it.id == fromId }
        val b = items.indexOfFirst { it.id == toId }
        if (a < 0 || b < 0) return 0
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        for (i in lo..hi) selectedIds.add(items[i].id)
        anchorId = fromId
        selectionMode = true
        notifyDataSetChanged()
        onSelectionChanged()
        return hi - lo + 1
    }

    fun selectAll() {
        selectedIds.addAll(items.map { it.id })
        selectionMode = selectedIds.isNotEmpty()
        if (anchorId == null || !selectedIds.contains(anchorId!!)) anchorId = selectedIds.firstOrNull()
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        selectionMode = false
        anchorId = null
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedTasks(): List<DownloadTask> =
        synchronized(Repo.tasks) { Repo.tasks.filter { selectedIds.contains(it.id) } }

    // ---------- 视图 ----------

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: MaterialCardView = v as MaterialCardView
        val iconBox: View = v.findViewById(R.id.iconBox)
        val ext: TextView = v.findViewById(R.id.tvExt)
        val name: TextView = v.findViewById(R.id.tvName)
        val url: TextView = v.findViewById(R.id.tvUrl)
        val progress: ProgressBar = v.findViewById(R.id.progress)
        val info: TextView = v.findViewById(R.id.tvInfo)
        val status: TextView = v.findViewById(R.id.tvStatus)
        val ops: View = v.findViewById(R.id.ops)
        val action: ImageButton = v.findViewById(R.id.btnAction)
        val del: ImageButton = v.findViewById(R.id.btnDelete)

        /** 当前绑定的任务：监听器只注册一次，靠这个引用取数据 */
        var bound: DownloadTask? = null

        init {
            action.setOnClickListener {
                bound?.let { t -> if (t.status == Status.COMPLETED) onOpen(t) else onPauseResume(t) }
            }
            del.setOnClickListener { bound?.let { onDelete(it) } }
            itemView.setOnClickListener {
                bound?.let { t -> if (selectionMode) toggle(t) else onItemClick(t) }
            }
            itemView.setOnLongClickListener {
                val t = bound ?: return@setOnLongClickListener false
                if (selectionMode) {
                    toggle(t); true
                } else {
                    onItemLongClick(t)
                }
            }
            attachPress(itemView)
        }
    }

    /** iOS 风按压反馈：只改 RenderNode 缩放，不触发布局，成本极低 */
    private fun attachPress(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.972f).scaleY(0.972f)
                        .setDuration(90)
                        .setInterpolator(DecelerateInterpolator())
                        .start()

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(230)
                        .setInterpolator(OvershootInterpolator(1.15f))
                        .start()
            }
            false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        bindHeader(holder, t)
        resetViewState(holder)
        if (holder.itemView.scaleX != 1f) {
            holder.itemView.scaleX = 1f
            holder.itemView.scaleY = 1f
        }
        bindProgress(holder, t)
    }

    /** 标题 / 类型徽标 / 链接：文件名可能在探测后变化，轻量绑定也要同步这一块 */
    private fun bindHeader(holder: VH, t: DownloadTask) {
        val ctx = holder.itemView.context

        // 多选高亮
        if (selectionMode && selectedIds.contains(t.id)) {
            holder.card.strokeWidth = 3
            holder.card.strokeColor = ContextCompat.getColor(ctx, R.color.mdm_primary)
        } else {
            holder.card.strokeWidth = 0
        }
        holder.ops.visibility = if (selectionMode) View.GONE else View.VISIBLE

        val cat = if (t.fileName.isBlank()) Cat.OTHER else FileTypes.catOf(t.fileName)
        holder.iconBox.background = badgeDrawable(ctx, ContextCompat.getColor(ctx, FileTypes.colorOf(cat)))
        val badge = if (t.fileName.isBlank()) "…" else FileTypes.badgeOf(t.fileName)
        if (holder.ext.text.toString() != badge) holder.ext.text = badge
        val label = t.fileName.ifBlank { ctx.getString(R.string.status_waiting) }
        if (holder.name.text.toString() != label) holder.name.text = label
        if (holder.url.text.toString() != t.url) holder.url.text = t.url
        holder.bound = t
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        val t = items[position]
        bindHeader(holder, t)
        resetViewState(holder)
        bindProgress(holder, t)
    }

    /** 清掉滑动后残留的位移，避免这一行停在屏幕外（看似消失） */
    private fun resetViewState(holder: VH) {
        val v = holder.itemView
        if (v.translationX != 0f) v.translationX = 0f
        if (v.translationY != 0f) v.translationY = 0f
        if (v.alpha != 1f) v.alpha = 1f
    }

    /** 进度 / 状态 / 操作按钮：进度变化时只走这里，开销远小于整行重绑 */
    private fun bindProgress(holder: VH, t: DownloadTask) {
        val ctx = holder.itemView.context
        val total = t.totalBytes
        val done = t.downloadedBytes
        val indeterminate = total <= 0 && t.status == Status.DOWNLOADING

        if (holder.progress.isIndeterminate != indeterminate) {
            holder.progress.isIndeterminate = indeterminate
        }

        if (indeterminate) {
            holder.info.text = "${formatSize(done)} · ${formatSpeed(speedOf(t))}"
        } else {
            val pct = if (total > 0) ((done * 100) / total).toInt() else 0
            // 带动画设置进度，500ms 一次的刷新也能连续顺滑
            holder.progress.setProgress(pct, true)
            holder.info.text = if (total > 0) {
                var info = "${formatSize(done)} / ${formatSize(total)} · $pct%"
                if (t.status == Status.DOWNLOADING) {
                    val speed = speedOf(t)
                    // 速度还没算出来（刚起手 / 停顿）时不显示 0 B/s，避免误判成卡死
                    if (speed > 0) info += " · ${formatSpeed(speed)} · " + getStringEta(ctx, (total - done) / speed)
                }
                info
            } else {
                formatSize(done)
            }
        }

        when (t.status) {
            Status.QUEUED -> {
                holder.status.setText(R.string.queued_status)
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.status_paused))
            }
            Status.DOWNLOADING -> {
                val pct = if (total > 0) " ${((done * 100) / total).toInt()}%" else ""
                holder.status.text = "${ctx.getString(R.string.status_downloading)}$pct"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.status_running))
            }
            Status.PAUSED -> {
                holder.status.setText(R.string.status_paused)
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.status_paused))
            }
            Status.COMPLETED -> {
                val saveFailed = !t.saveError.isNullOrBlank()
                holder.status.setText(
                    if (saveFailed) R.string.save_failed_short else R.string.status_completed
                )
                holder.status.setTextColor(
                    ContextCompat.getColor(
                        ctx, if (saveFailed) R.color.status_failed else R.color.status_done
                    )
                )
                when {
                    t.savedLocation.isNotBlank() ->
                        holder.info.text = ctx.getString(R.string.saved_to_path, t.savedLocation)
                    t.publicSaved -> holder.info.text = ctx.getString(R.string.saved_to)
                }
            }
            Status.FAILED -> {
                holder.status.text = "${ctx.getString(R.string.status_failed)}${t.error?.let { "：$it" } ?: ""}"
                holder.status.setTextColor(ContextCompat.getColor(ctx, R.color.status_failed))
            }
        }

        when (t.status) {
            Status.DOWNLOADING, Status.QUEUED -> {
                holder.action.setImageResource(R.drawable.ic_pause)
                holder.action.imageTintList = ContextCompat.getColorStateList(ctx, R.color.mdm_accent)
                holder.action.contentDescription = ctx.getString(R.string.pause)
            }
            Status.PAUSED, Status.FAILED -> {
                holder.action.setImageResource(R.drawable.ic_resume)
                holder.action.imageTintList = ContextCompat.getColorStateList(ctx, R.color.status_running)
                holder.action.contentDescription = ctx.getString(R.string.resume)
            }
            Status.COMPLETED -> {
                holder.action.setImageResource(R.drawable.ic_open)
                holder.action.imageTintList = ContextCompat.getColorStateList(ctx, R.color.ios_blue)
                holder.action.contentDescription = ctx.getString(R.string.open)
            }
        }
    }

    // ---------- 速度与格式化 ----------

    private val badgeCache = HashMap<Int, GradientDrawable>()

    private fun badgeDrawable(ctx: Context, color: Int): GradientDrawable =
        badgeCache.getOrPut(color) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 12 * ctx.resources.displayMetrics.density
                setColor(color)
            }
        }

    private val speedMap = HashMap<String, Long>()
    private val lastBytes = HashMap<String, Long>()
    private var lastTick = System.currentTimeMillis()

    /** 每 500ms 统一算一次速度表，而不是每绑一个条目就遍历全部任务 */
    private fun updateSpeeds() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastTick
        if (elapsed < 400) return
        val snapshot = synchronized(Repo.tasks) { Repo.tasks.toList() }
        synchronized(speedMap) {
            snapshot.forEach { task ->
                val prev = lastBytes[task.id] ?: task.downloadedBytes
                val delta = task.downloadedBytes - prev
                if (task.status == Status.DOWNLOADING && delta >= 0) {
                    speedMap[task.id] = delta * 1000 / elapsed
                } else {
                    speedMap.remove(task.id)
                }
                lastBytes[task.id] = task.downloadedBytes
            }
            val alive = snapshot.mapTo(HashSet()) { it.id }
            lastBytes.keys.retainAll(alive)
            speedMap.keys.retainAll(alive)
        }
        lastTick = now
    }

    private fun speedOf(t: DownloadTask): Long = speedMap[t.id] ?: 0

    private fun formatSize(b: Long): String {
        if (b <= 0) return "0 B"
        val kb = b / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            kb >= 1 -> String.format(Locale.US, "%.1f KB", kb)
            else -> "$b B"
        }
    }

    private fun formatSpeed(bps: Long): String = "${formatSize(bps)}/s"

    private fun getStringEta(ctx: Context, sec: Long): String {
        val s = if (sec <= 0) 0 else sec
        val txt = when {
            s >= 3600 -> String.format(Locale.US, "%dh%02dm", s / 3600, (s % 3600) / 60)
            s >= 60 -> String.format(Locale.US, "%dm%02ds", s / 60, s % 60)
            else -> "${s}s"
        }
        return ctx.getString(R.string.eta_left, txt)
    }
}
