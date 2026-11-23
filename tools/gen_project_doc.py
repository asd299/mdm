#!/usr/bin/env python3
"""从源码与资源自动生成项目说明（docs/PROJECT_MAP.md）。

扫描：
- app/src/main/java 下每个 Kotlin 文件的首段 KDoc 注释
- res/layout 下的布局数量
- res/drawable、res/anim、res/values 的资源数量
- app/src/test 下的测试文件与用例数

用法：
    python3 tools/gen_project_doc.py
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
MAIN = ROOT / "app/src/main/java/com/misterdownloadmanager/mobile"
RES = ROOT / "app/src/main/res"
TEST = ROOT / "app/src/test/java/com/misterdownloadmanager/mobile"
OUT = ROOT / "docs/PROJECT_MAP.md"

KDoc = re.compile(r"/\*\*(.*?)\*/", re.S)


def summarize(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    m = KDoc.search(text)
    if not m:
        return "—"
    for line in m.group(1).splitlines():
        line = line.strip().lstrip("*").strip()
        if line and not line.startswith("@"):
            return line.rstrip("。，,：:、 ")
    return "—"


def main() -> None:
    kt = sorted(MAIN.glob("*.kt"))
    rows = []
    total_lines = 0
    for f in kt:
        lines = len(f.read_text(encoding="utf-8").splitlines())
        total_lines += lines
        rows.append((f.name, lines, summarize(f)))

    layouts = sorted((RES / "layout").glob("*.xml"))
    drawables = sorted((RES / "drawable").glob("*.xml")) + sorted((RES / "drawable-nodpi").glob("*.png"))
    anims = sorted((RES / "anim").glob("*.xml"))
    tests = sorted(TEST.glob("*.kt"))
    case_count = 0
    for t in tests:
        case_count += len(re.findall(r"@Test", t.read_text(encoding="utf-8")))

    out = []
    out.append("# 项目结构（自动生成）\n")
    out.append("> 由 `tools/gen_project_doc.py` 扫描源码注释与资源目录生成，请勿手动编辑。\n")
    out.append(f"Kotlin 源码 **{len(kt)}** 个文件 / **{total_lines}** 行；"
               f"布局 **{len(layouts)}** 个；drawable **{len(drawables)}** 个；动画 **{len(anims)}** 个；"
               f"单元测试 **{len(tests)}** 个文件 / **{case_count}** 个用例。\n")
    out.append("## 代码\n")
    out.append("| 文件 | 行数 | 职责 |")
    out.append("|---|---:|---|")
    for name, lines, desc in rows:
        out.append(f"| `{name}` | {lines} | {desc} |")

    out.append("\n## 界面布局\n")
    out.append("| 文件 | 用途 |")
    out.append("|---|---|")
    for f in layouts:
        out.append(f"| `{f.name}` | {layout_purpose(f.stem)} |")

    out.append("\n## 资源\n")
    out.append(f"- `res/anim`：{', '.join('`' + f.name + '`' for f in anims)}\n")
    out.append(f"- `res/drawable*`：共 {len(drawables)} 个（图标、卡片背景、进度条样式）\n")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(out) + "\n", encoding="utf-8")
    print(f"已生成 {OUT.relative_to(ROOT)}")


def layout_purpose(name: str) -> str:
    table = {
        "activity_main": "主页面：工具栏、筛选、列表、排序 / 操作面板、侧边栏",
        "activity_about": "关于页：身份卡、入口、上游项目、致谢",
        "activity_crash_log": "崩溃日志页",
        "drawer_content": "侧边栏内容：分组导航与设置",
        "nav_header": "侧边栏顶部概览卡",
        "item_download": "下载任务卡片",
        "item_nav_row": "侧边栏单行",
        "item_about_row": "关于页入口行",
        "item_action_chip": "操作面板的胶囊按钮",
        "item_changelog_version": "版本说明的单个版本",
        "sheet_changelog": "版本说明底部面板",
        "dialog_add": "添加下载任务",
        "dialog_conflict": "重复任务冲突选择",
        "dialog_properties": "任务属性",
        "dialog_segment": "分段数选择",
        "dialog_sort": "排序方式",
        "activity_prompt": "浏览器唤起时的下载确认",
    }
    return table.get(name, "—")


if __name__ == "__main__":
    main()
