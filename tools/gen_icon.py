#!/usr/bin/env python3
"""生成应用图标（深紫极光 squircle + 白色 M），并写入各密度与文档目录。

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

MARK_PATH = "M306 744 L306 336 L512 592 L718 336 L718 744"

DEFS = f"""
    <linearGradient id="base" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#2A1A63"/>
      <stop offset="0.48" stop-color="#4C1D95"/>
      <stop offset="1" stop-color="#7C3AED"/>
    </linearGradient>
    <radialGradient id="glowA" cx="0.78" cy="0.12" r="0.72">
      <stop offset="0" stop-color="#C4B5FD" stop-opacity="0.55"/>
      <stop offset="0.55" stop-color="#A78BFA" stop-opacity="0.16"/>
      <stop offset="1" stop-color="#A78BFA" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="glowB" cx="0.12" cy="0.9" r="0.66">
      <stop offset="0" stop-color="#22D3EE" stop-opacity="0.38"/>
      <stop offset="0.6" stop-color="#22D3EE" stop-opacity="0.08"/>
      <stop offset="1" stop-color="#22D3EE" stop-opacity="0"/>
    </radialGradient>
    <linearGradient id="shade" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF" stop-opacity="0.10"/>
      <stop offset="0.45" stop-color="#FFFFFF" stop-opacity="0.02"/>
      <stop offset="1" stop-color="#000000" stop-opacity="0.12"/>
    </linearGradient>
    <linearGradient id="mark" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="#FFFFFF"/>
      <stop offset="1" stop-color="#E6DFFF"/>
    </linearGradient>
    <filter id="soft" x="-40%" y="-40%" width="180%" height="180%">
      <feGaussianBlur stdDeviation="26"/>
    </filter>
"""


def svg() -> str:
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" viewBox="0 0 {S} {S}">
  <defs>{DEFS}</defs>
  <rect width="{S}" height="{S}" fill="url(#base)"/>
  <rect width="{S}" height="{S}" fill="url(#glowA)"/>
  <rect width="{S}" height="{S}" fill="url(#glowB)"/>
  <rect width="{S}" height="{S}" fill="url(#shade)"/>
  <path d="{MARK_PATH}" fill="none" stroke="#2A1A63" stroke-opacity="0.35"
        stroke-width="112" stroke-linecap="round" stroke-linejoin="round" filter="url(#soft)"/>
  <path d="{MARK_PATH}" fill="none" stroke="url(#mark)" stroke-width="104"
        stroke-linecap="round" stroke-linejoin="round"/>
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
