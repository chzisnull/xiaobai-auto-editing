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
from backend.app.detectors.audio_visual import AudioVisualRallyDetector

# Temporary SQLite test database
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
    monkeypatch.setenv("MOCK_MODE", "true")
    app.dependency_overrides[get_db] = override_get_db
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)

@pytest.fixture
def client():
    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as c:
        yield c

MOCK_RALLIES = AudioVisualRallyDetector._get_mock_rallies()

def test_ai_analyze_lifecycle_and_db_persistence(client):
    """Test POST /api/v1/ai/analyze/{task_id} end-to-end lifecycle and SQLite DB persistence."""
    # 1. Upload video to create Task
    files = {"file": ("match_m2.mp4", b"fake binary video stream content", "video/mp4")}
    upload_res = client.post("/api/v1/video/upload", files=files)
    assert upload_res.status_code == 201
    task_id = upload_res.json()["task_id"]

    # Verify initial task status in DB is "uploaded"
    db = TestingSessionLocal()
    task_before = db.query(Task).filter(Task.id == task_id).first()
    assert task_before.status == "uploaded"
    db.close()

    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=MOCK_RALLIES):
        analyze_res = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert analyze_res.status_code == 200
    assert analyze_res.json()["status"] == "completed"

    db = TestingSessionLocal()
    task_after = db.query(Task).filter(Task.id == task_id).first()
    assert task_after.status == "completed"
    rallies = db.query(Rally).filter(Rally.task_id == task_id).order_by(Rally.rally_index).all()
    assert len(rallies) == 3
    assert rallies[0].start_time == 15.0
    assert rallies[0].end_time == 28.5
    assert rallies[0].duration == 13.5
    db.close()


def test_ai_analyze_passes_court_roi_to_detector(client):
    files = {"file": ("court_roi.mp4", b"fake video stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]
    court_roi = [
        {"x": 0.2, "y": 0.3},
        {"x": 0.8, "y": 0.3},
        {"x": 0.95, "y": 0.95},
        {"x": 0.05, "y": 0.95},
    ]

    with patch("backend.app.services.ai_service.CourtAwareRallyDetector") as detector_class:
        detector_class.return_value.analyze_video.return_value = MOCK_RALLIES
        response = client.post(
            f"/api/v1/ai/analyze/{task_id}",
            json={"court_roi": court_roi},
        )

    assert response.status_code == 200
    detector_class.assert_called_once_with(
        court_roi=[[point["x"], point["y"]] for point in court_roi]
    )

def test_ai_analyze_idempotency_and_reanalysis(client):
    """Test calling /analyze/{task_id} multiple times replaces old rallies without duplicate PK errors."""
    files = {"file": ("reanalysis.mp4", b"video data", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    # First analysis
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=MOCK_RALLIES):
        res1 = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert res1.status_code == 200

    db = TestingSessionLocal()
    rallies_count_1 = db.query(Rally).filter(Rally.task_id == task_id).count()
    assert rallies_count_1 == 3
    db.close()

    # Second analysis (re-analysis)
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=MOCK_RALLIES):
        res2 = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert res2.status_code == 200

    db = TestingSessionLocal()
    rallies_count_2 = db.query(Rally).filter(Rally.task_id == task_id).count()
    assert rallies_count_2 == 3
    db.close()

def test_ai_analyze_failure_recovery_and_rollback(client):
    """Test failure recovery: exception during detection triggers rollback and task.status = 'failed'."""
    files = {"file": ("fail_test.mp4", b"video stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    # Mock AudioVisualRallyDetector.analyze_video to raise RuntimeError
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video") as mock_det:
        mock_det.side_effect = RuntimeError("Simulated AI Processing Failure")

        res = client.post(f"/api/v1/ai/analyze/{task_id}")
        assert res.status_code == 500
        assert "Simulated AI Processing Failure" in res.json()["detail"]

    # Direct DB Inspection
    db = TestingSessionLocal()
    task_failed = db.query(Task).filter(Task.id == task_id).first()
    assert task_failed.status == "failed"

    rallies_in_db = db.query(Rally).filter(Rally.task_id == task_id).all()
    assert len(rallies_in_db) == 0  # Rolled back, zero orphan records
    db.close()

def test_ai_analyze_unparseable_media_raises_runtime_error_and_sets_failed_status(client, monkeypatch):
    """Verify that unparseable video file causes AudioVisualRallyDetector to raise RuntimeError and AIService sets task status to 'failed'."""
    monkeypatch.setenv("MOCK_MODE", "false")
    files = {"file": ("unparseable.mp4", b"invalid unparseable binary content", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    res = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert res.status_code == 500
    assert "Failed to process media file" in res.json()["detail"]

    db = TestingSessionLocal()
    task = db.query(Task).filter(Task.id == task_id).first()
    assert task.status == "failed"
    db.close()

def test_ai_analyze_nonexistent_task(client):
    """Test POST /api/v1/ai/analyze for non-existent task ID returns HTTP 404."""
    res = client.post("/api/v1/ai/analyze/nonexistent_task_999")
    assert res.status_code == 404
    assert "not found" in res.json()["detail"].lower()

def test_ai_result_for_all_task_statuses(client):
    """Test GET /api/v1/ai/result/{task_id} behavior across uploaded, completed, and failed tasks."""
    # 1. Uploaded Task
    files = {"file": ("status_test.mp4", b"video data", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    res_uploaded = client.get(f"/api/v1/ai/result/{task_id}")
    assert res_uploaded.status_code == 200
    data_uploaded = res_uploaded.json()
    assert data_uploaded["status"] == "uploaded"
    assert data_uploaded["total_rallies"] == 0
    assert data_uploaded["edited_duration"] == 0.0
    assert data_uploaded["rallies"] == []

    # 2. Completed Task
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=MOCK_RALLIES):
        client.post(f"/api/v1/ai/analyze/{task_id}")
    res_completed = client.get(f"/api/v1/ai/result/{task_id}")
    assert res_completed.status_code == 200
    data_completed = res_completed.json()
    assert data_completed["status"] == "completed"
    assert data_completed["total_rallies"] == 3
    assert data_completed["edited_duration"] > 0
    assert len(data_completed["rallies"]) == 3
    # Check start/end alias fields
    first_rally = data_completed["rallies"][0]
    assert first_rally["start"] == 15.0
    assert first_rally["end"] == 28.5

    # 3. Failed Task
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video") as mock_det:
        mock_det.side_effect = RuntimeError("Failure")
        client.post(f"/api/v1/ai/analyze/{task_id}")

    res_failed = client.get(f"/api/v1/ai/result/{task_id}")
    assert res_failed.status_code == 200
    data_failed = res_failed.json()
    assert data_failed["status"] == "failed"
    assert data_failed["total_rallies"] == 0
    assert data_failed["rallies"] == []
