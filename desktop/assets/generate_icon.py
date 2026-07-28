"""Generate platform icons without requiring design-tool dependencies."""

from __future__ import annotations

import platform
import subprocess
from pathlib import Path

from PIL import Image, ImageDraw


ASSET_DIR = Path(__file__).resolve().parent
ICONSET_DIR = ASSET_DIR / "xiaobai.iconset"


def draw_icon(size: int) -> Image.Image:
    image = Image.new("RGBA", (size, size), "#007aff")
    draw = ImageDraw.Draw(image)
    scale = size / 1024
    width = max(16, round(74 * scale))

    # A compact scissors mark, kept legible at small app-icon sizes.
    draw.line([(320 * scale, 320 * scale), (704 * scale, 704 * scale)], fill="white", width=width)
    draw.line([(704 * scale, 320 * scale), (320 * scale, 704 * scale)], fill="white", width=width)
    radius = 105 * scale
    for center in ((286 * scale, 286 * scale), (286 * scale, 738 * scale)):
        draw.ellipse(
            [center[0] - radius, center[1] - radius, center[0] + radius, center[1] + radius],
            outline="white",
            width=width,
        )
    return image


def main() -> None:
    base = draw_icon(1024)
    base.save(ASSET_DIR / "icon.png")
    base.save(ASSET_DIR / "icon.ico", sizes=[(16, 16), (32, 32), (48, 48), (256, 256)])

    if platform.system() != "Darwin":
        return
    ICONSET_DIR.mkdir(exist_ok=True)
    for size in (16, 32, 128, 256, 512):
        base.resize((size, size), Image.Resampling.LANCZOS).save(ICONSET_DIR / f"icon_{size}x{size}.png")
        base.resize((size * 2, size * 2), Image.Resampling.LANCZOS).save(
            ICONSET_DIR / f"icon_{size}x{size}@2x.png"
        )
    subprocess.run(
        ["iconutil", "-c", "icns", str(ICONSET_DIR), "-o", str(ASSET_DIR / "icon.icns")],
        check=True,
    )


if __name__ == "__main__":
    main()
