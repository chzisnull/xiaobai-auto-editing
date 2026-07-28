import tempfile
import pytest
from pydantic import ValidationError
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from backend.app.main import app
from backend.app.db.session import Base, get_db, set_sqlite_pragma
from backend.app.schemas.rally import RallyItem
from backend.app.schemas.export import ExportRequest

# Setup isolated temporary SQLite DB for boundary tests
TEST_DB_FILE = tempfile.NamedTemporaryFile(suffix="_boundary.db", delete=False).name
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
    monkeypatch.setenv("FFMPEG_MOCK", "1")
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
# 1. RallyItem Pydantic Schema Boundary Value Tests
# ============================================================================

def test_rally_item_negative_start_time():
    """Verify that RallyItem rejects negative start_time (start_time=-1.0)."""
    with pytest.raises(ValidationError) as exc_info:
        RallyItem(
            rally_index=1,
            start_time=-1.0,
            end_time=5.0,
            duration=6.0,
            confidence=0.9
        )
    errors = exc_info.value.errors()
    assert any(e["loc"] == ("start_time",) for e in errors)


def test_rally_item_negative_end_time():
    """Verify that RallyItem rejects negative end_time via timestamp validation."""
    with pytest.raises(ValidationError) as exc_info:
        RallyItem(
            rally_index=1,
            start_time=0.0,
            end_time=-1.0,
            duration=1.0,
            confidence=0.9
        )
    assert "end_time" in str(exc_info.value)


def test_rally_item_inverted_bounds():
    """Verify that RallyItem rejects inverted bounds (start_time > end_time)."""
    with pytest.raises(ValidationError) as exc_info:
        RallyItem(
            rally_index=1,
            start_time=10.0,
            end_time=5.0,
            duration=-5.0,
            confidence=0.8
        )
    assert "end_time (5.0) must be strictly greater than start_time (10.0)" in str(exc_info.value)


def test_rally_item_equal_bounds():
    """Verify that RallyItem rejects equal bounds (start_time == end_time)."""
    with pytest.raises(ValidationError) as exc_info:
        RallyItem(
            rally_index=1,
            start_time=5.0,
            end_time=5.0,
            duration=0.0,
            confidence=0.8
        )
    assert "end_time (5.0) must be strictly greater than start_time (5.0)" in str(exc_info.value)


def test_rally_item_valid_boundary():
    """Verify valid minimum non-negative boundary values for RallyItem."""
    item = RallyItem(
        rally_index=1,
        start_time=0.0,
        end_time=0.001,
        duration=0.001,
        confidence=1.0
    )
    assert item.start_time == 0.0
    assert item.end_time == 0.001
    assert item.start == 0.0
    assert item.end == 0.001


def test_rally_item_nan_and_inf_floats():
    """Verify that NaN and Infinity float values are rejected by RallyItem."""
    with pytest.raises(ValidationError):
        RallyItem(rally_index=1, start_time=float('nan'), end_time=10.0, duration=10.0)

    with pytest.raises(ValidationError):
        RallyItem(rally_index=1, start_time=0.0, end_time=float('inf'), duration=float('inf'))


def test_rally_item_confidence_range():
    """Verify confidence is constrained within [0.0, 1.0]."""
    with pytest.raises(ValidationError):
        RallyItem(rally_index=1, start_time=0.0, end_time=5.0, duration=5.0, confidence=-0.1)

    with pytest.raises(ValidationError):
        RallyItem(rally_index=1, start_time=0.0, end_time=5.0, duration=5.0, confidence=1.05)


# ============================================================================
# 2. ExportRequest Pydantic Schema Boundary Value Tests
# ============================================================================

def test_export_request_invalid_resolution():
    """Verify that ExportRequest rejects invalid resolution string ('INVALID_8K')."""
    with pytest.raises(ValidationError) as exc_info:
        ExportRequest(
            resolution="INVALID_8K",
            export_type="merged",
            pre_buffer=1.0,
            post_buffer=1.5
        )
    assert "resolution must be one of" in str(exc_info.value)


def test_export_request_valid_resolutions():
    """Verify that valid resolutions ('original', '1080p', '720p', '1080P') are accepted and normalized."""
    req1 = ExportRequest(resolution="original")
    assert req1.resolution == "original"

    req2 = ExportRequest(resolution="720p")
    assert req2.resolution == "720p"

    req3 = ExportRequest(resolution="1080P")
    assert req3.resolution == "1080p"


def test_export_request_negative_buffers():
    """Verify that ExportRequest rejects negative pre_buffer and post_buffer."""
    with pytest.raises(ValidationError):
        ExportRequest(pre_buffer=-1.0, post_buffer=1.5)

    with pytest.raises(ValidationError):
        ExportRequest(pre_buffer=1.0, post_buffer=-0.5)


def test_export_request_invalid_export_type():
    """Verify that ExportRequest rejects unsupported export_type."""
    with pytest.raises(ValidationError) as exc_info:
        ExportRequest(export_type="unsupported_mkv")
    assert "export_type must be one of" in str(exc_info.value)


def test_export_request_valid_export_types():
    """Verify that valid export types ('merged', 'zip', 'ZIP') are accepted and normalized."""
    req1 = ExportRequest(export_type="merged")
    assert req1.export_type == "merged"

    req2 = ExportRequest(export_type="ZIP")
    assert req2.export_type == "zip"


# ============================================================================
# 3. Endpoint HTTP Response Boundary Tests
# ============================================================================

def test_endpoint_export_invalid_resolution_rejected_http422(client):
    """Verify that POST /api/v1/video/export/{task_id} rejects invalid resolution ('INVALID_8K') with HTTP 422."""
    payload = {
        "resolution": "INVALID_8K",
        "export_type": "merged",
        "pre_buffer": 1.0,
        "post_buffer": 1.5
    }
    response = client.post("/api/v1/video/export/dummy_task_id", json=payload)
    assert response.status_code == 422
    data = response.json()
    assert "detail" in data
    assert any("resolution" in str(err.get("loc", [])) for err in data["detail"])


def test_endpoint_export_negative_buffer_rejected_http422(client):
    """Verify that POST /api/v1/video/export/{task_id} rejects negative pre_buffer (-1.0) with HTTP 422."""
    payload = {
        "resolution": "1080p",
        "export_type": "merged",
        "pre_buffer": -1.0,
        "post_buffer": 1.5
    }
    response = client.post("/api/v1/video/export/dummy_task_id", json=payload)
    assert response.status_code == 422


def test_endpoint_export_invalid_export_type_rejected_http422(client):
    """Verify that POST /api/v1/video/export/{task_id} rejects invalid export_type ('RAW_AVI') with HTTP 422."""
    payload = {
        "resolution": "1080p",
        "export_type": "RAW_AVI",
        "pre_buffer": 1.0,
        "post_buffer": 1.5
    }
    response = client.post("/api/v1/video/export/dummy_task_id", json=payload)
    assert response.status_code == 422


def test_endpoint_export_unanalyzed_task_rejected_http400(client):
    """Verify that POST /api/v1/video/export/{task_id} for an unanalyzed task is rejected with HTTP 400."""
    # 1. Upload video (creates task with status 'uploaded' and 0 rallies)
    files = {"file": ("unanalyzed.mp4", b"raw video stream", "video/mp4")}
    upload_res = client.post("/api/v1/video/upload", files=files)
    assert upload_res.status_code in [200, 201]
    task_id = upload_res.json()["task_id"]

    # 2. Attempt export before running AI analysis
    payload = {
        "resolution": "1080p",
        "export_type": "merged",
        "pre_buffer": 1.0,
        "post_buffer": 1.5
    }
    export_res = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert export_res.status_code == 400
    detail = export_res.json()["detail"]
    assert f"Task with id '{task_id}' is not ready for export" in detail


def test_endpoint_export_unanalyzed_task_invalid_payload_rejected_http422(client):
    """Verify that an unanalyzed task export with invalid payload fails schema validation (HTTP 422) first."""
    files = {"file": ("unanalyzed_invalid.mp4", b"raw video stream", "video/mp4")}
    upload_res = client.post("/api/v1/video/upload", files=files)
    task_id = upload_res.json()["task_id"]

    payload = {
        "resolution": "INVALID_8K",
        "export_type": "merged"
    }
    export_res = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert export_res.status_code == 422


from unittest.mock import patch
from backend.app.detectors.audio_visual import AudioVisualRallyDetector

def test_endpoint_export_analyzed_task_success_http200(client):
    """Verify end-to-end flow: Upload -> Analyze -> Export with valid parameters returns HTTP 200."""
    files = {"file": ("complete_flow.mp4", b"raw video stream", "video/mp4")}
    upload_res = client.post("/api/v1/video/upload", files=files)
    task_id = upload_res.json()["task_id"]

    # Analyze
    with patch("backend.app.services.ai_service.AudioVisualRallyDetector.analyze_video", return_value=AudioVisualRallyDetector._get_mock_rallies()):
        analyze_res = client.post(f"/api/v1/ai/analyze/{task_id}")
    assert analyze_res.status_code == 200

    # Export
    payload = {
        "resolution": "720p",
        "export_type": "zip",
        "pre_buffer": 0.5,
        "post_buffer": 1.0
    }
    export_res = client.post(f"/api/v1/video/export/{task_id}", json=payload)
    assert export_res.status_code == 200
    assert export_res.headers["content-type"] == "application/zip"
    assert export_res.headers["cache-control"] == "no-store"
    assert export_res.headers["x-export-filename"].endswith(".zip")
    assert export_res.content.startswith(b"PK")
