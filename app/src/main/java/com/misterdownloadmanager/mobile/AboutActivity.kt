package com.misterdownloadmanager.mobile

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.WindowInsetsCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.appbar.MaterialToolbar

/**
 * 关于页：应用信息、功能说明、致谢与版本更新说明。
 *
 * 侧边栏头部原来放的图标/名称/版本号整体搬到这里，侧边栏只保留统计与导航，
 * 这样打开抽屉时不会一上来就占掉三行。
 */
class AboutActivity : AppCompatActivity() {

    /** 版本说明面板：同一时间只留一个 */
    private var changelogSheet: Dialog? = null

    /** 面板高度动画：连点顶部时先取消上一次，避免两个动画互相拉扯 */
    private var sheetAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<MaterialToolbar>(R.id.aboutToolbar).apply {
            setNavigationOnClickListener { finish() }
        }

        findViewById<TextView>(R.id.tvAboutVersion).text = getString(
            R.string.about_version_row, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )

        bindRow(
            findViewById(R.id.rowChangelog),
            icon = R.drawable.ic_info,
            title = getString(R.string.about_changelog),
            sub = getString(R.string.about_changelog_sub),
        ) { showChangelog() }

        bindRow(
            findViewById(R.id.rowCrashLog),
            icon = R.drawable.ic_bug,
            title = getString(R.string.crash_log),
            sub = getString(R.string.about_crash_log_sub),
        ) { CrashLogActivity.safeStart(this) }

        findViewById<TextView>(R.id.tvUpstreamRepo).text =
            Changelog.upstream.repo
        findViewById<TextView>(R.id.tvUpstreamVersion).text =
            getString(R.string.about_upstream_value, Changelog.upstream.version)
        findViewById<TextView>(R.id.tvUpstreamReleased).text = Changelog.upstream.released
        findViewById<TextView>(R.id.tvUpstreamChecked).text = Changelog.upstream.checkedAt
        findViewById<View>(R.id.rowUpstreamRepo).setOnClickListener { openProject() }
    }

    private fun openProject() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Changelog.PROJECT_URL)))
        } catch (_: Exception) {
            Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindRow(root: View, icon: Int, title: String, sub: String, onClick: () -> Unit) {
        root.findViewById<ImageView>(R.id.rowIcon).setImageResource(icon)
        root.findViewById<TextView>(R.id.rowTitle).text = title
        root.findViewById<TextView>(R.id.rowSub).text = sub
        root.setOnClickListener { onClick() }
    }

    /**
     * 版本更新说明：自下而上的底部面板。
     *
     * 面板自己算高度、钉在窗口底部（半屏 62% / 拉满 82%），不走 BottomSheetBehavior——
     * 之前把窗口高度设成 82% 再让行为去「半屏」，窗口本身居中、面板又只有内容那么高，
     * 面板底部就离屏幕底部差一截，露出下面的半透明遮罩。这里窗口留全屏，只让面板贴底。
     */
    private fun showChangelog() {
        if (changelogSheet?.isShowing == true) return

        val sheet = layoutInflater.inflate(R.layout.sheet_changelog, null)
        val list = sheet.findViewById<LinearLayout>(R.id.changeList)

        // 标题下注明基线版本，方便对照上游改动
        sheet.findViewById<TextView>(R.id.tvChangelogSub).text = getString(
            R.string.changelog_subtitle, Changelog.upstream.version, Changelog.upstream.released
        )

        Changelog.versions.forEachIndexed { index, version ->
            val item = layoutInflater.inflate(R.layout.item_changelog_version, list, false)
            item.findViewById<TextView>(R.id.tvVer).text = "v${version.name}"
            val date = item.findViewById<TextView>(R.id.tvDate)
            if (version.date.isBlank()) date.visibility = View.GONE else date.text = version.date
            item.findViewById<TextView>(R.id.tvCurrent).visibility =
                if (index == 0) View.VISIBLE else View.GONE
            item.findViewById<TextView>(R.id.tvItems).text =
                version.notes.joinToString("\n") { "· $it" }
            list.addView(item)
        }

        val screenH = resources.displayMetrics.heightPixels
        val halfHeight = (screenH * SHEET_HALF_RATIO).toInt()
        val fullHeight = (screenH * SHEET_FULL_RATIO).toInt()

        val dialog = Dialog(this, R.style.Theme_MDM_Sheet)
        // 全屏透明载体：点面板外的空白处关闭
        val root = FrameLayout(this)
        root.id = R.id.changelogSheetHost
        root.setOnClickListener { dismissChangelog(dialog, sheet) }
        val sheetLp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, halfHeight, Gravity.BOTTOM
        )
        root.addView(sheet, sheetLp)
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            sheetAnimator?.cancel()
            sheetAnimator = null
            changelogSheet = null
        }

        // 面板贴住屏幕底，但正文要避开导航栏手势区
        val basePadBottom = sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, basePadBottom + bottom)
            insets
        }

        sheet.findViewById<View>(R.id.btnChangelogClose).setOnClickListener {
            dismissChangelog(dialog, sheet)
        }
        // 顶部（抓手 + 标题）点一下：半屏 ⇄ 拉满
        var expanded = false
        sheet.findViewById<View>(R.id.sheetHeader).setOnClickListener {
            expanded = !expanded
            animateSheetHeight(sheet, sheetLp, if (expanded) fullHeight else halfHeight)
        }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(SHEET_DIM)
        }
        dialog.show()
        changelogSheet = dialog
        ViewCompat.requestApplyInsets(sheet)

        // 自下而上滑入（等第一次布局完成，才有面板高度可用来做起点）
        sheet.doOnLayout {
            if (!dialog.isShowing) return@doOnLayout
            sheet.translationY = sheet.height.toFloat()
            sheet.animate()
                .translationY(0f)
                .setDuration(SHEET_SLIDE_MS)
                .setInterpolator(DecelerateInterpolator(1.4f))
                .start()
        }
    }

    /** 面板高度在半屏 / 拉满之间过渡，底边始终贴着屏幕底 */
    private fun animateSheetHeight(sheet: View, lp: FrameLayout.LayoutParams, target: Int) {
        sheetAnimator?.cancel()
        if (lp.height == target) return
        sheetAnimator = ValueAnimator.ofInt(lp.height, target).apply {
            duration = SHEET_TOGGLE_MS
            interpolator = FastOutSlowInInterpolator()
            addUpdateListener {
                lp.height = it.animatedValue as Int
                sheet.requestLayout()
            }
            start()
        }
    }

    private fun dismissChangelog(dialog: Dialog, sheet: View) {
        if (!dialog.isShowing) return
        // 可能还在滑入 / 换高度，先打断再从当前位置滑下去
        sheetAnimator?.cancel()
        sheet.animate().cancel()
        sheet.animate()
            .translationY(sheet.height.toFloat())
            .setDuration(SHEET_DISMISS_MS)
            .withEndAction { dialog.dismiss() }
            .start()
    }

    private companion object {
        /** 默认停在 62% 屏高，点顶部拉满到 82% */
        const val SHEET_HALF_RATIO = 0.62f
        const val SHEET_FULL_RATIO = 0.82f
        const val SHEET_DIM = 0.35f
        const val SHEET_SLIDE_MS = 280L
        const val SHEET_TOGGLE_MS = 240L
        const val SHEET_DISMISS_MS = 180L
    }
}
