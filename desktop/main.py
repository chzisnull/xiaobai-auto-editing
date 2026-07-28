"""Launch 小白自动剪辑 as a local desktop application."""

from __future__ import annotations

import os
import platform
import socket
import sys
import threading
import time
import urllib.request
from pathlib import Path


APP_NAME = "XiaobaiAutoEditing"
APP_TITLE = "小白自动剪辑"


def resource_root() -> Path:
    """Return the unpacked PyInstaller resources or the repository root."""
    if getattr(sys, "frozen", False):
        return Path(getattr(sys, "_MEIPASS")).resolve()
    return Path(__file__).resolve().parents[1]


def user_data_dir() -> Path:
    system = platform.system()
    if system == "Darwin":
        base = Path.home() / "Library" / "Application Support"
    elif system == "Windows":
        base = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    path = base / APP_NAME
    path.mkdir(parents=True, exist_ok=True)
    return path


def _sqlite_url(path: Path) -> str:
    return f"sqlite:///{path.as_posix()}"


def configure_runtime() -> Path:
    """Set writable desktop paths before importing the FastAPI application."""
    app_data = user_data_dir()
    uploads = app_data / "uploads"
    exports = app_data / "exports"
    uploads.mkdir(exist_ok=True)
    exports.mkdir(exist_ok=True)

    os.environ.setdefault("DATABASE_URL", _sqlite_url(app_data / "xiaobai.db"))
    os.environ.setdefault("UPLOAD_DIR", str(uploads))
    os.environ.setdefault("EXPORTS_DIR", str(exports))
    os.environ.setdefault("DELETE_SOURCE_AFTER_EXPORT", "true")
    os.environ.setdefault("TRACKNET_DEVICE", "auto")

    # Finder and Explorer start apps with a minimal PATH. Include common FFmpeg locations.
    root = resource_root()
    system = platform.system()
    bundled_bin = root / "bin" / ("windows" if system == "Windows" else "macos")
    common_bins = [str(bundled_bin)]
    if system == "Darwin":
        common_bins.extend(["/opt/homebrew/bin", "/usr/local/bin"])
    os.environ["PATH"] = os.pathsep.join([*common_bins, os.environ.get("PATH", "")])
    return app_data


def free_local_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


class LocalServer:
    def __init__(self, port: int) -> None:
        self.port = port
        self.server = None
        self.thread: threading.Thread | None = None

    def start(self) -> None:
        import uvicorn
        from backend.app.main import app

        self.server = uvicorn.Server(
            uvicorn.Config(app, host="127.0.0.1", port=self.port, log_level="warning", access_log=False)
        )
        self.thread = threading.Thread(target=self.server.run, name="xiaobai-api", daemon=True)
        self.thread.start()

        health_url = f"http://127.0.0.1:{self.port}/api/health"
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            try:
                with urllib.request.urlopen(health_url, timeout=1) as response:
                    if response.status == 200:
                        return
            except OSError:
                time.sleep(0.1)
        self.stop()
        raise RuntimeError("本地剪辑服务启动失败")

    def stop(self) -> None:
        if self.server is not None:
            self.server.should_exit = True
        if self.thread is not None:
            self.thread.join(timeout=5)


def launch() -> None:
    configure_runtime()
    server = LocalServer(free_local_port())
    server.start()

    try:
        import webview

        webview.settings["ALLOW_DOWNLOADS"] = True
        window = webview.create_window(
            APP_TITLE,
            f"http://127.0.0.1:{server.port}/app/",
            width=1440,
            height=900,
            min_size=(1000, 700),
        )
        def close_server(*_: object) -> None:
            server.stop()

        window.events.closed += close_server
        webview.start()
    finally:
        server.stop()


if __name__ == "__main__":
    if platform.system() == "Windows":
        import multiprocessing

        multiprocessing.freeze_support()
    launch()
