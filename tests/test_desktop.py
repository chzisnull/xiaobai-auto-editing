from pathlib import Path

from backend.app.services.export_service import _cleanup_export_artifacts
from desktop import main as desktop_main


def test_desktop_runtime_uses_writable_user_paths(tmp_path, monkeypatch):
    runtime_env = {}
    monkeypatch.setattr(desktop_main, "user_data_dir", lambda: tmp_path)
    monkeypatch.setattr(desktop_main.os, "environ", runtime_env)

    assert desktop_main.configure_runtime() == tmp_path
    assert runtime_env["DATABASE_URL"] == f"sqlite:///{(tmp_path / 'xiaobai.db').as_posix()}"
    assert Path(runtime_env["UPLOAD_DIR"]) == tmp_path / "uploads"
    assert Path(runtime_env["EXPORTS_DIR"]) == tmp_path / "exports"
    assert runtime_env["DELETE_SOURCE_AFTER_EXPORT"] == "true"


def test_desktop_export_cleanup_removes_source_copy(tmp_path, monkeypatch):
    export_dir = tmp_path / "export"
    export_dir.mkdir()
    (export_dir / "result.mp4").write_bytes(b"result")
    source = tmp_path / "source.mp4"
    source.write_bytes(b"source")
    monkeypatch.setenv("DELETE_SOURCE_AFTER_EXPORT", "true")

    _cleanup_export_artifacts(export_dir, str(source))

    assert not export_dir.exists()
    assert not source.exists()


def test_browser_export_cleanup_keeps_source(tmp_path, monkeypatch):
    export_dir = tmp_path / "export"
    export_dir.mkdir()
    source = tmp_path / "source.mp4"
    source.write_bytes(b"source")
    monkeypatch.delenv("DELETE_SOURCE_AFTER_EXPORT", raising=False)

    _cleanup_export_artifacts(export_dir, str(source))

    assert not export_dir.exists()
    assert source.exists()
