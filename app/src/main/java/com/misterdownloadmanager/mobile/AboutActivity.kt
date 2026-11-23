package com.misterdownloadmanager.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * 关于页：应用信息、功能说明、致谢与版本更新说明。
 *
 * 侧边栏头部原来放的图标/名称/版本号整体搬到这里，侧边栏只保留统计与导航，
 * 这样打开抽屉时不会一上来就占掉三行。
 */
class AboutActivity : AppCompatActivity() {

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
     * 版本更新说明：自下而上弹出的底部面板。
     * 列表用代码填充，新增版本只要往 [Changelog.versions] 里加一条。
     */
    private fun showChangelog() {
        val sheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_changelog, null)
        val list = view.findViewById<LinearLayout>(R.id.changeList)

        // 标题下注明基线版本，方便对照上游改动
        view.findViewById<TextView>(R.id.tvChangelogSub).text = getString(
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

        view.findViewById<View>(R.id.btnChangelogClose).setOnClickListener { sheet.dismiss() }

        // 顶部（抓手 + 标题）点一下：半屏 ⇄ 全屏
        view.findViewById<View>(R.id.sheetHeader).setOnClickListener {
            val behavior = sheet.behavior
            behavior.state =
                if (behavior.state == BottomSheetBehavior.STATE_EXPANDED)
                    BottomSheetBehavior.STATE_HALF_EXPANDED
                else BottomSheetBehavior.STATE_EXPANDED
        }

        sheet.setContentView(view)
        // 关掉 fitToContents 才有「半屏」这一档
        sheet.behavior.apply {
            isFitToContents = false
            halfExpandedRatio = 0.62f
            expandedOffset = 0
            skipCollapsed = true
        }
        // 窗口背景透明，圆角才不会被方形白底盖住
        sheet.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        sheet.show()

        // 面板最高 82% 屏高；默认停在约半屏，点顶部拉满
        val full = (resources.displayMetrics.heightPixels * 0.82f).toInt()
        sheet.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, full)
        sheet.behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED
    }
}
