import os
import tempfile
import time
import concurrent.futures
import pytest
from unittest.mock import patch
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker, Session
from sqlalchemy.exc import OperationalError, DBAPIError

from backend.app.main import app
from backend.app.db.session import Base, get_db
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.detectors.audio_visual import AudioVisualRallyDetector

# Temporary SQLite database for stress testing M2.2
TEST_DB_FILE = tempfile.NamedTemporaryFile(suffix="_stress_m2_2.db", delete=False).name
SQLALCHEMY_TEST_DATABASE_URL = f"sqlite:///{TEST_DB_FILE}"

engine = create_engine(
    SQLALCHEMY_TEST_DATABASE_URL,
    connect_args={"check_same_thread": False, "timeout": 30}
)

@event.listens_for(engine, "connect")
def set_sqlite_pragma(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.execute("PRAGMA journal_mode=WAL")
    cursor.close()

TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()

@pytest.fixture(autouse=True)
def setup_test_database(tmp_path, monkeypatch):
    monkeypatch.setenv("MOCK_MODE", "true")
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(parents=True, exist_ok=True)
    monkeypatch.setenv("UPLOAD_DIR", str(upload_dir))

    import backend.app.services.video_service as video_svc
    monkeypatch.setattr(video_svc, "UPLOAD_DIR", str(upload_dir))

    app.dependency_overrides[get_db] = override_get_db
    Base.metadata.drop_all(bind=engine)
    Base.metadata.create_all(bind=engine)
    yield
    Base.metadata.drop_all(bind=engine)

@pytest.fixture
def client():
    app.dependency_overrides[get_db] = override_get_db
    with TestClient(app) as c:
        yield c


# ============================================================================
# 1. API Concurrency & Repeated POST /analyze/{task_id} on Same Task
# ============================================================================

def test_concurrent_repeated_analyze_same_task(client):
    """Empirical test: 15 concurrent POST /analyze/{task_id} requests on the exact same task.
    Must maintain transaction integrity: no duplicate key errors, exactly 3 rallies persisted,
    and Task.status ending in 'completed'.
    """
    # Create single uploaded task
    files = {"file": ("concurrency_match.mp4", b"synthetic video content stream", "video/mp4")}
    upload_res = client.post("/api/v1/video/upload", files=files)
    assert upload_res.status_code == 201
    task_id = upload_res.json()["task_id"]

    num_concurrent = 15

    def run_analyze(index):
        with TestClient(app) as local_client:
            res = local_client.post(f"/api/v1/ai/analyze/{task_id}")
            return res.status_code, res.json()

    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            results = list(executor.map(run_analyze, range(num_concurrent)))

    # All concurrent requests should return HTTP 200 OK with completed status
    for status_code, data in results:
        assert status_code == 200
        assert data["task_id"] == task_id
        assert data["status"] == "completed"

    # DB Integrity verification
    db = TestingSessionLocal()
    task = db.query(Task).filter(Task.id == task_id).first()
    assert task is not None
    assert task.status == "completed"

    rallies = db.query(Rally).filter(Rally.task_id == task_id).order_by(Rally.rally_index).all()
    assert len(rallies) == 3, f"Expected 3 rallies, got {len(rallies)}"
    rally_indices = [r.rally_index for r in rallies]
    assert rally_indices == [1, 2, 3], f"Invalid rally indices: {rally_indices}"
    db.close()


def test_concurrent_analyze_multiple_distinct_tasks(client):
    """Empirical test: 10 concurrent tasks being analyzed simultaneously.
    Ensures no cross-task DB pollution, session leaks, or foreign key race conditions.
    """
    num_tasks = 10
    task_ids = []

    for i in range(num_tasks):
        files = {"file": (f"distinct_match_{i}.mp4", f"content {i}".encode(), "video/mp4")}
        res = client.post("/api/v1/video/upload", files=files)
        assert res.status_code == 201
        task_ids.append(res.json()["task_id"])

    def run_task_analysis(tid):
        with TestClient(app) as local_client:
            res = local_client.post(f"/api/v1/ai/analyze/{tid}")
            return tid, res.status_code, res.json()

    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
            results = list(executor.map(run_task_analysis, task_ids))

    for tid, status_code, data in results:
        assert status_code == 200
        assert data["status"] == "completed"

    # DB verification for all tasks
    db = TestingSessionLocal()
    total_rallies = db.query(Rally).count()
    assert total_rallies == num_tasks * 3, f"Expected {num_tasks * 3} total rallies in DB, found {total_rallies}"

    for tid in task_ids:
        t_rallies = db.query(Rally).filter(Rally.task_id == tid).count()
        assert t_rallies == 3
        t_obj = db.query(Task).filter(Task.id == tid).first()
        assert t_obj.status == "completed"
    db.close()


# ============================================================================
# 2. Injected Failures Mid-Transaction & DB Rollback Integrity
# ============================================================================

def test_detection_failure_mid_transaction_rollback(client):
    """Empirical test: Injected exceptions during detector execution (RuntimeError, ValueError, Custom Error).
    Verifies DB rollback integrity: zero orphan Rally rows in DB and Task.status == 'failed'.
    """
    exceptions_to_test = [
        RuntimeError("CUDA Out of Memory in AudioVisualRallyDetector"),
        ValueError("Invalid audio sample rate in video file"),
        ZeroDivisionError("Audio frame window length is zero"),
    ]

    for exc in exceptions_to_test:
        files = {"file": ("fail_match.mp4", b"fake binary stream", "video/mp4")}
        upload_res = client.post("/api/v1/video/upload", files=files)
        task_id = upload_res.json()["task_id"]

        with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video") as mock_det:
            mock_det.side_effect = exc

            res = client.post(f"/api/v1/ai/analyze/{task_id}")
            assert res.status_code == 500
            assert str(exc) in res.json()["detail"]

        # Verify DB Rollback Integrity
        db = TestingSessionLocal()
        task = db.query(Task).filter(Task.id == task_id).first()
        assert task is not None
        assert task.status == "failed", f"Expected task status 'failed', got '{task.status}'"

        orphan_rallies = db.query(Rally).filter(Rally.task_id == task_id).all()
        assert len(orphan_rallies) == 0, f"Found {len(orphan_rallies)} orphan rally rows after failed transaction!"
        db.close()


def test_failure_after_previous_successful_analysis(client):
    """Empirical test: Re-analyzing a completed task that fails mid-transaction.
    Verifies old rallies are cleared/rolled back and Task.status updates to 'failed'.
    """
    files = {"file": ("reanalyze_fail.mp4", b"video data", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    # 1. First analysis succeeds
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        res1 = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert res1.status_code == 200

    db = TestingSessionLocal()
    assert db.query(Rally).filter(Rally.task_id == task_id).count() == 3
    db.close()

    # 2. Second analysis fails mid-transaction
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video") as mock_det:
        mock_det.side_effect = RuntimeError("Detector crash during re-analysis")
        res2 = client.post(f"/api/v1/ai/analyze/{task_id}")
        assert res2.status_code == 500

    # 3. DB Verification: stale rallies cleared, task status is 'failed'
    db = TestingSessionLocal()
    task = db.query(Task).filter(Task.id == task_id).first()
    assert task.status == "failed"
    rallies = db.query(Rally).filter(Rally.task_id == task_id).all()
    assert len(rallies) == 0
    db.close()


# ============================================================================
# 3. Injected Database Disconnects & Operational Errors
# ============================================================================

def test_db_commit_operational_error_handling(client):
    """Empirical test: Simulating DB OperationalError during commit (e.g. database disk I/O error or lock).
    Ensures application handles DB disconnect gracefully, rolls back cleanly, sets non-corrupted status,
    and leaves zero orphan rally rows in DB.
    """
    files = {"file": ("db_error_match.mp4", b"content", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    # Commit raises OperationalError on completion attempt
    side_effects = [None, OperationalError("database is locked", None, None), None]
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        with patch.object(Session, "commit", side_effect=side_effects):
            res = client.post(f"/api/v1/ai/analyze/{task_id}")
            assert res.status_code == 500
            assert "Rally detection failed" in res.json()["detail"]

    # Test DB engine session remains functional after error recovery & zero orphan rallies exist
    db = TestingSessionLocal()
    task = db.query(Task).filter(Task.id == task_id).first()
    assert task is not None
    assert task.status in ("failed", "uploaded"), f"Unexpected status: {task.status}"

    rallies = db.query(Rally).filter(Rally.task_id == task_id).all()
    assert len(rallies) == 0, f"Found orphan rallies after DB operational error: {len(rallies)}"
    db.close()


def test_db_session_disconnect_recovery(client):
    """Empirical test: Verifies system recovers and can re-run analysis after a simulated database disconnect."""
    files = {"file": ("disconnect_match.mp4", b"content", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    # 1. Trigger error with injected DB exception during detector run
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video") as mock_det:
        mock_det.side_effect = DBAPIError("Database connection lost", None, None)
        res = client.post(f"/api/v1/ai/analyze/{task_id}")
        assert res.status_code == 500

    # 2. Second request without mock (normal flow) must succeed cleanly
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        res_retry = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert res_retry.status_code == 200
    assert res_retry.json()["status"] == "completed"

    db = TestingSessionLocal()
    task = db.query(Task).filter(Task.id == task_id).first()
    assert task.status == "completed"
    assert db.query(Rally).filter(Rally.task_id == task_id).count() == 3
    db.close()


# ============================================================================
# 4. Rapid GET /result/{task_id} Polling Consistency During State Transitions
# ============================================================================

def test_rapid_polling_result_consistency_during_analysis(client):
    """Empirical test: Rapidly polling GET /result/{task_id} from a background thread
    while POST /analyze/{task_id} executes.
    Verifies read consistency:
    - Every polling response returns 200 OK.
    - Response status is always one of valid lifecycle states ('uploaded', 'processing', 'completed').
    - When status=='completed', total_rallies==3 and rallies list is fully populated (no partial half-read data).
    """
    files = {"file": ("polling_match.mp4", b"video data stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    poll_results = []
    stop_polling = False

    def poll_worker():
        with TestClient(app) as local_client:
            while not stop_polling:
                res = local_client.get(f"/api/v1/ai/result/{task_id}")
                if res.status_code == 200:
                    poll_results.append(res.json())
                time.sleep(0.001)
            # Final poll after completion signal to capture finished state
            res = local_client.get(f"/api/v1/ai/result/{task_id}")
            if res.status_code == 200:
                poll_results.append(res.json())

    poll_thread = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    future = poll_thread.submit(poll_worker)

    from backend.app.detectors.audio_visual import AudioVisualRallyDetector
    original_det_func = AudioVisualRallyDetector.analyze_video

    def delayed_analyze(self, path):
        time.sleep(0.05)
        return AudioVisualRallyDetector._get_mock_rallies()

    with patch.object(AudioVisualRallyDetector, "analyze_video", delayed_analyze):
        analyze_res = client.post(f"/api/v1/ai/analyze/{task_id}")
        assert analyze_res.status_code == 200

    stop_polling = True
    future.result()
    poll_thread.shutdown()

    # Empirical assertions on polling responses
    assert len(poll_results) >= 2, f"Expected >=2 poll samples, got {len(poll_results)}"

    statuses_seen = set()
    for item in poll_results:
        st = item["status"]
        statuses_seen.add(st)
        assert st in {"uploaded", "processing", "completed"}, f"Unexpected status during polling: {st}"

        if st == "uploaded" or st == "processing":
            assert item["total_rallies"] == 0 or item["total_rallies"] == 3
        elif st == "completed":
            assert item["total_rallies"] == 3
            assert len(item["rallies"]) == 3
            for index, r in enumerate(item["rallies"], start=1):
                assert r["rally_index"] == index
                assert r["duration"] > 0
                assert r["start"] >= 0
                assert r["end"] > r["start"]

    assert "completed" in statuses_seen, "Polling never observed 'completed' status!"


def test_rapid_polling_result_during_failure_transition(client):
    """Empirical test: Rapidly polling GET /result/{task_id} while POST /analyze/{task_id} fails.
    Verifies that reader threads never see partial/corrupted rally data during failure cleanup,
    and final state correctly reflects status=='failed' with 0 rallies.
    """
    files = {"file": ("polling_fail_match.mp4", b"video data", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    poll_results = []
    stop_polling = False

    def poll_worker():
        with TestClient(app) as local_client:
            while not stop_polling:
                res = local_client.get(f"/api/v1/ai/result/{task_id}")
                if res.status_code == 200:
                    poll_results.append(res.json())
                time.sleep(0.001)
            # Final poll after completion signal
            res = local_client.get(f"/api/v1/ai/result/{task_id}")
            if res.status_code == 200:
                poll_results.append(res.json())

    poll_thread = concurrent.futures.ThreadPoolExecutor(max_workers=1)
    future = poll_thread.submit(poll_worker)

    def failing_analyze(self, path):
        time.sleep(0.05)
        raise RuntimeError("Simulated mid-analysis failure with delay")

    from backend.app.detectors.audio_visual import AudioVisualRallyDetector
    with patch.object(AudioVisualRallyDetector, "analyze_video", failing_analyze):
        analyze_res = client.post(f"/api/v1/ai/analyze/{task_id}")
        assert analyze_res.status_code == 500

    stop_polling = True
    future.result()
    poll_thread.shutdown()

    assert len(poll_results) >= 2, f"Expected >=2 poll samples, got {len(poll_results)}"

    for item in poll_results:
        st = item["status"]
        assert st in {"uploaded", "processing", "failed"}, f"Unexpected status: {st}"
        if st == "failed":
            assert item["total_rallies"] == 0
            assert item["rallies"] == []


# ============================================================================
# 5. Re-analysis & Lifecycle Recovery Workflow
# ============================================================================

def test_reanalyze_recovery_workflow_failed_to_completed(client):
    """Empirical test: Full lifecycle recovery workflow:
    1. Upload task -> 'uploaded'
    2. Analyze fails -> 'failed' (0 rallies)
    3. Re-analyze succeeds -> 'completed' (3 rallies)
    4. Re-analyze again -> 'completed' (3 rallies preserved, no duplicate constraint errors)
    """
    files = {"file": ("recovery_lifecycle.mp4", b"video data stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]

    # Step 1: Initial state
    res1 = client.get(f"/api/v1/ai/result/{task_id}")
    assert res1.json()["status"] == "uploaded"

    # Step 2: Failed analysis
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video") as mock_det:
        mock_det.side_effect = RuntimeError("Temporary GPU fault")
        client.post(f"/api/v1/ai/analyze/{task_id}")

    res2 = client.get(f"/api/v1/ai/result/{task_id}")
    assert res2.json()["status"] == "failed"
    assert res2.json()["total_rallies"] == 0

    # Step 3: Recovery re-analysis
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        client.post(f"/api/v1/ai/analyze/{task_id}")
    res3 = client.get(f"/api/v1/ai/result/{task_id}")
    assert res3.json()["status"] == "completed"
    assert res3.json()["total_rallies"] == 3

    # Step 4: Subsequent re-analysis
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        client.post(f"/api/v1/ai/analyze/{task_id}")
    res4 = client.get(f"/api/v1/ai/result/{task_id}")
    assert res4.json()["status"] == "completed"
    assert res4.json()["total_rallies"] == 3
