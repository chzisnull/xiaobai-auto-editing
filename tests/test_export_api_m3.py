import io
import os
from pathlib import Path
import tempfile
import zipfile
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from backend.app.main import app
from backend.app.db.session import Base, get_db, set_sqlite_pragma
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.models.export import ExportJob

# Setup temporary SQLite database for testing
TEST_DB_FILE = tempfile.NamedTemporaryFile(suffix=".db", delete=False).name
SQLALCHEMY_TEST_DATABASE_URL = f"sqlite:///{TEST_DB_FILE}"

engine = create_engine(
    SQLALCHEMY_TEST_DATABASE_URL, connect_args={"check_same_thread": False}
)

event.listens_for(engine, "connect")(set_sqlite_pragma)

TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def override_get_db():
    try:
        db = TestingSessionLocal()
        yield db
    finally:
        db.close()

@pytest.fixture(autouse=True)
def setup_test_database(monkeypatch):
    monkeypatch.setenv("FFMPEG_MOCK", "1")
    app.dependency_overrides[get_db] = override_get_db
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    yield
    app.dependency_overrides.pop(get_db, None)

@pytest.fixture
def client():
    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as test_client:
        yield test_client

@pytest.fixture
def db():
    db_session = TestingSessionLocal()
    try:
        yield db_session
    finally:
        db_session.close()

# -----------------------------------------------------------------------------
# Integration Tests: Export API & SQLite Persistence
# -----------------------------------------------------------------------------

def test_api_export_merged_mp4_is_one_time_response(client, db, tmp_path):
    task_id = "task_m3_merged_1"
    source = tmp_path / "match_1.mp4"
    source.write_bytes(b"mock source")
    task = Task(id=task_id, filename=source.name, video_path=str(source), status="completed")
    r1 = Rally(task_id=task_id, rally_index=1, start_time=1.0, end_time=4.0, duration=3.0)
    r2 = Rally(task_id=task_id, rally_index=2, start_time=6.0, end_time=9.0, duration=3.0)
    db.add(task)
    db.add_all([r1, r2])
    db.commit()

    payload = {
        "resolution": "1080p",
        "pre_buffer": 1.0,
        "post_buffer": 1.5,
        "export_type": "merged"
    }
    response = client.post(
        f"/api/v1/video/export/{task_id}",
        json=payload,
        headers={"Origin": "http://localhost:3000"},
    )
    assert response.status_code == 200
    assert response.headers["content-type"] == "video/mp4"
    assert response.headers["cache-control"] == "no-store"
    assert "X-Export-Filename" in response.headers["access-control-expose-headers"]
    assert response.headers["x-export-filename"].endswith(".mp4")
    assert float(response.headers["x-file-size-mb"]) >= 0.0
    assert response.content.startswith(b"MOCK_MP4")
    assert db.query(ExportJob).filter(ExportJob.task_id == task_id).count() == 0
    assert not list(Path(tempfile.gettempdir()).glob(f"badminton-export-{task_id}-*"))

def test_api_export_zip_is_one_time_response(client, db, tmp_path):
    task_id = "task_m3_zip_1"
    source = tmp_path / "match_zip.mp4"
    source.write_bytes(b"mock source")
    task = Task(id=task_id, filename=source.name, video_path=str(source), status="completed")
    r1 = Rally(task_id=task_id, rally_index=1, start_time=2.0, end_time=5.0, duration=3.0)
    db.add(task)
    db.add(r1)
    db.commit()

    payload = {
        "resolution": "720p",
        "pre_buffer": 0.5,
        "post_buffer": 1.0,
        "export_type": "zip"
    }
    response = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert response.status_code == 200
    assert response.headers["content-type"] == "application/zip"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["x-export-filename"].endswith(".zip")
    with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
        assert archive.testzip() is None
        assert archive.namelist() == ["rally_01.mp4"]
    assert db.query(ExportJob).filter(ExportJob.task_id == task_id).count() == 0
    assert not list(Path(tempfile.gettempdir()).glob(f"badminton-export-{task_id}-*"))

def test_api_export_nonexistent_task(client):
    response = client.post("/api/v1/video/export/nonexistent_task_9999", json={"resolution": "1080p"})
    assert response.status_code == 404
    assert "not found" in response.json()["detail"].lower()

def test_api_export_not_ready_task(client, db):
    task_id = "task_not_ready"
    task = Task(id=task_id, filename="processing.mp4", video_path="uploads/processing.mp4", status="processing")
    db.add(task)
    db.commit()

    response = client.post(f"/api/v1/video/export/{task_id}", json={"resolution": "1080p"})
    assert response.status_code == 400
    assert "not ready" in response.json()["detail"].lower()

def test_api_export_payload_validation(client):
    res1 = client.post("/api/v1/video/export/demo_task_888", json={"resolution": "4K"})
    assert res1.status_code == 422

    res2 = client.post("/api/v1/video/export/demo_task_888", json={"export_type": "mkv"})
    assert res2.status_code == 422

    res3 = client.post("/api/v1/video/export/demo_task_888", json={"pre_buffer": -1.0})
    assert res3.status_code == 422

def test_download_security_path_traversal(client):
    res1 = client.get("/downloads/../PROJECT.md")
    assert res1.status_code in (400, 404)

    res2 = client.get("/downloads/..\\PROJECT.md")
    assert res2.status_code == 400

    res3 = client.get("/downloads/..%2F..%2Fetc%2Fpasswd")
    assert res3.status_code == 400

def test_download_nonexistent_file_returns_404(client):
    res = client.get("/downloads/nonexistent_file_xyz_12345.mp4")
    assert res.status_code == 404
    assert res.json()["detail"] == "File not found."

def test_export_does_not_create_persistent_job(client, db, tmp_path):
    task_id = "task_no_export_job"
    source = tmp_path / "source.mp4"
    source.write_bytes(b"mock source")
    task = Task(id=task_id, filename=source.name, video_path=str(source), status="completed")
    rally = Rally(task_id=task_id, rally_index=1, start_time=1.0, end_time=2.0, duration=1.0)
    db.add(task)
    db.add(rally)
    db.commit()

    res = client.post(f"/api/v1/video/export/{task_id}", json={"resolution": "1080p"})
    assert res.status_code == 200
    assert db.query(ExportJob).filter(ExportJob.task_id == task_id).count() == 0
