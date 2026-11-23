package com.misterdownloadmanager.mobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.text.TextUtils
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: DownloadsAdapter
    private lateinit var emptyWrap: View
    private lateinit var emptyText: TextView
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerPanel: View
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fab: FloatingActionButton
    private lateinit var selectionBar: View
    private lateinit var recycler: RecyclerView
    private lateinit var largeTitle: TextView
    private lateinit var actionRow: View
    private lateinit var sortPanel: View
    private lateinit var sortOptions: LinearLayout
    private lateinit var tvSortOrder: TextView

    private var searchView: SearchView? = null
    private var cat: Cat = Cat.ALL
    private var sortMode: SortMode = SortMode.TIME
    private var sortAscending = false
    private var sortPanelOpen = false

    private val pickDir: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val label = SaveStore.resolveLabel(this, uri)
            Toast.makeText(this, getString(R.string.save_dir_checking, label), Toast.LENGTH_SHORT).show()
            // 真正创建/写入/删除一次，确认这个目录能用，避免「看起来改了、文件却不在」
            Thread {
                val problem = SaveStore.verify(this, uri)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (problem == null) {
                        val persisted = SaveStore.remember(this, uri)
                        refreshSavePath()
                        Toast.makeText(
                            this, getString(R.string.save_dir_verified, label), Toast.LENGTH_SHORT
                        ).show()
                        if (!persisted) {
                            Toast.makeText(
                                this, R.string.save_dir_no_persist, Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        MaterialAlertDialogBuilder(this)
                            .setTitle(R.string.save_dir_invalid_title)
                            .setMessage(
                                getString(
                                    R.string.save_dir_invalid_message,
                                    problem, label
                                )
                            )
                            .setPositiveButton(R.string.save_dir_reselect) { _, _ -> pickDir.launch(null) }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                }
            }.start()
        }

    private val handler = Handler(Looper.getMainLooper())
    private var tick = 0

    /** 已提示过「未写入所选目录」的任务，避免每 500ms 弹一次 */
    private val saveFailNotified = HashSet<String>()

    private val ticker = object : Runnable {
        override fun run() {
            try {
                // RecyclerView 正在布局/滚动时刷新会抛 IllegalStateException
                if (::recycler.isInitialized && !recycler.isComputingLayout) {
                    adapter.refresh()
                    updateEmpty()
                    notifySaveFailures()
                }
                updateSummary()
                // 抽屉没打开时不刷新菜单标题：setTitle 会重建整个导航列表，属于纯浪费
                if (tick++ % 4 == 0 && drawer.isDrawerOpen(GravityCompat.START)) updateNavCounts()
            } catch (e: Throwable) {
                CrashHandler.recordSoftError(this@MainActivity, "刷新列表", e)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Repo.init(this)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        largeTitle = findViewById(R.id.tvLargeTitle)

        drawer = findViewById(R.id.drawer)
        drawerPanel = findViewById(R.id.drawerPanel)
        recycler = findViewById(R.id.recycler)
        emptyWrap = findViewById(R.id.emptyView)
        emptyText = findViewById(R.id.tvEmpty)
        fab = findViewById(R.id.fab)
        selectionBar = findViewById(R.id.selectionBar)

        // 侧边栏
        toolbar.setNavigationOnClickListener { drawer.openDrawer(GravityCompat.START) }
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                updateNavCounts()
            }
        })
        buildNav()
        applyDrawerInset()
        findViewById<TextView>(R.id.tvNavVersion).text =
            getString(R.string.version_label, BuildConfig.VERSION_NAME)

        // 读取上次排序方式与方向（兼容旧版本的 TIME_DESC / TIME_ASC）
        sortMode = SortMode.fromPref(prefs().getString("sort_mode", null))
        sortAscending = prefs().getBoolean("sort_ascending", false)

        adapter = DownloadsAdapter(
            onPauseResume = { task -> onPauseResume(task) },
            onDelete = { task -> confirmDelete(task) },
            onOpen = { task -> openFile(task) },
            onItemClick = { task ->
                if (task.status == Status.COMPLETED) openFile(task) else showItemMenu(task)
            },
            onItemLongClick = { task ->
                showItemMenu(task, fromLongPress = true)
                true
            },
            onSelectionChanged = { updateSelectionUi() },
        )
        adapter.sortMode = sortMode
        adapter.sortAscending = sortAscending
        adapter.category = cat

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.setHasFixedSize(true)
        recycler.setItemViewCacheSize(16)
        recycler.adapter = adapter
        // 关闭默认的 change 动画：进度每 500ms 更新一次，交叉淡入淡出会造成明显闪烁
        (recycler.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        adapter.refresh()
        attachSwipe()
        setupActionRow()

        // 滚动列表时自动收起排序面板，避免长期占用屏幕
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE && sortPanelOpen) collapseSortPanel()
            }
        })

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener { showAddDialog("") }

        findViewById<View>(R.id.btnSelAll).setOnClickListener { adapter.selectAll() }
        findViewById<View>(R.id.btnSelPause).setOnClickListener { batchPause() }
        findViewById<View>(R.id.btnSelResume).setOnClickListener { batchResume() }
        findViewById<View>(R.id.btnSelShare).setOnClickListener { shareSelected() }
        findViewById<View>(R.id.btnSelDelete).setOnClickListener { deleteSelected() }

        if (Build.VERSION.SDK_INT <= 28) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        updateSelectionUi()
        updateNavCounts()
        selectCatRow(cat)
        setHeaderTitle(getString(Cat.ALL.titleRes))
        handleIntent(intent)

        // 首次布局完成后再检查上次崩溃（此时 header 也已就绪）
        drawerPanel.post {
            checkLastCrash()
            updateNavCounts()
            playListEntrance()
            animateFabIn()
        }
    }

    /** 若上次运行崩溃，提示查看日志（日志已在崩溃时写入剪切板） */
    private fun checkLastCrash() {
        if (!CrashHandler.hasUnseen(this)) return
        try {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.crash_found_title)
                .setMessage(R.string.crash_found_msg)
                .setPositiveButton(R.string.crash_view) { _, _ -> CrashLogActivity.safeStart(this) }
                .setNegativeButton(R.string.crash_ignore) { _, _ -> CrashHandler.markSeen(this) }
                .setOnCancelListener { CrashHandler.markSeen(this) }
                .show()
        } catch (e: Throwable) {
            CrashHandler.recordSoftError(this, "提示崩溃日志", e)
        }
    }

    private fun prefs() = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)

    // ---------- 手势：屏幕左侧 1/3 区域向右滑打开侧边栏 ----------

    private var drawerDownX = 0f
    private var drawerDownY = 0f
    private var drawerSwipeCandidate = false
    private var drawerSwipeClaimed = false

    /**
     * DrawerLayout 默认只在屏幕最左约 20dp 的边缘能拖出抽屉，很难碰到。
     * 这里改为：**任意位置**向右滑都能打开侧边栏——包括按在列表卡片上，
     * 右滑不再触发任何列表项动作。左滑仍然交给列表（选中 / 区间多选）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::drawer.isInitialized) return super.dispatchTouchEvent(ev)

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerSwipeClaimed = false
                drawerDownX = ev.x
                drawerDownY = ev.y
                // 右滑在任何位置（含列表卡片上）都只是打开侧边栏，认手即可，不需要命中测试
                drawerSwipeCandidate = !drawer.isDrawerOpen(GravityCompat.START)
            }

            MotionEvent.ACTION_MOVE -> {
                if (drawerSwipeClaimed) return true
                if (drawerSwipeCandidate) {
                    val dx = ev.x - drawerDownX
                    val dy = ev.y - drawerDownY
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
                    if (dx > slop * 2 && dx > kotlin.math.abs(dy) * 1.6f) {
                        drawerSwipeClaimed = true
                        // 先让列表收到 CANCEL，避免残留按压/滑动状态
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        try {
                            super.dispatchTouchEvent(cancel)
                        } catch (_: Throwable) {
                        }
                        cancel.recycle()
                        drawer.openDrawer(GravityCompat.START)
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawerSwipeClaimed) {
                    drawerSwipeClaimed = false
                    drawerSwipeCandidate = false
                    return true
                }
                drawerSwipeCandidate = false
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---------- 侧边栏 ----------

    /** 一行分类：行视图 + 右侧数量 */
    private class NavRow(val root: View, val count: TextView?)

    private val catRows = LinkedHashMap<Cat, NavRow>()

    /** 侧边栏内容铺满整屏，文字内容避开状态栏 */
    private fun applyDrawerInset() {
        ViewCompat.setOnApplyWindowInsetsListener(drawerPanel) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, top, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(drawerPanel)
    }

    private fun buildNav() {
        val bodyTasks = findViewById<LinearLayout>(R.id.bodyTasks)
        val bodyTypes = findViewById<LinearLayout>(R.id.bodyTypes)
        val bodyMore = findViewById<LinearLayout>(R.id.bodyMore)

        addCatRows(
            bodyTasks, listOf(
                Triple(Cat.ALL, R.drawable.ic_nav_all, R.string.nav_all),
                Triple(Cat.RUNNING, R.drawable.ic_nav_running, R.string.nav_running),
                Triple(Cat.DONE, R.drawable.ic_nav_done, R.string.nav_done),
                Triple(Cat.PAUSED, R.drawable.ic_nav_paused, R.string.nav_paused),
                Triple(Cat.FAILED, R.drawable.ic_nav_failed, R.string.nav_failed),
            )
        )
        addCatRows(
            bodyTypes, listOf(
                Triple(Cat.VIDEO, R.drawable.ic_cat_video, R.string.nav_video),
                Triple(Cat.AUDIO, R.drawable.ic_cat_audio, R.string.nav_audio),
                Triple(Cat.IMAGE, R.drawable.ic_cat_image, R.string.nav_image),
                Triple(Cat.DOC, R.drawable.ic_cat_doc, R.string.nav_doc),
                Triple(Cat.ARCHIVE, R.drawable.ic_cat_archive, R.string.nav_archive),
                Triple(Cat.APK, R.drawable.ic_cat_apk, R.string.nav_apk),
                Triple(Cat.OTHER, R.drawable.ic_cat_other, R.string.nav_other),
            )
        )

        addActionRow(bodyMore, R.drawable.ic_bug, R.string.crash_log) { CrashLogActivity.safeStart(this) }
        addActionRow(bodyMore, R.drawable.ic_info, R.string.nav_about) { showAbout() }

        setupCollapsible(R.id.hdrTasks, R.id.bodyTasks, R.id.chevTasks, "tasks")
        setupCollapsible(R.id.hdrTypes, R.id.bodyTypes, R.id.chevTypes, "types")
        setupCollapsible(R.id.hdrSettings, R.id.bodySettings, R.id.chevSettings, "settings")
        setupCollapsible(R.id.hdrMore, R.id.bodyMore, R.id.chevMore, "more")

        setupSettings()
    }

    private fun addCatRows(container: LinearLayout, items: List<Triple<Cat, Int, Int>>) {
        items.forEach { (c, icon, label) ->
            val row = layoutInflater.inflate(R.layout.item_nav_row, container, false)
            row.findViewById<ImageView>(R.id.navIcon).setImageResource(icon)
            row.findViewById<TextView>(R.id.navTitle).text = getString(label)
            val count = row.findViewById<TextView>(R.id.navCount)
            row.setOnClickListener {
                drawer.closeDrawer(GravityCompat.START)
                applyCat(c)
            }
            container.addView(row)
            catRows[c] = NavRow(row, count)
        }
    }

    private fun addActionRow(container: LinearLayout, icon: Int, label: Int, action: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_nav_row, container, false)
        row.findViewById<ImageView>(R.id.navIcon).setImageResource(icon)
        row.findViewById<TextView>(R.id.navTitle).text = getString(label)
        row.findViewById<TextView>(R.id.navCount).visibility = View.GONE
        row.setOnClickListener {
            drawer.closeDrawer(GravityCompat.START)
            action()
        }
        container.addView(row)
    }

    private fun applyCat(c: Cat) {
        cat = c
        selectCatRow(c)
        adapter.category = c
        setHeaderTitle(getString(c.titleRes))
        clearSearch()
        if (sortPanelOpen) collapseSortPanel()
        updateEmpty()
        recycler.scrollToPosition(0)
        playListEntrance()
    }

    private fun selectCatRow(c: Cat) {
        catRows.forEach { (k, row) -> row.root.isActivated = k == c }
    }

    // ---------- 可折叠分组 ----------

    private fun setupCollapsible(headerId: Int, bodyId: Int, chevronId: Int, key: String) {
        val header = findViewById<View>(headerId)
        val body = findViewById<View>(bodyId)
        val chevron = findViewById<ImageView>(chevronId)
        val expanded = prefs().getBoolean("nav_expanded_$key", true)
        body.visibility = if (expanded) View.VISIBLE else View.GONE
        chevron.rotation = if (expanded) 180f else 0f
        header.setOnClickListener { toggleSection(key, body, chevron) }
    }

    private fun toggleSection(key: String, body: View, chevron: ImageView) {
        val expand = body.visibility != View.VISIBLE
        prefs().edit().putBoolean("nav_expanded_$key", expand).apply()
        chevron.animate()
            .rotation(if (expand) 180f else 0f)
            .setDuration(180)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        animateSection(body, expand)
    }

    /** 展开/收起：只做高度动画，不重建视图，成本极低 */
    private fun animateSection(body: View, expand: Boolean) {
        val parent = body.parent as? ViewGroup ?: return
        val startH = body.height
        body.visibility = View.VISIBLE
        body.measure(
            View.MeasureSpec.makeMeasureSpec(parent.width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val endH = if (expand) body.measuredHeight else 0
        body.layoutParams.height = startH
        body.requestLayout()
        val anim = ValueAnimator.ofInt(startH, endH)
        anim.duration = if (expand) 210L else 170L
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener {
            body.layoutParams.height = it.animatedValue as Int
            body.requestLayout()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                body.requestLayout()
                if (!expand) body.visibility = View.GONE
            }
        })
        anim.start()
    }

    // ---------- 设置（原设置页内容） ----------

    private fun setupSettings() {
        val swWifi = findViewById<MaterialSwitch>(R.id.swWifi)
        val swRetry = findViewById<MaterialSwitch>(R.id.swRetry)
        swWifi.isChecked = prefs().getBoolean("wifi_only", false)
        swRetry.isChecked = prefs().getBoolean("auto_retry", true)
        swWifi.setOnCheckedChangeListener { _, c -> prefs().edit().putBoolean("wifi_only", c).apply() }
        swRetry.setOnCheckedChangeListener { _, c -> prefs().edit().putBoolean("auto_retry", c).apply() }
        // 整行可点：iOS 设置里点文字也能切换
        findViewById<View>(R.id.rowWifi).setOnClickListener { swWifi.toggle() }
        findViewById<View>(R.id.rowRetry).setOnClickListener { swRetry.toggle() }
        findViewById<View>(R.id.rowSeg).setOnClickListener { showSegDialog() }
        findViewById<View>(R.id.rowPath).setOnClickListener { showDirDialog() }
        refreshSeg()
        refreshSavePath()
    }

    private fun refreshSeg() {
        findViewById<TextView>(R.id.tvSeg).text =
            getString(R.string.thread_count_value, prefs().getInt("seg_count", 8))
    }

    private fun showSegDialog() {
        val values = intArrayOf(4, 8, 12, 16)
        val cur = prefs().getInt("seg_count", 8)
        val checked = values.indexOf(cur).let { if (it < 0) 1 else it }
        val labels = values.map { getString(R.string.thread_count_value, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.seg_count)
            .setSingleChoiceItems(labels, checked) { d, which ->
                prefs().edit().putInt("seg_count", values[which]).apply()
                refreshSeg()
                d.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshSavePath() {
        val tv = findViewById<TextView>(R.id.tvSavePath)
        tv.text = SaveStore.destinationLabel(this)
    }

    private fun showDirDialog() {
        val current = SaveStore.label(this)
        val options = arrayOf(
            getString(R.string.save_dir_default),
            getString(R.string.save_dir_custom),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.save_path_label)
            .setMessage(getString(R.string.save_dir_current, current))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        SaveStore.forget(this)
                        refreshSavePath()
                        Toast.makeText(this, R.string.save_dir_reset, Toast.LENGTH_SHORT).show()
                    }
                    1 -> pickDir.launch(null)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- 批量与清理 ----------

    // ---------- 顶部操作行（暂停 / 继续 / 清除 / 排序） ----------

    private fun setupActionRow() {
        actionRow = findViewById(R.id.actionRow)
        sortPanel = findViewById(R.id.sortPanel)
        sortOptions = findViewById(R.id.sortOptions)
        tvSortOrder = findViewById(R.id.tvSortOrder)

        findViewById<View>(R.id.btnPauseAll).setOnClickListener { pauseAll() }
        findViewById<View>(R.id.btnResumeAll).setOnClickListener { resumeAll() }
        findViewById<View>(R.id.btnClearDone).setOnClickListener { clearFinished() }
        findViewById<View>(R.id.btnSort).setOnClickListener { toggleSortPanel() }
        tvSortOrder.setOnClickListener { setSortAscending(!sortAscending) }

        buildSortOptions()
        updateSortChip()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** 排序方式选项：两列网格，选中为蓝底白字 */
    private fun buildSortOptions() {
        sortOptions.removeAllViews()
        val modes = SortMode.values()
        var row: LinearLayout? = null
        modes.forEachIndexed { index, mode ->
            if (index % 2 == 0) {
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = if (index == 0) 0 else dp(8) }
                }
                sortOptions.addView(r)
                row = r
            }
            val tv = TextView(this).apply {
                text = getString(mode.labelRes)
                gravity = Gravity.CENTER
                textSize = 12f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(
                    ContextCompat.getColorStateList(this@MainActivity, R.color.sort_option_text)
                )
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_sort_option)
                isSelected = mode == sortMode
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginStart = dp(4)
                    marginEnd = dp(4)
                }
                setOnClickListener { selectSortMode(mode) }
            }
            row?.addView(tv)
        }
    }

    private fun selectSortMode(mode: SortMode) {
        if (sortMode != mode) {
            sortMode = mode
            prefs().edit().putString("sort_mode", mode.name).apply()
            adapter.sortMode = mode
        }
        updateSortSelection()
        updateEmpty()
    }

    private fun updateSortSelection() {
        val modes = SortMode.values()
        var index = 0
        for (r in 0 until sortOptions.childCount) {
            val row = sortOptions.getChildAt(r) as? LinearLayout ?: continue
            for (i in 0 until row.childCount) {
                val tv = row.getChildAt(i) as? TextView ?: continue
                tv.isSelected = modes.getOrNull(index) == sortMode
                index++
            }
        }
    }

    private fun setSortAscending(asc: Boolean) {
        sortAscending = asc
        prefs().edit().putBoolean("sort_ascending", asc).apply()
        adapter.sortAscending = asc
        updateSortChip()
    }

    private fun updateSortChip() {
        tvSortOrder.text = if (sortAscending) {
            "↑ " + getString(R.string.sort_asc)
        } else {
            "↓ " + getString(R.string.sort_desc)
        }
    }

    private fun toggleSortPanel() {
        if (sortPanelOpen) collapseSortPanel() else expandSortPanel()
    }

    private fun expandSortPanel() {
        if (sortPanelOpen) return
        sortPanelOpen = true
        animateSection(sortPanel, true)
        findViewById<View>(R.id.btnSort).isSelected = true
    }

    private fun collapseSortPanel() {
        if (!sortPanelOpen) return
        sortPanelOpen = false
        animateSection(sortPanel, false)
        findViewById<View>(R.id.btnSort).isSelected = false
    }

    private fun pauseAll() {
        startService(DownloadService.intent(this, DownloadService.ACTION_PAUSE_ALL))
    }

    private fun resumeAll() {
        startService(DownloadService.intent(this, DownloadService.ACTION_RESUME_ALL))
    }

    private fun clearFinished() {
        val toRemove = synchronized(Repo.tasks) {
            Repo.tasks.filter { it.status == Status.COMPLETED || it.status == Status.FAILED }
        }
        if (toRemove.isEmpty()) {
            Toast.makeText(this, R.string.batch_none, Toast.LENGTH_SHORT).show()
            return
        }
        // 顶部常驻按钮容易误触，先确认一次
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_finished)
            .setMessage(getString(R.string.clear_done_confirm, toRemove.size))
            .setPositiveButton(R.string.clear_finished) { _, _ -> doClearFinished(toRemove) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun doClearFinished(toRemove: List<DownloadTask>) {
        toRemove.forEach { Repo.remove(this, it) }
        adapter.refresh()
        updateEmpty()
        updateNavCounts()
        Toast.makeText(this, getString(R.string.cleared_count, toRemove.size), Toast.LENGTH_SHORT).show()
    }

    /** 保存到所选目录失败时提示一次，并告诉用户可以在长按菜单里重试 */
    private fun notifySaveFailures() {
        val fresh = synchronized(Repo.tasks) {
            Repo.tasks.filter {
                it.status == Status.COMPLETED && !it.saveError.isNullOrBlank() && it.id !in saveFailNotified
            }.onEach { saveFailNotified.add(it.id) }
        }
        if (saveFailNotified.size > 200) {
            val alive = synchronized(Repo.tasks) { Repo.tasks.mapTo(HashSet()) { t -> t.id } }
            saveFailNotified.retainAll(alive)
        }
        val t = fresh.firstOrNull() ?: return
        Toast.makeText(
            this,
            getString(
                R.string.save_failed_toast,
                t.fileName.ifBlank { getString(R.string.prop_none) },
                t.saveError ?: ""
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun updateNavCounts() {
        try {
            val tasks = synchronized(Repo.tasks) { Repo.tasks.toList() }
            catRows.forEach { (c, row) ->
                setTextIfChanged(row.count, tasks.count { FileTypes.matches(c, it) }.toString())
            }
            setTextIfChanged(findViewById<TextView>(R.id.tvStatTasks), tasks.size.toString())
            setTextIfChanged(
                findViewById<TextView>(R.id.tvStatDone),
                tasks.count { it.status == Status.COMPLETED }.toString()
            )
            setTextIfChanged(
                findViewById<TextView>(R.id.tvStatSize),
                formatSize(tasks.sumOf { it.downloadedBytes })
            )
        } catch (e: Throwable) {
            CrashHandler.recordSoftError(this, "更新侧边栏统计", e)
        }
    }

    /** 文本没变就不赋值，避免无谓的重新布局 */
    private fun setTextIfChanged(tv: TextView?, text: String) {
        if (tv != null && tv.text.toString() != text) tv.text = text
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.about_title)
            .setMessage(getString(R.string.about_text) + "\n\n" + getString(R.string.version_label, BuildConfig.VERSION_NAME))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ---------- 列表 ----------

    /** iOS 风大标题：内容变化时做一次轻微淡入，避免瞬切 */
    private fun setHeaderTitle(text: CharSequence) {
        if (largeTitle.text == text) return
        largeTitle.text = text
        largeTitle.alpha = 0.3f
        largeTitle.animate().alpha(1f).setDuration(170)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
    }

    /** 列表整体入场：错峰淡入上移，只在新页面/切分类时播放 */
    private fun playListEntrance() {
        try {
            val anim = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.item_in)
            val controller = android.view.animation.LayoutAnimationController(anim, 0.045f)
            recycler.layoutAnimation = controller
            recycler.scheduleLayoutAnimation()
        } catch (_: Throwable) {
            CrashHandler.recordSoftError(this, "列表入场动画", Throwable("layout animation failed"))
        }
    }

    /** FAB 弹性出现 */
    private fun animateFabIn() {
        try {
            fab.scaleX = 0.6f
            fab.scaleY = 0.6f
            fab.alpha = 0f
            fab.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                .start()
        } catch (_: Throwable) {
        }
    }

    private var lastEmptyVisible: Boolean? = null
    private var lastEmptyText: String? = null

    private fun updateEmpty() {
        val empty = adapter.itemCount == 0
        if (lastEmptyVisible != empty) {
            lastEmptyVisible = empty
            emptyWrap.visibility = if (empty) View.VISIBLE else View.GONE
        }
        if (!empty) return
        val text = if (adapter.query.isNotBlank()) {
            getString(R.string.empty_search, adapter.query.trim())
        } else {
            getString(cat.emptyRes)
        }
        if (text != lastEmptyText) {
            lastEmptyText = text
            emptyText.text = text
        }
    }

    private fun updateSummary() {
        val tv = findViewById<TextView>(R.id.tvSummary)
        val downloading = synchronized(Repo.tasks) {
            Repo.tasks.filter { it.status == Status.DOWNLOADING }
        }
        if (downloading.isEmpty()) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            lastSummaryText = null
            return
        }
        val now = System.currentTimeMillis()
        if (lastSpeedTick == 0L) {
            lastSpeedTick = now
            lastSpeedBytes = downloading.sumOf { it.downloadedBytes }
        } else if (now - lastSpeedTick > 900) {
            val cur = downloading.sumOf { it.downloadedBytes }
            totalSpeed = ((cur - lastSpeedBytes) * 1000 / (now - lastSpeedTick)).coerceAtLeast(0)
            lastSpeedTick = now
            lastSpeedBytes = cur
        }
        val text = getString(R.string.summary_running, downloading.size, formatSpeed(totalSpeed))
        if (text != lastSummaryText) {
            lastSummaryText = text
            tv.text = text
        }
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE
    }

    private var lastSummaryText: String? = null

    private var lastSpeedTick = 0L
    private var lastSpeedBytes = 0L
    private var totalSpeed = 0L

    private fun onPauseResume(task: DownloadTask) {
        when (task.status) {
            Status.DOWNLOADING, Status.QUEUED ->
                startService(DownloadService.intent(this, DownloadService.ACTION_PAUSE, DownloadService.EXTRA_ID to task.id))
            Status.PAUSED, Status.FAILED ->
                startService(DownloadService.intent(this, DownloadService.ACTION_RESUME, DownloadService.EXTRA_ID to task.id))
            else -> Unit
        }
    }

    private fun confirmDelete(task: DownloadTask) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete))
            .setMessage(getString(R.string.delete_confirm, task.fileName.ifBlank { task.url }))
            .setPositiveButton(R.string.delete) { _, _ ->
                startService(DownloadService.intent(this, DownloadService.ACTION_DELETE, DownloadService.EXTRA_ID to task.id))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 长按/点击弹出的操作项；拆成独立方法便于单测覆盖 */
    private fun buildItemMenu(task: DownloadTask, fromLongPress: Boolean): Pair<List<String>, List<() -> Unit>> {
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        when (task.status) {
            Status.DOWNLOADING, Status.QUEUED -> {
                items.add(getString(R.string.pause)); actions.add { onPauseResume(task) }
            }
            Status.PAUSED, Status.FAILED -> {
                items.add(getString(R.string.resume)); actions.add { onPauseResume(task) }
                if (task.status == Status.FAILED) {
                    items.add(getString(R.string.copy_error)); actions.add { copyError(task) }
                }
            }
            Status.COMPLETED -> {
                items.add(getString(R.string.open)); actions.add { openFile(task) }
                // 之前写入失败，或旧版本下载的文件还没有「保存位置」记录：允许重新落盘
                val canResave = !task.saveError.isNullOrBlank() ||
                    (task.savedLocation.isBlank() && SaveStore.isCustom(this))
                if (canResave) {
                    items.add(getString(R.string.save_retry)); actions.add { retrySave(task) }
                }
            }
        }
        // 属性取代原来的“分享”入口；分享能力保留在属性页底部
        items.add(getString(R.string.properties)); actions.add { showProperties(task) }
        items.add(getString(R.string.copy_link)); actions.add {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("url", task.url))
            Toast.makeText(this, "已复制链接", Toast.LENGTH_SHORT).show()
        }
        items.add(getString(R.string.open_link)); actions.add {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(task.url)))
            } catch (_: Exception) {
                Toast.makeText(this, "没有浏览器", Toast.LENGTH_SHORT).show()
            }
        }
        if (fromLongPress) {
            items.add(getString(R.string.multi_select)); actions.add { adapter.toggle(task) }
        }
        items.add(getString(R.string.delete)); actions.add { confirmDelete(task) }
        return items to actions
    }

    private fun showItemMenu(task: DownloadTask, fromLongPress: Boolean = false) {
        val (items, actions) = buildItemMenu(task, fromLongPress)
        MaterialAlertDialogBuilder(this)
            .setTitle(task.fileName.ifBlank { task.url })
            .setItems(items.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 复制单个任务的错误信息（失败原因）到剪切板 */
    private fun copyError(task: DownloadTask) {
        val text = buildString {
            append("任务：").append(task.fileName.ifBlank { task.url }).append('\n')
            append("链接：").append(task.url).append('\n')
            append("状态：").append(statusText(task.status)).append('\n')
            append("错误：").append(task.error ?: "未知错误")
        }
        if (CrashHandler.copyToClipboard(this, text))
            Toast.makeText(this, R.string.error_copied, Toast.LENGTH_SHORT).show()
        else
            Toast.makeText(this, R.string.crash_copy_failed, Toast.LENGTH_SHORT).show()
    }

    /** 属性页：所有值点一下即复制，底部保留 分享 / 打开 / 关闭 */
    private fun showProperties(task: DownloadTask) {
        val view = layoutInflater.inflate(R.layout.dialog_properties, null)
        val list = view.findViewById<LinearLayout>(R.id.propList)
        view.findViewById<TextView>(R.id.tvPropSubtitle).text = getString(R.string.properties_hint)

        val sizeText = if (task.totalBytes > 0)
            "${formatSize(task.totalBytes)}（${task.totalBytes} 字节）"
        else getString(R.string.prop_unknown)
        val doneText = "${formatSize(task.downloadedBytes)}（${task.downloadedBytes} 字节）"
        val avgText = if (task.activeMillis > 0 && task.downloadedBytes > 0)
            formatSpeed(task.downloadedBytes * 1000 / task.activeMillis)
        else getString(R.string.prop_none)
        val extra = task.error?.takeIf { it.isNotBlank() }
            ?: task.saveError?.takeIf { it.isNotBlank() }
            ?: if (task.publicSaved) getString(R.string.prop_saved_public) else null
        // 路径展示文件真正落地的位置，而不是应用私有临时目录，否则用户会以为保存路径没生效
        val savedPath = task.savedLocation.takeIf { it.isNotBlank() }
        val shownPath = if (task.status == Status.COMPLETED && savedPath != null)
            savedPath else task.finalFile.absolutePath

        addPropRow(list, getString(R.string.prop_name), task.fileName.ifBlank { getString(R.string.prop_none) })
        addPropRow(list, getString(R.string.prop_url), task.url)
        addPropRow(list, getString(R.string.prop_path), shownPath)
        if (task.status == Status.COMPLETED && savedPath != null && task.finalFile.exists()) {
            addPropRow(list, getString(R.string.prop_temp_path), task.finalFile.absolutePath)
        }
        addPropRow(list, getString(R.string.prop_status), statusText(task.status))
        addPropRow(list, getString(R.string.prop_size), sizeText)
        addPropRow(list, getString(R.string.prop_done), doneText)
        addPropRow(list, getString(R.string.prop_avg_speed), avgText)
        addPropRow(list, getString(R.string.prop_added), dateText(task.addedAt))
        addPropRow(list, getString(R.string.prop_active), durationText(task.activeMillis))
        addPropRow(list, getString(R.string.prop_finished), dateText(task.finishedAt))
        addPropRow(list, getString(R.string.prop_extra), extra ?: getString(R.string.prop_none))

        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()
        view.findViewById<View>(R.id.btnPropShare).setOnClickListener {
            dialog.dismiss()
            shareTask(task)
        }
        view.findViewById<View>(R.id.btnPropOpen).setOnClickListener {
            dialog.dismiss()
            if (task.status == Status.COMPLETED && task.finalFile.exists()) openFile(task)
            else Toast.makeText(this, R.string.prop_not_openable, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btnPropClose).setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            try {
                val dm = resources.displayMetrics
                val width = (dm.widthPixels * 0.92f).toInt()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val height = view.measuredHeight.coerceAtMost((dm.heightPixels * 0.76f).toInt())
                dialog.window?.setLayout(width, height)
            } catch (_: Throwable) {
            }
        }
        dialog.show()
    }

    /** 一行属性：点击整行复制右侧的值 */
    private fun addPropRow(container: LinearLayout, label: String, value: String) {
        val row = layoutInflater.inflate(R.layout.item_prop_row, container, false)
        row.findViewById<TextView>(R.id.tvLabel).text = label
        row.findViewById<TextView>(R.id.tvValue).text = value
        row.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(label, value))
            Toast.makeText(this, getString(R.string.prop_copied, label), Toast.LENGTH_SHORT).show()
        }
        container.addView(row)
    }

    private fun dateText(ts: Long): String =
        if (ts <= 0L) getString(R.string.prop_none)
        else DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(ts)).toString()

    /** 活动时长：12 秒 / 3 分 20 秒 / 1 小时 8 分 */
    private fun durationText(ms: Long): String {
        if (ms <= 0L) return getString(R.string.prop_none)
        val total = ms / 1000
        if (total < 1L) return "不到 1 秒"
        val h = total / 3600
        val m = total % 3600 / 60
        val sec = total % 60
        return when {
            h > 0 -> "${h} 小时 ${m} 分"
            m > 0 -> "${m} 分 ${sec} 秒"
            else -> "${sec} 秒"
        }
    }

    private fun statusText(s: Status): String = getString(
        when (s) {
            Status.QUEUED -> R.string.queued_status
            Status.DOWNLOADING -> R.string.status_downloading
            Status.PAUSED -> R.string.status_paused
            Status.COMPLETED -> R.string.status_completed
            Status.FAILED -> R.string.status_failed
        }
    )

    private fun openFile(task: DownloadTask) {
        val f = task.finalFile
        val mime = DownloadEngine.guessMime(task.fileName)
        if (!f.exists()) {
            // 临时文件被清理时，改用保存到目录后的 content Uri
            val saved = task.savedUri?.let { s ->
                try {
                    Uri.parse(s)
                } catch (_: Exception) {
                    null
                }
            }
            if (saved != null) {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(saved, mime)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    return
                } catch (_: Exception) {
                }
            }
            Toast.makeText(this, "文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "com.misterdownloadmanager.mobile.files", f)
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "没有可打开此文件的应用", Toast.LENGTH_SHORT).show()
        }
    }

    /** 保存到所选目录失败后，手动重试一次（文件仍在应用临时目录里） */
    private fun retrySave(task: DownloadTask) {
        val src = task.finalFile
        if (!src.exists()) {
            Toast.makeText(this, R.string.save_retry_no_file, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.save_retrying, Toast.LENGTH_SHORT).show()
        Thread {
            val saved = SaveStore.save(this, src, task.fileName, DownloadEngine.guessMime(task.fileName))
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                task.publicSaved = saved.ok
                task.savedLocation = saved.location
                task.savedUri = saved.uri
                task.saveError = saved.error
                Repo.save(this)
                adapter.refresh()
                if (saved.ok) {
                    Toast.makeText(
                        this, getString(R.string.save_retry_done, saved.location), Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.save_retry_failed, saved.error ?: ""), Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    private fun shareTask(task: DownloadTask) {
        if (task.status == Status.COMPLETED && task.finalFile.exists()) {
            val uri = FileProvider.getUriForFile(this, "com.misterdownloadmanager.mobile.files", task.finalFile)
            val send = Intent(Intent.ACTION_SEND).setType(DownloadEngine.guessMime(task.fileName))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "分享文件"))
        } else {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, task.url), "分享链接"
                )
            )
        }
    }

    // ---------- 多选 ----------

    private fun updateSelectionUi() {
        val sel = adapter.selectionMode
        selectionBar.visibility = if (sel) View.VISIBLE else View.GONE
        fab.visibility = if (sel) View.GONE else View.VISIBLE
        // 多选时让位给底部批量操作条
        actionRow.visibility = if (sel) View.GONE else View.VISIBLE
        if (sel && sortPanelOpen) collapseSortPanel()
        setHeaderTitle(
            if (sel) {
                getString(R.string.selected_count, adapter.selectedIds.size)
            } else if (adapter.query.isNotBlank()) {
                getString(R.string.search)
            } else {
                getString(cat.titleRes)
            }
        )
        invalidateOptionsMenu()
    }

    private fun batchPause() {
        val targets = adapter.selectedTasks().filter {
            it.status == Status.DOWNLOADING || it.status == Status.QUEUED
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.batch_none, Toast.LENGTH_SHORT).show()
            return
        }
        targets.forEach {
            startService(DownloadService.intent(this, DownloadService.ACTION_PAUSE, DownloadService.EXTRA_ID to it.id))
        }
        Toast.makeText(this, getString(R.string.batch_paused, targets.size), Toast.LENGTH_SHORT).show()
        adapter.clearSelection()
    }

    private fun batchResume() {
        val targets = adapter.selectedTasks().filter {
            it.status == Status.PAUSED || it.status == Status.FAILED || it.status == Status.QUEUED
        }
        if (targets.isEmpty()) {
            Toast.makeText(this, R.string.batch_none, Toast.LENGTH_SHORT).show()
            return
        }
        targets.forEach {
            startService(DownloadService.intent(this, DownloadService.ACTION_RESUME, DownloadService.EXTRA_ID to it.id))
        }
        Toast.makeText(this, getString(R.string.batch_resumed, targets.size), Toast.LENGTH_SHORT).show()
        adapter.clearSelection()
    }

    private fun deleteSelected() {
        val tasks = adapter.selectedTasks()
        if (tasks.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete))
            .setMessage("确定删除所选的 ${tasks.size} 个任务吗？（已完成的文件也会从磁盘删除）")
            .setPositiveButton(R.string.delete) { _, _ ->
                tasks.forEach {
                    startService(DownloadService.intent(this, DownloadService.ACTION_DELETE, DownloadService.EXTRA_ID to it.id))
                }
                adapter.clearSelection()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun shareSelected() {
        val tasks = adapter.selectedTasks()
        if (tasks.isEmpty()) return
        if (tasks.size == 1) {
            shareTask(tasks[0])
            return
        }
        val text = tasks.joinToString("\n") { "${it.fileName.ifBlank { it.url }}\n${it.url}" }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
                "分享"
            )
        )
    }

    // ---------- 滑动操作 ----------

    /** 左滑 = 选中 / 区间多选。右滑由抽屉手势接管，列表项不再有自己的右滑动作 */
    private var swipedTask: DownloadTask? = null

    /** 首次左滑选中时提示一次区间多选用法 */
    private var swipeSelectHinted = false

    /**
     * 各项允许的滑动方向：只保留左滑（选中）。
     * 右滑在 dispatchTouchEvent 里就被识别为「打开侧边栏」，卡片不会再跟着手指滑动。
     */
    private fun swipeDirsFor(task: DownloadTask?): Int =
        if (task == null) 0 else ItemTouchHelper.LEFT

    /**
     * 强制把被滑动的行放回原位。
     *
     * ItemTouchHelper 的 `clearView()` 默认是空实现：滑动结束后卡片仍然带着
     * `translationX` 的位移留在原地（看似消失），而 DiffUtil 认为数据没变不会重绑，
     * 于是出现「滑走后消失了，切个分类又回来」。所以这里既要清掉位移，也要重绑一次。
     */
    private fun rebindSwiped(id: String) {
        // 先同步清掉位移，用户点「取消」时卡片立刻回位，不会留白
        resetSwipeOffset(id)
        recycler.post {
            if (isFinishing || isDestroyed) return@post
            resetSwipeOffset(id)
            val pos = adapter.positionOf(id)
            if (pos != RecyclerView.NO_POSITION) {
                try {
                    adapter.notifyItemChanged(pos)
                } catch (e: Throwable) {
                    CrashHandler.recordSoftError(this@MainActivity, "重绑滑动项", e)
                }
            }
        }
    }

    /** 清掉某一行残留的滑动位移 / 透明度 */
    private fun resetSwipeOffset(id: String) {
        if (!::recycler.isInitialized) return
        val pos = adapter.positionOf(id)
        if (pos == RecyclerView.NO_POSITION) return
        val v = recycler.findViewHolderForAdapterPosition(pos)?.itemView ?: return
        if (v.translationX != 0f) v.translationX = 0f
        if (v.translationY != 0f) v.translationY = 0f
        if (v.alpha != 1f) v.alpha = 1f
    }

    private fun attachSwipe() {
        val density = resources.displayMetrics.density
        val bgSelect = ContextCompat.getColor(this, R.color.ios_blue)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 13f * resources.displayMetrics.scaledDensity
            isFakeBoldText = true
        }

        val callback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int =
                swipeDirsFor(adapter.taskAt(viewHolder.bindingAdapterPosition))

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            /** 滑过卡片宽度 32% 即生效，比默认的 50% 跟手很多 */
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.32f

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder != null) {
                    val pos = viewHolder.bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) swipedTask = adapter.taskAt(pos)
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                val task = swipedTask ?: if (pos != RecyclerView.NO_POSITION) adapter.taskAt(pos) else null
                swipedTask = null
                if (task == null) {
                    adapter.refresh()
                    return
                }
                // ItemTouchHelper 默认的 clearView 是空实现，卡片会带着位移留在原地
                viewHolder.itemView.translationX = 0f
                viewHolder.itemView.translationY = 0f
                viewHolder.itemView.alpha = 1f
                performSwipe(task, direction)
            }

            /** 滑动结束：不论后续是执行还是取消，先把卡片放回原位 */
            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val v = viewHolder.itemView
                if (v.translationX != 0f) v.translationX = 0f
                if (v.translationY != 0f) v.translationY = 0f
                if (v.alpha != 1f) v.alpha = 1f
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val item = viewHolder.itemView
                if (isCurrentlyActive && item.scaleX != 1f) {
                    item.scaleX = 1f
                    item.scaleY = 1f
                }
                // 只有左滑（右→左）有视觉反馈；右滑已被抽屉手势接管
                if (isCurrentlyActive && dX < 0f) {
                    val top = item.top + 5 * density
                    val bottom = item.bottom - 5 * density
                    val r = 14 * density
                    fill.color = bgSelect
                    val left = item.right + dX
                    c.drawRoundRect(RectF(left, top, item.right.toFloat(), bottom), r, r, fill)
                    val text = getString(R.string.swipe_select)
                    c.drawText(text, left + 20 * density, (top + bottom) / 2 + label.textSize / 3, label)
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(recycler)
    }

    /**
     * 处理一次滑动操作：只有左滑（选中 / 区间多选）。
     * 右滑在 [dispatchTouchEvent] 里就被识别为打开侧边栏，不会走到这里。
     *
     * 滑过阈值后 ItemTouchHelper 会把卡片平移出屏幕（250ms 的 RecoverAnimation），
     * 并且**不会**再调 clearView 把位移清零；若这里不复位，这一行就停在屏幕外，
     * 看起来是「项消失了」，切分类重建列表才回来。所以在动画结束之后再复位一次。
     */
    private fun performSwipe(task: DownloadTask, direction: Int) {
        recycler.postDelayed({ rebindSwiped(task.id) }, SWIPE_SETTLE_MS)
        if (direction != ItemTouchHelper.LEFT) return
        swipeSelect(task)
    }

    /**
     * 左滑选择：
     * - 首次（还没进多选）：只选中这一项，并记住为区间起点
     * - 已有起点后再滑另一项：把起点与该项之间的所有项一起选中
     * - 滑的正好是已选中的项：取消它
     */
    private fun swipeSelect(task: DownloadTask) {
        val anchor = adapter.anchorId
        when {
            !adapter.selectionMode || anchor == null -> {
                adapter.selectOnly(task)
                hintSwipeSelect()
            }

            adapter.isSelected(task.id) -> adapter.toggle(task)

            // 起点不在当前列表（切了分类/搜索）：退化成单选
            else -> if (adapter.selectRange(anchor, task.id) == 0) adapter.selectOnly(task)
        }
    }

    /** 首次左滑时说明一下「再滑一项可批量选择」 */
    private fun hintSwipeSelect() {
        if (swipeSelectHinted) return
        swipeSelectHinted = true
        Toast.makeText(this, R.string.swipe_select_hint, Toast.LENGTH_LONG).show()
    }

    // ---------- 菜单 ----------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)

        val iconTint = ContextCompat.getColor(this, R.color.ios_blue)
        menu.findItem(R.id.action_search)?.icon?.setTint(iconTint)

        val searchItem = menu.findItem(R.id.action_search)
        val sv = searchItem.actionView as? SearchView ?: return true
        searchView = sv

        sv.queryHint = getString(R.string.search_hint)
        val searchTint = ContextCompat.getColor(this, R.color.ios_label_2)
        sv.findViewById<ImageView>(androidx.appcompat.R.id.search_button)?.setColorFilter(searchTint)
        sv.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)?.setColorFilter(searchTint)
        sv.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_label))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.ios_label_2))
        }
        sv.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                sv.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.query = newText.orEmpty()
                if (newText.isNullOrBlank() && !adapter.selectionMode) {
                    setHeaderTitle(getString(cat.titleRes))
                }
                updateEmpty()
                return true
            }
        })
        sv.setOnCloseListener {
            adapter.query = ""
            if (!adapter.selectionMode) setHeaderTitle(getString(cat.titleRes))
            updateEmpty()
            false
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // 多选时交给底部批量条，搜索入口让位
        menu.findItem(R.id.action_search)?.isVisible = !adapter.selectionMode
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = super.onOptionsItemSelected(item)

    private fun clearSearch() {
        searchView?.let {
            if (!it.isIconified) {
                it.setQuery("", false)
                it.isIconified = true
            }
        }
        adapter.query = ""
    }

    // ---------- 生命周期 ----------

    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
            adapter.selectionMode -> adapter.clearSelection()
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        updateNavCounts()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------- 其他 ----------

    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == "text/plain") {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                    val url = Regex("https?://\\S+").find(text)?.value ?: text
                    showAddDialog(url)
                }
            }
            ACTION_QUICK_ADD -> {
                val url = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                startService(DownloadService.intent(this, DownloadService.ACTION_ADD, DownloadService.EXTRA_URL to url))
                Toast.makeText(this, "已交给 Mister 下载管理器下载", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun showAddDialog(prefill: String) {
        val dlg = AddDownloadDialog.newInstance(prefill)
        dlg.setOnSubmit { url, name, startNow ->
            startService(
                DownloadService.intent(this, DownloadService.ACTION_ADD).apply {
                    putExtra(DownloadService.EXTRA_URL, url)
                    putExtra(DownloadService.EXTRA_NAME, name)
                    putExtra(DownloadService.EXTRA_START, startNow)
                }
            )
            Toast.makeText(this, if (startNow) "已开始下载" else "已添加到队列", Toast.LENGTH_SHORT).show()
        }
        dlg.show(supportFragmentManager, "add")
    }

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

    private fun formatSpeed(bps: Long): String {
        if (bps <= 0) return "0 B/s"
        val kb = bps / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1 -> String.format(Locale.US, "%.1f MB/s", mb)
            kb >= 1 -> String.format(Locale.US, "%.1f KB/s", kb)
            else -> "$bps B/s"
        }
    }

    companion object {
        const val ACTION_QUICK_ADD = "com.misterdownloadmanager.mobile.QUICK_ADD"

        /** ItemTouchHelper 把卡片滑出屏幕的动画约 250ms，等它结束再复位 */
        const val SWIPE_SETTLE_MS = 320L
    }
}
