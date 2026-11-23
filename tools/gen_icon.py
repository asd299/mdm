#!/usr/bin/env python3
"""生成应用图标：蓝紫渐变的「箭头落入托盘」符号（无字母），并写入各密度与文档目录。

设计取向（参考 iOS 26 图标原则）：
- 一个强符号：向下箭头 + 开口托盘，一眼是「下载」，缩到 40px 仍可辨；
- 两三种颜色：靛蓝 → 紫的柔和渐变，不做彩虹；
- 上方柔光 + 符号内玻璃高光，有深度但不做 3D；
- 四周留白，符号占画面约 45%。

用法：
    python3 tools/gen_icon.py

依赖：cairosvg、numpy、Pillow
"""
from pathlib import Path

import cairosvg
import io
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
S = 1024
SS = 4  # 超采样倍数

# 符号：短粗箭头 + 开口托盘，尖端与托盘之间留一点空隙
ARROW = "M512 268 L512 468"
TIP = "M446 424 L512 492 L578 424"
TRAY = "M360 612 L360 698 Q360 738 400 738 L624 738 Q664 738 664 698 L664 612"

DEFS = """
    <linearGradient id="base" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#5B8CFF"/>
      <stop offset="0.46" stop-color="#5B4BF0"/>
      <stop offset="1" stop-color="#2E1E70"/>
    </linearGradient>
    <radialGradient id="glowTop" cx="0.24" cy="0.10" r="0.62">
      <stop offset="0" stop-color="#BFD4FF" stop-opacity="0.32"/>
      <stop offset="0.55" stop-color="#9FB6FF" stop-opacity="0.12"/>
      <stop offset="1" stop-color="#9FB6FF" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="glowBottom" cx="0.86" cy="0.92" r="0.58">
      <stop offset="0" stop-color="#4DE0FF" stop-opacity="0.22"/>
      <stop offset="0.6" stop-color="#4DE0FF" stop-opacity="0.06"/>
      <stop offset="1" stop-color="#4DE0FF" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="sheen" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.16"/>
      <stop offset="0.42" stop-color="#FFFFFF" stop-opacity="0.02"/>
      <stop offset="1" stop-color="#000000" stop-opacity="0.10"/>
    </linearGradient>
    <linearGradient id="mark" gradientUnits="userSpaceOnUse" x1="0" y1="260" x2="0" y2="750">
      <stop offset="0" stop-color="#FFFFFF"/>
      <stop offset="1" stop-color="#E8EEFF"/>
    </linearGradient>
    <linearGradient id="glass" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.32"/>
      <stop offset="0.55" stop-color="#FFFFFF" stop-opacity="0.10"/>
      <stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/>
    </linearGradient>
    <filter id="soft" x="-40%" y="-40%" width="180%" height="180%">
      <feGaussianBlur stdDeviation="18"/>
    </filter>
    <mask id="glyphMask">
      <rect width="1024" height="1024" fill="#000000"/>
      <g fill="none" stroke="#FFFFFF" stroke-width="84"
         stroke-linecap="round" stroke-linejoin="round">
        <path d="M512 268 L512 468"/>
        <path d="M446 424 L512 492 L578 424"/>
        <path d="M360 612 L360 698 Q360 738 400 738 L624 738 Q664 738 664 698 L664 612"/>
      </g>
    </mask>
"""


def svg() -> str:
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" viewBox="0 0 {S} {S}">
  <defs>{DEFS}</defs>
  <rect width="{S}" height="{S}" fill="url(#base)"/>
  <rect width="{S}" height="{S}" fill="url(#glowTop)"/>
  <rect width="{S}" height="{S}" fill="url(#glowBottom)"/>
  <rect width="{S}" height="{S}" fill="url(#sheen)"/>

  <!-- 符号投影：很浅的一层，只为脱离底色 -->
  <g transform="translate(0,10)" fill="none" stroke="#1A1150" stroke-opacity="0.20"
     stroke-width="84" stroke-linecap="round" stroke-linejoin="round" filter="url(#soft)">
    <path d="{ARROW}"/>
    <path d="{TIP}"/>
    <path d="{TRAY}"/>
  </g>

  <g fill="none" stroke="url(#mark)" stroke-width="84"
     stroke-linecap="round" stroke-linejoin="round">
    <path d="{ARROW}"/>
    <path d="{TIP}"/>
    <path d="{TRAY}"/>
  </g>

  <!-- 符号内的玻璃高光：上亮下透，符号不会显得死白 -->
  <rect width="{S}" height="{S}" fill="url(#glass)" mask="url(#glyphMask)"/>
</svg>"""


def squircle_alpha(n: int, exponent: float = 5.0) -> np.ndarray:
    y, x = np.mgrid[0:n, 0:n].astype(np.float32)
    cx = (x + 0.5) / n * 2 - 1
    cy = (y + 0.5) / n * 2 - 1
    return ((np.abs(cx) ** exponent + np.abs(cy) ** exponent) <= 1.0).astype(np.float32)


def circle_alpha(n: int) -> np.ndarray:
    y, x = np.mgrid[0:n, 0:n].astype(np.float32)
    cx = (x + 0.5) / n * 2 - 1
    cy = (y + 0.5) / n * 2 - 1
    return ((cx ** 2 + cy ** 2) <= 1.0).astype(np.float32)


def apply_mask(src: Image.Image, alpha: np.ndarray) -> Image.Image:
    arr = np.array(src)
    arr[..., 3] = (arr[..., 3].astype(np.float32) * alpha).astype(np.uint8)
    return Image.fromarray(arr)


def main():
    png = cairosvg.svg2png(bytestring=svg().encode(), output_width=S * SS, output_height=S * SS)
    raw = Image.open(io.BytesIO(png)).convert("RGBA")
    squircle = apply_mask(raw, squircle_alpha(S * SS)).resize((S, S), Image.LANCZOS)

    squircle.save(ROOT / "docs" / "app-icon-1024.png", optimize=True)
    squircle.resize((512, 512), Image.LANCZOS).save(ROOT / "docs" / "app-icon.png", optimize=True)

    # 界面内使用（侧边栏头像）：squircle 位图，避免自适应图标在 ImageView 里画成方形
    out_nodpi = RES / "drawable-nodpi"
    out_nodpi.mkdir(parents=True, exist_ok=True)
    squircle.resize((192, 192), Image.LANCZOS).save(out_nodpi / "app_icon.png", optimize=True)

    round_icon = apply_mask(squircle.copy(), circle_alpha(S))
    for name, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        d = RES / f"mipmap-{name}"
        d.mkdir(parents=True, exist_ok=True)
        squircle.resize((size, size), Image.LANCZOS).save(d / "ic_launcher.png", optimize=True)
        round_icon.resize((size, size), Image.LANCZOS).save(d / "ic_launcher_round.png", optimize=True)

    print("图标已写入 docs/ 与 app/src/main/res/mipmap-*、drawable-nodpi/")


if __name__ == "__main__":
    main()
