package com.misterdownloadmanager.mobile

/**
 * 版本更新记录与上游基线。
 *
 * 版本号规则：`<上游版本号>.<迭代号>`，迭代号从 1001 起、每发一版 +1。
 * 上游 v0.5.2 → 本应用 0.5.2.1001、0.5.2.1002 …
 * 上游升到新版本后迭代号重置为 1001（例如 0.6.0.1001），这样版本号本身就能看出
 * 「对应上游哪个版本、在这之上迭代了多少次」。
 *
 * 0.5.2.1001 是本项目的第一个版本，版本说明从这里开始记录。
 */
object Changelog {

    data class Version(val name: String, val date: String, val notes: List<String>)

    /** 参考的上游项目：地址、基线版本、上游发布日期、最近一次核对日期 */
    data class Upstream(
        val repo: String,
        val url: String,
        val version: String,
        val released: String,
        val checkedAt: String,
    )

    val upstream = Upstream(
        repo = "anefzaoui/misterdownloadmanager",
        url = "https://github.com/anefzaoui/misterdownloadmanager",
        version = "0.5.2",
        released = "2026-11-09",
        checkedAt = "2026-11-24",
    )

    val PROJECT_URL: String = upstream.url

    /** 迭代号起点，每次发版在此基础上 +1 */
    const val ITERATION_BASE = 1001

    val versions: List<Version> = listOf(
        Version(
            "0.5.2.1001", "2026-11-24", listOf(
                "项目首个版本：基于上游 v0.5.2（2026-11-09），版本号 0.5.2.1001（上游版本号 + 迭代号 .1001）",
                "侧边栏顶部重做为概览卡：图标、名称、版本胶囊、上游基线与三列统计，整卡可点进关于页",
                "关于页新增「上游项目」卡片：仓库地址、参考版本、上游发布日期与本版核对日期",
                "下载文件直写系统「下载」目录，不再计入应用数据；卸载应用也不会删掉已下载文件",
                "拦截其它应用里的 http(s) 链接，点链接直接弹出本应用的下载确认窗",
                "服务器返回真实文件名后列表实时同步；手动命名的任务保持锁定",
                "添加任务前检测重复链接、重复文件名与目录同名文件，可选跳过 / 编号 / 覆盖",
                "搜索框收起输入法即结束搜索：没有关键字退出，已有文字保留结果",
                "下拉刷新、双层筛选（任务状态 × 文件类型）、批量操作与 iOS 圆润卡片界面",
                "侧边栏改为跟手滑出：任意位置右滑都能拖，过半或轻甩吸附，打开后左滑 / 点遮罩关闭",
                "列表项单击就地展开操作面板（与「排序」同一交互），长按直接进入多选",
                "左滑选择的动画重做：卡片跟手缩放、蓝色提示渐显，松手平滑回弹，不再飞出屏幕",
                "关于页 / 崩溃日志页转场：原页面淡出，新页面自右向左淡入全屏",
                "全新图标：白色「箭头落入托盘」符号 + 靛蓝到紫的柔和渐变，不再使用字母",
                "项目结构文档改为脚本自动生成（tools/gen_project_doc.py → docs/PROJECT_MAP.md）",
            )
        ),
    )
}
