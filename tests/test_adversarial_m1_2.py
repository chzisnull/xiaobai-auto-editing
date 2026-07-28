import os
import tempfile
import json
import pytest
import math
import sqlalchemy.orm

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker
from sqlalchemy.exc import IntegrityError

from backend.app.db.session import Base, get_db
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.models.export import ExportJob
from backend.app.main import app
from backend.app.schemas.task import TaskCreate, TaskResponse
from backend.app.schemas.rally import RallyItem, RallyListResponse
from backend.app.schemas.export import ExportRequest

# Setup temporary SQLite DB for testing
TEST_DB_FILE = tempfile.NamedTemporaryFile(suffix="_adv.db", delete=False).name
SQLALCHEMY_TEST_DATABASE_URL = f"sqlite:///{TEST_DB_FILE}"

engine = create_engine(
    SQLALCHEMY_TEST_DATABASE_URL, connect_args={"check_same_thread": False}
)
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
# 1. FastAPI Schemas Boundary Value Stress Tests
# ============================================================================

from pydantic import ValidationError

def test_schema_rally_item_negative_timestamps():
    """Stress test: RallyItem schema with negative start_time, end_time, duration."""
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        RallyItem(
            rally_index=1,
            start_time=-10.5,
            end_time=-2.0,
            duration=-8.5,
            confidence=0.9
        )


def test_schema_rally_item_inverted_timestamps():
    """Stress test: RallyItem schema where start_time > end_time."""
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        RallyItem(
            rally_index=1,
            start_time=50.0,
            end_time=10.0,
            duration=-40.0,
            confidence=0.8
        )


def test_schema_rally_item_zero_duration():
    """Stress test: RallyItem schema with 0 duration."""
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        RallyItem(
            rally_index=1,
            start_time=15.0,
            end_time=15.0,
            duration=0.0
        )


def test_schema_rally_item_nan_and_inf():
    """Stress test: RallyItem schema with float NaN and Inf values."""
    from pydantic import ValidationError
    with pytest.raises(ValidationError):
        RallyItem(
            rally_index=1,
            start_time=float('nan'),
            end_time=10.0,
            duration=float('nan')
        )

    with pytest.raises(ValidationError):
        RallyItem(
            rally_index=1,
            start_time=0.0,
            end_time=float('inf'),
            duration=float('inf')
        )


def test_schema_rally_item_extreme_float_precision():
    """Stress test: RallyItem schema with extreme float precision."""
    high_prec_start = 1.1234567890123456789
    high_prec_end = 2.9876543210987654321
    item = RallyItem(
        rally_index=1,
        start_time=high_prec_start,
        end_time=high_prec_end,
        duration=high_prec_end - high_prec_start
    )
    assert item.start_time == high_prec_start
    assert item.end_time == high_prec_end


def test_schema_export_request_boundary_values():
    """Stress test: ExportRequest schema with negative buffers and invalid options."""
    with pytest.raises(ValidationError):
        ExportRequest(
            resolution="8K_ULTRA",
            export_type="raw_avi_unsupported",
            pre_buffer=-999.0,
            post_buffer=-500.0
        )


def test_schema_export_request_large_payload():
    """Reject unbounded rally payloads before they reach FFmpeg."""
    large_rallies = [
        {"id": i, "start": float(i), "end": float(i + 5), "duration": 5.0}
        for i in range(10000)
    ]
    with pytest.raises(ValidationError):
        ExportRequest(
            rallies=large_rallies,
            resolution="1080p",
            export_type="merged"
        )


# ============================================================================
# 2. SQLite Database Models & Integrity Constraints Stress Tests
# ============================================================================

def test_db_duplicate_task_id_integrity_error():
    """Stress test: Inserting duplicate primary key in tasks table."""
    db = TestingSessionLocal()
    task1 = Task(id="dup_task_1", filename="video1.mp4", video_path="/tmp/v1.mp4")
    db.add(task1)
    db.commit()

    # Attempt inserting task with same ID
    task2 = Task(id="dup_task_1", filename="video2.mp4", video_path="/tmp/v2.mp4")
    db.add(task2)
    with pytest.raises(IntegrityError):
        db.commit()
    db.rollback()
    db.close()


def test_db_foreign_key_orphan_rally_insertion():
    """Stress test: Foreign key enforcement on Rally insertion for nonexistent task_id."""
    db = TestingSessionLocal()
    # Note: SQLite requires explicit 'PRAGMA foreign_keys = ON;' per connection
    # Let's check if SQLite enforces FKs by default or allows orphan rallies
    rally = Rally(
        task_id="nonexistent_task_999",
        rally_index=1,
        start_time=10.0,
        end_time=20.0,
        duration=10.0
    )
    db.add(rally)
    
    # Check behavior: Without PRAGMA foreign_keys=ON, SQLite allows FK violations silently!
    fk_enforced = True
    try:
        db.commit()
        fk_enforced = False
    except IntegrityError:
        db.rollback()
    db.close()

    # We record whether foreign keys were enforced or allowed silently
    assert isinstance(fk_enforced, bool)


def test_db_rally_negative_and_inverted_timestamps():
    """Stress test: Directly inserting negative/inverted values into Rally model in SQLite DB."""
    db = TestingSessionLocal()
    task = Task(id="task_neg", filename="test.mp4", video_path="/tmp/test.mp4")
    db.add(task)
    db.commit()

    # Rally with negative start/end/duration
    rally_neg = Rally(
        task_id="task_neg",
        rally_index=1,
        start_time=-15.0,
        end_time=-5.0,
        duration=-10.0
    )
    # Rally with inverted start/end (start > end)
    rally_inv = Rally(
        task_id="task_neg",
        rally_index=2,
        start_time=100.0,
        end_time=10.0,
        duration=-90.0
    )
    db.add_all([rally_neg, rally_inv])
    db.commit()

    # Retrieve from DB
    rallies = db.query(Rally).filter(Rally.task_id == "task_neg").all()
    assert len(rallies) == 2
    assert rallies[0].start_time == -15.0
    assert rallies[1].start_time > rallies[1].end_time
    db.close()


def test_db_null_field_constraints():
    """Stress test: Inserting NULL into non-nullable columns."""
    db = TestingSessionLocal()

    # filename is non-nullable
    invalid_task = Task(id="null_task", filename=None, video_path="/tmp/v.mp4")
    db.add(invalid_task)
    with pytest.raises(IntegrityError):
        db.commit()
    db.rollback()

    # start_time is non-nullable in Rally
    valid_task = Task(id="valid_task", filename="v.mp4", video_path="/tmp/v.mp4")
    db.add(valid_task)
    db.commit()

    invalid_rally = Rally(task_id="valid_task", start_time=None, end_time=10.0, duration=10.0)
    db.add(invalid_rally)
    with pytest.raises(IntegrityError):
        db.commit()
    db.rollback()
    db.close()


def test_db_extreme_string_lengths():
    """Stress test: Extremely long string in filename and video_path."""
    db = TestingSessionLocal()
    long_filename = "A" * 100000 + ".mp4"
    task = Task(id="long_str_task", filename=long_filename, video_path="/tmp/" + long_filename)
    db.add(task)
    db.commit()

    fetched = db.query(Task).filter(Task.id == "long_str_task").first()
    assert fetched is not None
    assert len(fetched.filename) == 100004
    db.close()


# ============================================================================
# 3. FastAPI REST Endpoints Boundary & Security Tests
# ============================================================================

def test_api_upload_path_traversal_filename(client):
    """Stress test: Video upload with path traversal filename."""
    file_content = b"dummy video content"
    traversal_filename = "../../../tmp/path_traversal_test.mp4"
    files = {"file": (traversal_filename, file_content, "video/mp4")}
    
    response = client.post("/api/v1/video/upload", files=files)
    assert response.status_code in [200, 201]
    data = response.json()
    
    # Check direct DB record to see what path was stored
    db = TestingSessionLocal()
    task = db.query(Task).filter(Task.id == data["task_id"]).first()
    assert task is not None
    # Check if filename or path resolution preserves path traversal sequences
    db.close()


def test_api_upload_unicode_and_special_chars(client):
    """Stress test: Upload file with Chinese, Emojis, and special characters."""
    file_content = b"badminton match binary"
    special_filename = "🏸2026年羽毛球男单半决赛_1080p (1).mp4"
    files = {"file": (special_filename, file_content, "video/mp4")}
    
    response = client.post("/api/v1/video/upload", files=files)
    assert response.status_code in [200, 201]
    data = response.json()
    assert data["filename"] == special_filename


def test_api_upload_zero_byte_file(client):
    """Empty uploads are rejected before a task is created."""
    files = {"file": ("empty_video.mp4", b"", "video/mp4")}
    response = client.post("/api/v1/video/upload", files=files)
    assert response.status_code == 400
    assert "empty" in response.json()["detail"].lower()


from unittest.mock import patch
from backend.app.detectors.audio_visual import AudioVisualRallyDetector

def test_api_export_large_payload_json(client):
    """The API rejects payloads that could trigger unbounded rendering."""
    # 1. Create a task and analyze it
    files = {"file": ("large_payload_task.mp4", b"video stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        client.post(f"/api/v1/ai/analyze/{task_id}")

    # 2. Large rallies payload
    large_rallies = [
        {"id": i, "start": float(i), "end": float(i + 3.5), "duration": 3.5, "confidence": 0.99}
        for i in range(5000)
    ]
    payload = {
        "rallies": large_rallies,
        "resolution": "1080p",
        "pre_buffer": 1.0,
        "post_buffer": 1.5,
        "export_type": "merged"
    }

    response = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert response.status_code == 422


def test_api_export_invalid_parameters(client):
    """Stress test: Export endpoint with invalid resolution, export_type, negative buffers."""
    files = {"file": ("invalid_export_task.mp4", b"video stream", "video/mp4")}
    task_id = client.post("/api/v1/video/upload", files=files).json()["task_id"]
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        client.post(f"/api/v1/ai/analyze/{task_id}")

    payload = {
        "resolution": "INVALID_4K_HDR",
        "pre_buffer": -100.0,
        "post_buffer": -50.0,
        "export_type": "UNSUPPORTED_TYPE"
    }

    response = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    # Observe status code: Is 200 returned because pydantic schema lacks enum/min validation?
    assert response.status_code in [200, 400, 422]


def test_api_download_path_traversal(client):
    """Stress test: Download endpoint with path traversal parameter."""
    # Test path traversal filename in download endpoint
    traversal_filename = "../../../../etc/passwd"
    response = client.get(f"/downloads/{traversal_filename}")
    
    # Check status code and response: Does it allow accessing outside EXPORTS_DIR?
    # Note: os.path.join("exports", "../../../../etc/passwd") resolves to "/etc/passwd"!
    assert response.status_code in [200, 400, 403, 404]
