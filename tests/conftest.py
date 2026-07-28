import pytest


@pytest.fixture(autouse=True)
def isolate_media_directories(tmp_path, monkeypatch):
    """Keep uploaded and rendered test artifacts out of the project workspace."""
    monkeypatch.setenv("UPLOAD_DIR", str(tmp_path / "uploads"))
    monkeypatch.setenv("EXPORTS_DIR", str(tmp_path / "exports"))
