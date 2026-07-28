import os
import tempfile
import pytest
from unittest.mock import patch
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from backend.app.main import app
from backend.app.db.session import Base, get_db, set_sqlite_pragma
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.models.export import ExportJob
from backend.app.schemas.task import TaskResponse
from backend.app.detectors.audio_visual import AudioVisualRallyDetector

# Setup temporary SQLite database for testing
TEST_DB_FILE = tempfile.NamedTemporaryFile(suffix=".db", delete=False).name
SQLALCHEMY_TEST_DATABASE_URL = f"sqlite:///{TEST_DB_FILE}"

engine = create_engine(
    SQLALCHEMY_TEST_DATABASE_URL, connect_args={"check_same_thread": False}
)

# Enable Foreign Key constraints PRAGMA on test SQLite connection
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
    monkeypatch.setenv("MOCK_MODE", "true")
    monkeypatch.setenv("FFMPEG_MOCK", "1")
    app.dependency_overrides[get_db] = override_get_db
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)

@pytest.fixture
def client():
    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as c:
        yield c

def test_root_endpoint(client):
    response = client.get("/", follow_redirects=False)
    assert response.status_code in (302, 307)
    assert response.headers["location"] == "/app/"

def test_video_upload_and_db_persistence(client):
    # Test file upload endpoint
    file_content = b"fake video content for testing"
    files = {"file": ("match_sample.mp4", file_content, "video/mp4")}
    response = client.post("/api/v1/video/upload", files=files)
    
    assert response.status_code in [200, 201]
    data = response.json()
    assert "task_id" in data
    task_id = data["task_id"]
    assert data["filename"] == "match_sample.mp4"
    assert data["status"] == "uploaded"

    # Direct DB verification & Pydantic TaskResponse.model_validate test
    db = TestingSessionLocal()
    task_in_db = db.query(Task).filter(Task.id == task_id).first()
    assert task_in_db is not None
    assert task_in_db.filename == "match_sample.mp4"
    assert task_in_db.status == "uploaded"
    assert os.path.exists(task_in_db.video_path)

    validated_resp = TaskResponse.model_validate(task_in_db)
    assert validated_resp.task_id == task_id
    assert validated_resp.filename == "match_sample.mp4"
    db.close()

    task_response = client.get(f"/api/v1/video/task/{task_id}")
    assert task_response.status_code == 200
    assert task_response.json()["task_id"] == task_id
    assert task_response.json()["video_url"].startswith("/uploads/")


def test_get_video_task_returns_404_for_unknown_task(client):
    response = client.get("/api/v1/video/task/missing-task")

    assert response.status_code == 404
    assert "missing-task" in response.json()["detail"]

def test_streamed_upload_with_mock_file(client):
    file_content = b"streamed mock video binary chunk 1 chunk 2 " * 200
    files = {"file": ("streamed_sample.mp4", file_content, "video/mp4")}
    response = client.post("/api/v1/video/upload", files=files)

    assert response.status_code == 201
    data = response.json()
    task_id = data["task_id"]
    assert data["filename"] == "streamed_sample.mp4"
    assert data["status"] == "uploaded"

    db = TestingSessionLocal()
    task_in_db = db.query(Task).filter(Task.id == task_id).first()
    assert task_in_db is not None
    assert os.path.exists(task_in_db.video_path)
    with open(task_in_db.video_path, "rb") as f:
        saved_content = f.read()
    assert saved_content == file_content
    db.close()

def test_ai_analyze_endpoint(client):
    # 1. First upload a video to create a Task
    file_content = b"test video binary stream"
    files = {"file": ("test_analysis.mp4", file_content, "video/mp4")}
    upload_res = client.post("/api/v1/video/upload", files=files)
    task_id = upload_res.json()["task_id"]

    # 2. Trigger AI analysis
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        analyze_res = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert analyze_res.status_code == 200
    analyze_data = analyze_res.json()
    assert analyze_data["task_id"] == task_id
    assert analyze_data["status"] == "completed"

    # Direct DB verification: Task status updated and Rallies created
    db = TestingSessionLocal()
    task_in_db = db.query(Task).filter(Task.id == task_id).first()
    assert task_in_db.status == "completed"
    
    rallies_in_db = db.query(Rally).filter(Rally.task_id == task_id).all()
    assert len(rallies_in_db) == 3
    assert rallies_in_db[0].rally_index == 1
    assert rallies_in_db[0].start_time == 15.0
    assert rallies_in_db[0].end_time == 28.5
    db.close()

def test_ai_analyze_nonexistent_task(client):
    response = client.post("/api/v1/ai/analyze/nonexistent_999")
    assert response.status_code == 404
    assert "not found" in response.json()["detail"].lower()

def test_ai_result_endpoint(client):
    # Upload video and perform analysis
    files = {"file": ("result_test.mp4", b"video stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        client.post(f"/api/v1/ai/analyze/{task_id}")

    # Query AI result
    res = client.get(f"/api/v1/ai/result/{task_id}")
    assert res.status_code == 200
    data = res.json()
    assert data["task_id"] == task_id
    assert data["status"] == "completed"
    assert data["total_rallies"] == 3
    assert data["original_duration"] == 0.0
    assert data["edited_duration"] > 0
    assert len(data["rallies"]) == 3
    # Verify field compatibility (both start_time/end_time and start/end)
    r1 = data["rallies"][0]
    assert "start_time" in r1 and "end_time" in r1
    assert "start" in r1 and "end" in r1
    assert r1["start"] == 15.0
    assert r1["end"] == 28.5

def test_ai_result_nonexistent_task(client):
    response = client.get("/api/v1/ai/result/nonexistent_999")
    assert response.status_code == 404

def test_video_export_task_state_validation(client):
    # Upload video (status is 'uploaded', 0 rallies)
    files = {"file": ("unready_export.mp4", b"video content", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    # Export attempt when task is not ready -> 400 Bad Request
    payload = {
        "resolution": "1080p",
        "pre_buffer": 1.0,
        "post_buffer": 1.5,
        "export_type": "merged"
    }
    res_unready = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert res_unready.status_code == 400
    assert "not ready" in res_unready.json()["detail"].lower()

    # Trigger AI analysis so task status becomes 'completed'
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        client.post(f"/api/v1/ai/analyze/{task_id}")

    # Export attempt when task is completed -> one-time binary response
    res_ready = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert res_ready.status_code == 200
    assert res_ready.headers["content-type"] == "video/mp4"
    assert res_ready.headers["cache-control"] == "no-store"
    assert res_ready.headers["x-export-filename"].endswith(".mp4")
    assert res_ready.content.startswith(b"MOCK_MP4")

    # One-time exports are not persisted as jobs.
    db = TestingSessionLocal()
    export_in_db = db.query(ExportJob).filter(ExportJob.task_id == task_id).first()
    assert export_in_db is None
    db.close()

def test_video_export_nonexistent_task(client):
    payload = {"resolution": "1080p", "export_type": "merged"}
    res = client.post("/api/v1/video/export/nonexistent_999", json=payload)
    assert res.status_code == 404

def test_path_traversal_attempts(client):
    res1 = client.get("/downloads/../PROJECT.md")
    assert res1.status_code in [400, 404]

    res2 = client.get("/downloads/..%2FPROJECT.md")
    assert res2.status_code in [400, 404]

def test_nonexistent_download_file(client):
    filename = "nonexistent_file_9999.mp4"
    res = client.get(f"/downloads/{filename}")
    assert res.status_code == 404

    # Verify DO NOT create dummy text files on disk
    exports_dir = os.getenv("EXPORTS_DIR", "exports")
    assert not os.path.exists(os.path.join(exports_dir, filename))

def test_export_endpoint_returns_download_content_directly(client):
    # Upload and analyze video, then receive the generated bytes in one response.
    files = {"file": ("dl_success.mp4", b"video stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        client.post(f"/api/v1/ai/analyze/{task_id}")
    payload = {"resolution": "1080p", "export_type": "merged"}
    export_res = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert export_res.status_code == 200
    assert export_res.headers["content-type"] == "video/mp4"
    assert export_res.headers["cache-control"] == "no-store"
    assert export_res.content.startswith(b"MOCK_MP4")

def test_foreign_key_cascading_deletion(client):
    db = TestingSessionLocal()
    task = Task(id="fk_task_100", filename="test_fk.mp4", video_path="uploads/test_fk.mp4", status="completed")
    db.add(task)
    db.commit()

    rally = Rally(task_id="fk_task_100", rally_index=1, start_time=1.0, end_time=5.0, duration=4.0)
    export_job = ExportJob(id="fk_exp_100", task_id="fk_task_100", export_url="/downloads/fk.mp4")
    db.add(rally)
    db.add(export_job)
    db.commit()

    # Verify records exist
    assert db.query(Rally).filter(Rally.task_id == "fk_task_100").count() == 1
    assert db.query(ExportJob).filter(ExportJob.task_id == "fk_task_100").count() == 1

    # Delete parent task
    db.delete(task)
    db.commit()

    # Verify cascading deletion in SQLite DB
    assert db.query(Rally).filter(Rally.task_id == "fk_task_100").count() == 0
    assert db.query(ExportJob).filter(ExportJob.task_id == "fk_task_100").count() == 0
    db.close()
