import os
import tempfile
import uuid
import hashlib
import concurrent.futures
import pytest
from pathlib import Path
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event, text
from sqlalchemy.orm import sessionmaker
from sqlalchemy.exc import IntegrityError

from backend.app.db.session import Base, get_db
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.models.export import ExportJob
from backend.app.main import app

# Setup temporary SQLite database for empirical stress testing
TEST_DB_FILE = tempfile.NamedTemporaryFile(suffix="_stress_m1_3.db", delete=False).name
SQLALCHEMY_TEST_DATABASE_URL = f"sqlite:///{TEST_DB_FILE}"

engine = create_engine(
    SQLALCHEMY_TEST_DATABASE_URL,
    connect_args={"check_same_thread": False}
)

@event.listens_for(engine, "connect")
def set_sqlite_pragma(dbapi_connection, connection_record):
    cursor = dbapi_connection.cursor()
    cursor.execute("PRAGMA foreign_keys=ON")
    cursor.close()

TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()

@pytest.fixture(autouse=True)
def setup_test_environment(tmp_path, monkeypatch):
    monkeypatch.setenv("MOCK_MODE", "true")
    # Set isolated upload and export directories
    upload_dir = tmp_path / "uploads"
    export_dir = tmp_path / "exports"
    upload_dir.mkdir(parents=True, exist_ok=True)
    export_dir.mkdir(parents=True, exist_ok=True)

    monkeypatch.setenv("UPLOAD_DIR", str(upload_dir))
    monkeypatch.setenv("EXPORTS_DIR", str(export_dir))

    # Import and patch service level directory constants if applicable
    import backend.app.services.video_service as video_svc
    import backend.app.services.download_service as download_svc
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
# 1. Path Traversal Attacks on /downloads/
# ============================================================================

@pytest.mark.parametrize("traversal_path", [
    "../PROJECT.md",
    ".../.../etc/passwd",
    "..%2fPROJECT.md",
    "%2e%2e%2fPROJECT.md",
    "subfolder/../../PROJECT.md",
    "....//....//PROJECT.md",
    r"..\PROJECT.md",
    "./../PROJECT.md",
    "../uploads/some_file.mp4",
    "../../badminton_app.db",
    "exports/../../etc/shadow",
])
def test_path_traversal_downloads_rejected(client, traversal_path):
    """Empirical test: Various path traversal payloads on /downloads/ MUST be blocked (400 Bad Request or 404 Not Found due to URL normalization)."""
    response = client.get(f"/downloads/{traversal_path}")
    # Path traversal must be blocked via 400 (caught by service validator) or 404 (URL path resolved outside router)
    assert response.status_code in [400, 404], f"Path traversal '{traversal_path}' failed! Status: {response.status_code}"
    # Under no circumstances should sensitive file contents be returned
    assert "Project: Automatic Badminton Match" not in response.text
    assert "root:x:0:0" not in response.text
    if response.status_code == 400:
        detail = response.json().get("detail", "")
        assert "Path traversal attempt detected" in detail or "Invalid filename" in detail


def test_downloads_legitimate_file_success(client, tmp_path):
    """Empirical test: Legitimate file inside EXPORTS_DIR can be downloaded successfully."""
    export_dir = tmp_path / "exports"
    test_filename = "final_export_1080p.mp4"
    test_content = b"fake binary video content data 12345"
    (export_dir / test_filename).write_bytes(test_content)

    response = client.get(f"/downloads/{test_filename}")
    assert response.status_code == 200
    assert response.content == test_content


def test_downloads_nonexistent_file_404(client):
    """Empirical test: Requesting non-existent file inside EXPORTS_DIR returns 404 Not Found."""
    response = client.get("/downloads/nonexistent_file_9999.mp4")
    assert response.status_code == 404
    assert response.json()["detail"] == "File not found."


# ============================================================================
# 2. Concurrent Chunked File Uploads
# ============================================================================

def test_concurrent_chunked_uploads_distinct_files(client, tmp_path):
    """Empirical test: 20 concurrent chunked file uploads must all succeed without locking or corruption."""
    upload_dir = tmp_path / "uploads"
    num_concurrent = 20

    # Generate unique test file content for each task
    file_payloads = [
        (f"video_match_{i}.mp4", f"dummy video content burst {i} ".encode("utf-8") * 10000)
        for i in range(num_concurrent)
    ]

    def perform_upload(item):
        filename, content = item
        # Use TestClient in thread safely
        with TestClient(app) as local_client:
            res = local_client.post(
                "/api/v1/video/upload",
                files={"file": (filename, content, "video/mp4")}
            )
            return res.status_code, res.json(), hashlib.md5(content).hexdigest()

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        results = list(executor.map(perform_upload, file_payloads))

    # Verify all requests completed successfully
    assert len(results) == num_concurrent
    task_ids = set()
    for status_code, data, original_hash in results:
        assert status_code == 201
        assert "task_id" in data
        assert data["status"] == "uploaded"
        task_id = data["task_id"]
        assert task_id not in task_ids, f"Duplicate task_id generated: {task_id}"
        task_ids.add(task_id)

        # Check DB record
        db = TestingSessionLocal()
        db_task = db.query(Task).filter(Task.id == task_id).first()
        assert db_task is not None
        file_on_disk = Path(db_task.video_path)
        assert file_on_disk.exists()
        
        # Check byte fidelity
        disk_hash = hashlib.md5(file_on_disk.read_bytes()).hexdigest()
        assert disk_hash == original_hash
        db.close()


def test_concurrent_uploads_identical_filename(client, tmp_path):
    """Empirical test: 10 concurrent uploads with the exact same filename must not overwrite each other."""
    num_concurrent = 10
    same_filename = "match_final_game3.mp4"

    def perform_upload(idx):
        content = f"content for thread {idx}".encode("utf-8") * 5000
        with TestClient(app) as local_client:
            res = local_client.post(
                "/api/v1/video/upload",
                files={"file": (same_filename, content, "video/mp4")}
            )
            return res.status_code, res.json(), hashlib.md5(content).hexdigest()

    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
        results = list(executor.map(perform_upload, range(num_concurrent)))

    assert len(results) == num_concurrent
    saved_paths = set()
    for status_code, data, original_hash in results:
        assert status_code == 201
        task_id = data["task_id"]
        db = TestingSessionLocal()
        db_task = db.query(Task).filter(Task.id == task_id).first()
        assert db_task is not None
        assert db_task.video_path not in saved_paths, f"File path collision! {db_task.video_path}"
        saved_paths.add(db_task.video_path)

        file_on_disk = Path(db_task.video_path)
        assert file_on_disk.exists()
        disk_hash = hashlib.md5(file_on_disk.read_bytes()).hexdigest()
        assert disk_hash == original_hash
        db.close()

    assert len(saved_paths) == num_concurrent


# ============================================================================
# 3. Foreign Key Cascading Deletion Behavior
# ============================================================================

def test_fk_cascading_deletion_orm_level():
    """Empirical test: Deleting a Task via SQLAlchemy ORM session deletes child Rally and ExportJob records."""
    db = TestingSessionLocal()

    # 1. Create task with child rallies and export jobs
    task_id = "task_orm_cascade_test"
    task = Task(id=task_id, filename="match1.mp4", video_path="/tmp/match1.mp4", duration=300.0)
    db.add(task)

    rallies = [
        Rally(task_id=task_id, rally_index=i+1, start_time=float(i*10), end_time=float(i*10 + 5), duration=5.0)
        for i in range(5)
    ]
    exports = [
        ExportJob(id=f"exp_{i}", task_id=task_id, resolution="1080p", export_url=f"/downloads/exp_{i}.mp4")
        for i in range(2)
    ]
    db.add_all(rallies + exports)
    db.commit()

    # Verify rows exist
    assert db.query(Task).filter(Task.id == task_id).count() == 1
    assert db.query(Rally).filter(Rally.task_id == task_id).count() == 5
    assert db.query(ExportJob).filter(ExportJob.task_id == task_id).count() == 2

    # 2. Delete parent task via ORM
    db.delete(task)
    db.commit()

    # 3. Verify cascading deletion
    assert db.query(Task).filter(Task.id == task_id).count() == 0
    assert db.query(Rally).filter(Rally.task_id == task_id).count() == 0
    assert db.query(ExportJob).filter(ExportJob.task_id == task_id).count() == 0
    db.close()


def test_fk_cascading_deletion_raw_sql_level():
    """Empirical test: Deleting a Task via raw SQL (PRAGMA foreign_keys=ON) triggers ON DELETE CASCADE in SQLite."""
    db = TestingSessionLocal()

    task_id = "task_raw_sql_cascade_test"
    task = Task(id=task_id, filename="match2.mp4", video_path="/tmp/match2.mp4", duration=600.0)
    db.add(task)

    rallies = [
        Rally(task_id=task_id, rally_index=i+1, start_time=float(i*20), end_time=float(i*20 + 10), duration=10.0)
        for i in range(10)
    ]
    exports = [
        ExportJob(id=f"raw_exp_{i}", task_id=task_id, resolution="720p", export_url=f"/downloads/raw_exp_{i}.mp4")
        for i in range(3)
    ]
    db.add_all(rallies + exports)
    db.commit()

    # Verify count
    assert db.query(Rally).filter(Rally.task_id == task_id).count() == 10
    assert db.query(ExportJob).filter(ExportJob.task_id == task_id).count() == 3

    # 2. Delete parent via raw SQL command
    db.execute(text("DELETE FROM tasks WHERE id = :tid"), {"tid": task_id})
    db.commit()

    # 3. Verify database level cascading deletion
    assert db.query(Task).filter(Task.id == task_id).count() == 0
    assert db.query(Rally).filter(Rally.task_id == task_id).count() == 0
    assert db.query(ExportJob).filter(ExportJob.task_id == task_id).count() == 0
    db.close()


def test_fk_enforcement_prevents_orphan_insertion():
    """Empirical test: Inserting a Rally or ExportJob for a non-existent task_id raises IntegrityError."""
    db = TestingSessionLocal()

    invalid_rally = Rally(
        task_id="ghost_task_999",
        rally_index=1,
        start_time=1.0,
        end_time=5.0,
        duration=4.0
    )
    db.add(invalid_rally)
    with pytest.raises(IntegrityError):
        db.commit()
    db.rollback()

    invalid_export = ExportJob(
        id="ghost_export_999",
        task_id="ghost_task_999",
        export_url="/downloads/ghost.mp4"
    )
    db.add(invalid_export)
    with pytest.raises(IntegrityError):
        db.commit()
    db.rollback()
    db.close()


def test_batch_parent_deletion_cascades_all():
    """Empirical test: Batch deleting multiple parent tasks cascades to all 50+ child records."""
    db = TestingSessionLocal()

    num_parents = 10
    total_rallies = 0
    total_exports = 0

    for p in range(num_parents):
        tid = f"batch_task_{p}"
        task = Task(id=tid, filename=f"video_{p}.mp4", video_path=f"/tmp/v_{p}.mp4")
        db.add(task)
        for r in range(5):
            db.add(Rally(task_id=tid, rally_index=r+1, start_time=float(r), end_time=float(r+1), duration=1.0))
            total_rallies += 1
        for e in range(2):
            db.add(ExportJob(id=f"batch_exp_{p}_{e}", task_id=tid, export_url=f"/downloads/exp_{p}_{e}.mp4"))
            total_exports += 1

    db.commit()
    assert db.query(Task).count() == num_parents
    assert db.query(Rally).count() == total_rallies
    assert db.query(ExportJob).count() == total_exports

    # Execute batch delete on tasks
    db.execute(text("DELETE FROM tasks"))
    db.commit()

    assert db.query(Task).count() == 0
    assert db.query(Rally).count() == 0
    assert db.query(ExportJob).count() == 0
    db.close()
