# 项目结构（自动生成）

> 由 `tools/gen_project_doc.py` 扫描源码注释与资源目录生成，请勿手动编辑。

Kotlin 源码 **25** 个文件 / **6555** 行；布局 **14** 个；drawable **63** 个；动画 **7** 个；单元测试 **21** 个文件 / **115** 个用例。

## 代码

| 文件 | 行数 | 职责 |
|---|---:|---|
| `AboutActivity.kt` | 204 | 关于页：应用信息、功能说明、致谢与版本更新说明 |
| `AddCoordinator.kt` | 156 | 添加任务的统一入口：先做重复检测，冲突时弹窗让用户选「跳过 / 换个文件名 / 覆盖下载」 |
| `AddDownloadDialog.kt` | 258 | 1DM 风格下载确认窗：链接 / 另存为 / 大小 / 续传状态 / 存储空间 / 仅限Wi-Fi / 出错重试 |
| `Categories.kt` | 115 | 侧边栏分类：状态分类 + 文件类型分类 |
| `Changelog.kt` | 92 | 版本更新记录与上游基线 |
| `Conflict.kt` | 98 | 冲突类型：只撞链接、只撞文件名，或两者都撞 |
| `CrashHandler.kt` | 175 | 全局崩溃捕获 |
| `CrashLogActivity.kt` | 95 | 崩溃 / 错误日志查看页，支持一键复制到剪切板 |
| `DownloadEngine.kt` | 441 | 多线程分段下载引擎（受 Mister Download Manager 的分段并发下载思想启发） |
| `DownloadService.kt` | 370 | 前台下载服务：管理任务队列、更新进度通知 |
| `DownloadTask.kt` | 254 | 任务状态 |
| `DownloadsAdapter.kt` | 545 | 下载列表适配器 |
| `DrawerPanelLayout.kt` | 75 | 侧边栏面板容器：只有右侧两个角是圆角，并且**真的**把子视图裁进这两个圆角里 |
| `MainActivity.kt` | 1824 | 属性面板：与版本说明同一套贴底面板 |
| `MdmApp.kt` | 12 | — |
| `PanelSheet.kt` | 190 | 贴底的底部面板：属性页、版本说明共用同一套行为 |
| `PromptActivity.kt` | 63 | 悬浮下载确认窗：别的应用把链接交给 MDM 时（分享文本 / 直接打开 http 链接 / 快捷添加） |
| `PublicStore.kt` | 323 | 分段写入器：把一段字节写到文件的指定偏移 |
| `SafeSwipeRefreshLayout.kt` | 79 | 下拉刷新容器，给「进入下拉」加一道方向与距离的闸门 |
| `SaveStore.kt` | 391 | 统一的「保存到用户所选目录」实现 |
| `SmoothCornerPath.kt` | 50 | 全项目共用的圆角路径 |
| `SmoothShapeDrawable.kt` | 206 | 圆角背景 Drawable（标准圆弧，描边 / 渐变 / 分角半径） |
| `SwipeDrawerLayout.kt` | 434 | 跟手展开的侧边栏容器，替代 DrawerLayout |
| `UpdateChecker.kt` | 63 | 检查更新：读本项目 GitHub Release 的最新 tag，与当前版本比较 |
| `WifiPolicy.kt` | 42 | 「仅限 Wi-Fi」下载策略 |

## 界面布局

| 文件 | 用途 |
|---|---|
| `activity_about.xml` | 关于页：身份卡、入口、上游项目、致谢 |
| `activity_crash_log.xml` | 崩溃日志页 |
| `activity_main.xml` | 主页面：工具栏、筛选、列表、排序 / 操作面板、侧边栏 |
| `dialog_add.xml` | 添加下载任务 |
| `dialog_conflict.xml` | 重复任务冲突选择 |
| `drawer_content.xml` | 侧边栏内容：分组导航与设置 |
| `item_about_row.xml` | 关于页入口行 |
| `item_changelog_version.xml` | 版本说明的单个版本 |
| `item_download.xml` | 下载任务卡片 |
| `item_nav_row.xml` | 侧边栏单行 |
| `item_prop_row.xml` | — |
| `nav_header.xml` | 侧边栏顶部概览卡 |
| `sheet_changelog.xml` | 版本说明底部面板 |
| `sheet_properties.xml` | — |

## 资源

- `res/anim`：`item_in.xml`, `screen_enter.xml`, `screen_exit.xml`, `screen_pop_enter.xml`, `screen_pop_exit.xml`, `sheet_in.xml`, `sheet_out.xml`

- `res/drawable*`：共 63 个（图标、卡片背景、进度条样式）

