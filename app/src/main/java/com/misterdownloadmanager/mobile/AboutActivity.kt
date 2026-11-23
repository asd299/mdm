package com.misterdownloadmanager.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
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

        findViewById<View>(R.id.btnProject).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Changelog.PROJECT_URL)))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.about_open_failed, Toast.LENGTH_SHORT).show()
            }
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
        sheet.setContentView(view)
        sheet.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()

        // 面板最多占屏幕 78%，版本再多也只在面板内部滚动
        sheet.setOnShowListener {
            val bottomSheet = sheet.findViewById<View>(
                com.google.android.material.R.id.design_bottom_sheet
            ) ?: return@setOnShowListener
            val params = bottomSheet.layoutParams ?: return@setOnShowListener
            params.height = (resources.displayMetrics.heightPixels * 0.78f).toInt()
            bottomSheet.layoutParams = params
        }
    }
}
