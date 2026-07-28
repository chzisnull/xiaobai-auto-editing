import os
import shutil
import tempfile
import zipfile
import subprocess
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine, event
from sqlalchemy.orm import sessionmaker

from backend.app.main import app
from backend.app.db.session import Base, get_db, set_sqlite_pragma
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.services.ffmpeg_service import ffmpeg_service

# Setup test DB
TEST_DB_FILE = tempfile.NamedTemporaryFile(suffix="_stress_m3_1.db", delete=False).name
SQLALCHEMY_TEST_DATABASE_URL = f"sqlite:///{TEST_DB_FILE}"
engine = create_engine(SQLALCHEMY_TEST_DATABASE_URL, connect_args={"check_same_thread": False})
event.listens_for(engine, "connect")(set_sqlite_pragma)
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)

def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()

@pytest.fixture(autouse=True)
def setup_test_database():
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

@pytest.fixture(scope="module")
def synth_50s_video():
    """
    Creates a 50-second synthetic 1920x1080 MP4 video with sine audio track.
    """
    temp_dir = tempfile.mkdtemp()
    video_path = os.path.join(temp_dir, "synth_50s.mp4")
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=1000:duration=50",
        "-t", "50",
        "-c:v", "libx264", "-c:a", "aac",
        video_path
    ]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    yield video_path

    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir, ignore_errors=True)

@pytest.fixture(scope="module")
def synth_vertical_video():
    """
    Creates a 20-second vertical video (720x1280).
    """
    temp_dir = tempfile.mkdtemp()
    video_path = os.path.join(temp_dir, "synth_vert.mp4")
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "testsrc=size=720x1280:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=800:duration=20",
        "-t", "20",
        "-c:v", "libx264", "-c:a", "aac",
        video_path
    ]
    subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    yield video_path

    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir, ignore_errors=True)


# =============================================================================
# 1. Overlapping / Contiguous Rally Timestamps & Buffer Padding Stress Tests
# =============================================================================

def test_overlapping_rally_timestamps_merging_algorithm():
    """
    Test prompt specified overlapping timestamps:
    [(10.0, 20.0), (19.0, 28.0), (27.5, 35.0)] with pre_buffer=2.0, post_buffer=2.0.
    """
    rallies = [(10.0, 20.0), (19.0, 28.0), (27.5, 35.0)]
    padded = ffmpeg_service.apply_buffer_padding(
        rallies=rallies,
        pre_buffer=2.0,
        post_buffer=2.0,
        video_duration=50.0
    )

    # R1 padded: [8.0, 22.0]
    # R2 padded: [17.0, 30.0] -> merges with R1 to [8.0, 30.0]
    # R3 padded: [25.5, 37.0] -> merges with R1/R2 to [8.0, 37.0]
    assert len(padded) == 1
    assert padded[0] == {"start": 8.0, "end": 37.0, "duration": 29.0}

def test_out_of_order_overlapping_timestamps():
    """
    Test out of order input segments are properly sorted and merged.
    """
    rallies = [(27.5, 35.0), (10.0, 20.0), (19.0, 28.0)]
    padded = ffmpeg_service.apply_buffer_padding(
        rallies=rallies,
        pre_buffer=2.0,
        post_buffer=2.0,
        video_duration=50.0
    )
    assert len(padded) == 1
    assert padded[0] == {"start": 8.0, "end": 37.0, "duration": 29.0}

def test_exact_contiguous_timestamps_merging():
    """
    Test contiguous boundaries (start == end of previous segment).
    """
    rallies = [(5.0, 10.0), (12.0, 15.0)] # pre=1.0, post=1.0 -> [4.0, 11.0] and [11.0, 16.0]
    padded = ffmpeg_service.apply_buffer_padding(
        rallies=rallies,
        pre_buffer=1.0,
        post_buffer=1.0,
        video_duration=50.0
    )
    assert len(padded) == 1
    assert padded[0] == {"start": 4.0, "end": 16.0, "duration": 12.0}

def test_fully_nested_timestamps_merging():
    """
    Test nested intervals: Rally 1 wraps Rally 2 and Rally 3.
    """
    rallies = [(5.0, 40.0), (10.0, 20.0), (15.0, 25.0)]
    padded = ffmpeg_service.apply_buffer_padding(
        rallies=rallies,
        pre_buffer=2.0,
        post_buffer=2.0,
        video_duration=50.0
    )
    assert len(padded) == 1
    assert padded[0] == {"start": 3.0, "end": 42.0, "duration": 39.0}

def test_boundary_clamping_at_zero_and_video_duration():
    """
    Test timestamps exceeding 0.0 or video_duration are correctly clamped.
    """
    rallies = [(0.5, 5.0), (45.0, 52.0)]
    padded = ffmpeg_service.apply_buffer_padding(
        rallies=rallies,
        pre_buffer=2.0,
        post_buffer=2.0,
        video_duration=50.0
    )
    # R1: max(0.0, 0.5-2.0)=0.0, 5.0+2.0=7.0 -> [0.0, 7.0]
    # R2: 45.0-2.0=43.0, min(50.0, 52.0+2.0)=50.0 -> [43.0, 50.0]
    assert len(padded) == 2
    assert padded[0] == {"start": 0.0, "end": 7.0, "duration": 7.0}
    assert padded[1] == {"start": 43.0, "end": 50.0, "duration": 7.0}

def test_real_ffmpeg_export_with_overlapping_merged_segments(synth_50s_video):
    """
    Empirically execute FFmpeg on overlapping timestamps [(10.0, 20.0), (19.0, 28.0), (27.5, 35.0)] +2.0s padding.
    Verify export output duration with ffprobe.
    """
    with tempfile.TemporaryDirectory() as out_dir:
        rallies = [(10.0, 20.0), (19.0, 28.0), (27.5, 35.0)]
        res = ffmpeg_service.process_and_export(
            input_video_path=synth_50s_video,
            rallies=rallies,
            output_dir=out_dir,
            resolution="1080p",
            pre_buffer=2.0,
            post_buffer=2.0,
            export_type="merged"
        )
        assert os.path.exists(res)

        # Check with ffprobe
        probe_cmd = [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            res
        ]
        probe_res = subprocess.run(probe_cmd, stdout=subprocess.PIPE, text=True, check=True)
        out_duration = float(probe_res.stdout.strip())
        assert 28.5 <= out_duration <= 29.5


# =============================================================================
# 2. Dual Export Modes Integrity Stress Tests (Merged MP4 & ZIP Archive)
# =============================================================================

def test_export_type_merged_integrity(synth_50s_video):
    """
    Verify export_type='merged' produces valid MP4 playable by ffprobe with audio and video streams.
    """
    with tempfile.TemporaryDirectory() as out_dir:
        rallies = [(2.0, 8.0), (15.0, 22.0)]
        res = ffmpeg_service.process_and_export(
            input_video_path=synth_50s_video,
            rallies=rallies,
            output_dir=out_dir,
            resolution="1080p",
            pre_buffer=1.0,
            post_buffer=1.0,
            export_type="merged"
        )

        assert res.endswith(".mp4")
        filepath = res
        assert os.path.exists(filepath)

        # 1. ffprobe format validation
        probe_format = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=format_name,duration", "-of", "default=noprint_wrappers=1", filepath],
            stdout=subprocess.PIPE, text=True, check=True
        )
        assert "mp4" in probe_format.stdout or "mov" in probe_format.stdout

        # 2. ffprobe video stream validation
        probe_video = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=codec_name,width,height", "-of", "csv=p=0", filepath],
            stdout=subprocess.PIPE, text=True, check=True
        )
        video_info = probe_video.stdout.strip()
        assert "h264" in video_info

        # 3. ffprobe audio stream validation
        probe_audio = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", filepath],
            stdout=subprocess.PIPE, text=True, check=True
        )
        audio_info = probe_audio.stdout.strip()
        assert "aac" in audio_info

def test_export_type_zip_integrity(synth_50s_video):
    """
    Verify export_type='zip' produces valid non-corrupted ZIP archive readable by zipfile.ZipFile,
    and each internal MP4 clip is valid and readable by ffprobe.
    """
    with tempfile.TemporaryDirectory() as out_dir:
        rallies = [(2.0, 6.0), (15.0, 19.0), (30.0, 34.0)] # 3 distinct segments
        res = ffmpeg_service.process_and_export(
            input_video_path=synth_50s_video,
            rallies=rallies,
            output_dir=out_dir,
            resolution="720p",
            pre_buffer=1.0,
            post_buffer=1.0,
            export_type="zip"
        )

        assert res.endswith(".zip")
        filepath = res
        assert os.path.exists(filepath)

        # 1. ZipFile read & integrity test (testzip returns None if no CRC error)
        with zipfile.ZipFile(filepath, "r") as zf:
            corrupt = zf.testzip()
            assert corrupt is None, f"ZIP file corrupted: {corrupt}"

            namelist = zf.namelist()
            assert len(namelist) == 3
            assert sorted(namelist) == ["rally_01.mp4", "rally_02.mp4", "rally_03.mp4"]

            # Extract clips and inspect with ffprobe
            extract_dir = os.path.join(out_dir, "extracted")
            zf.extractall(extract_dir)

            for fname in namelist:
                clip_p = os.path.join(extract_dir, fname)
                probe_res = subprocess.run(
                    ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", clip_p],
                    stdout=subprocess.PIPE, text=True, check=True
                )
                dur = float(probe_res.stdout.strip())
                # Each segment 4s + 1s pre + 1s post = 6s
                assert 5.5 <= dur <= 6.5


# =============================================================================
# 3. Resolution Presets & Size Scaling Stress Tests
# =============================================================================

def test_resolution_presets_scaling_and_filesize_comparison(synth_50s_video):
    """
    Verify 1080p, 720p, and original presets scale video dimensions correctly
    and file size scaling holds (720p size < 1080p size).
    """
    with tempfile.TemporaryDirectory() as out_dir:
        rallies = [(5.0, 15.0), (25.0, 35.0)]

        # 1080p
        res_1080 = ffmpeg_service.process_and_export(
            input_video_path=synth_50s_video, rallies=rallies, output_dir=out_dir, resolution="1080p"
        )
        probe_1080 = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", res_1080],
            stdout=subprocess.PIPE, text=True, check=True
        )
        assert probe_1080.stdout.strip() == "1920,1080"

        # 720p
        res_720 = ffmpeg_service.process_and_export(
            input_video_path=synth_50s_video, rallies=rallies, output_dir=out_dir, resolution="720p"
        )
        probe_720 = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", res_720],
            stdout=subprocess.PIPE, text=True, check=True
        )
        assert probe_720.stdout.strip() == "1280,720"

        # original
        res_orig = ffmpeg_service.process_and_export(
            input_video_path=synth_50s_video, rallies=rallies, output_dir=out_dir, resolution="original"
        )
        probe_orig = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", res_orig],
            stdout=subprocess.PIPE, text=True, check=True
        )
        assert probe_orig.stdout.strip() == "1920,1080"

        # Size scaling validation: 720p size should be smaller than 1080p
        assert os.path.getsize(res_720) < os.path.getsize(res_1080)

def test_resolution_scaling_vertical_video(synth_vertical_video):
    """
    Test resolution scaling when source is vertical video (720x1280).
    """
    with tempfile.TemporaryDirectory() as out_dir:
        rallies = [(2.0, 8.0)]
        res_1080 = ffmpeg_service.process_and_export(
            input_video_path=synth_vertical_video,
            rallies=rallies,
            output_dir=out_dir,
            resolution="1080p"
        )
        probe_out = subprocess.run(
            ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", res_1080],
            stdout=subprocess.PIPE, text=True, check=True
        )
        width, height = map(int, probe_out.stdout.strip().split(","))
        assert width <= 720
        assert height <= 1080
        assert height == 1080


# =============================================================================
# 4. API Endpoints Integration Stress Tests
# =============================================================================

def test_api_export_merged_and_zip_endpoints(client, db, tmp_path, monkeypatch):
    """
    Full end-to-end API test for post export endpoints.
    """
    monkeypatch.setenv("FFMPEG_MOCK", "1")
    task_id = "test_task_m3_stress_api"
    source = tmp_path / "stress_match.mp4"
    source.write_bytes(b"mock source")
    task = Task(id=task_id, filename=source.name, video_path=str(source), status="completed")
    r1 = Rally(task_id=task_id, rally_index=1, start_time=2.0, end_time=6.0, duration=4.0)
    r2 = Rally(task_id=task_id, rally_index=2, start_time=5.0, end_time=9.0, duration=4.0) # Overlapping!
    db.add(task)
    db.add_all([r1, r2])
    db.commit()

    # Merged export
    res_merged = client.post(
        f"/api/v1/video/export/{task_id}",
        json={"resolution": "1080p", "pre_buffer": 2.0, "post_buffer": 2.0, "export_type": "merged"}
    )
    assert res_merged.status_code == 200
    assert res_merged.headers["content-type"] == "video/mp4"
    assert res_merged.headers["cache-control"] == "no-store"
    assert res_merged.headers["x-export-filename"].endswith(".mp4")

    # ZIP export
    res_zip = client.post(
        f"/api/v1/video/export/{task_id}",
        json={"resolution": "720p", "pre_buffer": 1.0, "post_buffer": 1.0, "export_type": "zip"}
    )
    assert res_zip.status_code == 200
    assert res_zip.headers["content-type"] == "application/zip"
    assert res_zip.headers["cache-control"] == "no-store"
    assert res_zip.headers["x-export-filename"].endswith(".zip")
