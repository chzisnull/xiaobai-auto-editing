# -*- mode: python ; coding: utf-8 -*-
from pathlib import Path
import os
import platform


PROJECT_ROOT = Path(SPECPATH).parent
datas = [
    (str(PROJECT_ROOT / "frontend"), "frontend"),
    (str(PROJECT_ROOT / "backend" / "model_assets"), "backend/model_assets"),
]
binaries = []
hiddenimports = [
    "uvicorn.logging",
    "uvicorn.loops.auto",
    "uvicorn.loops.asyncio",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan.on",
]

icon_extension = "icns" if platform.system() == "Darwin" else "ico"
icon_path = PROJECT_ROOT / "desktop" / "assets" / f"icon.{icon_extension}"
platform_dir = "windows" if platform.system() == "Windows" else "macos"
bundled_tools = PROJECT_ROOT / "desktop" / "bin" / platform_dir
if bundled_tools.exists():
    datas.append((str(bundled_tools), f"bin/{platform_dir}"))

app_version = os.environ.get("XIAOBAI_VERSION", "1.0.0")

a = Analysis(
    [str(PROJECT_ROOT / "desktop" / "main.py")],
    pathex=[str(PROJECT_ROOT)],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    noarchive=False,
)
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="小白自动剪辑",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    icon=str(icon_path) if icon_path.exists() else None,
)
collection = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    name="小白自动剪辑",
)
if platform.system() == "Darwin":
    app = BUNDLE(
        collection,
        name="小白自动剪辑.app",
        icon=str(icon_path) if icon_path.exists() else None,
        bundle_identifier="icu.yuqiuyijiaren.xiaobai-auto-editing",
        info_plist={
            "CFBundleShortVersionString": app_version,
            "CFBundleVersion": "1",
            "NSHighResolutionCapable": True,
        },
    )
