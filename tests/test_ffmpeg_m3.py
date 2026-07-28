import os
import shutil
import tempfile
import zipfile
import subprocess
from unittest.mock import patch
import pytest

from backend.app.services.ffmpeg_service import ffmpeg_service, FFmpegService

@pytest.fixture(scope="module")
def synth_video_path():
    """
    创建一个包含音频与视频的 15 秒合成 MP4 测试视频文件
    """
    temp_dir = tempfile.mkdtemp()
    video_path = os.path.join(temp_dir, "synth_input.mp4")
    cmd = [
        "ffmpeg", "-y",
        "-f", "lavfi", "-i", "testsrc=size=1920x1080:rate=30",
        "-f", "lavfi", "-i", "sine=frequency=1000:duration=15",
        "-t", "15",
        "-c:v", "libx264", "-c:a", "aac",
        video_path
    ]
    try:
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    except Exception:
        # If ffmpeg is absent or fails, write mock video stream
        with open(video_path, "wb") as f:
            f.write(b"MOCK_VIDEO_DATA")

    yield video_path

    if os.path.exists(temp_dir):
        shutil.rmtree(temp_dir, ignore_errors=True)

# -----------------------------------------------------------------------------
# 1. Hardware Acceleration & Detection Tests
# -----------------------------------------------------------------------------

def test_detect_hwaccel_returns_valid_string():
    hwaccel = ffmpeg_service.detect_hwaccel()
    assert hwaccel in {"h264_videotoolbox", "h264_nvenc", "libx264"}


def test_scale_filter_never_requests_source_upscaling():
    scale_filter = ffmpeg_service._scale_filter("1280:720")
    assert "min(iw,1280)" in scale_filter
    assert "min(ih,720)" in scale_filter
    assert "pad=" not in scale_filter

def test_detect_hwaccel_fallback_on_subprocess_error():
    with patch("subprocess.run", side_effect=Exception("Subprocess error")):
        service = FFmpegService()
        hwaccel = service.detect_hwaccel()
        assert hwaccel == "libx264"

# -----------------------------------------------------------------------------
# 2. Buffer Padding & Segment Merging Tests
# -----------------------------------------------------------------------------

def test_apply_buffer_padding_basic():
    rallies = [
        {"start": 3.0, "end": 6.0},
        {"start": 10.0, "end": 14.0}
    ]
    padded = ffmpeg_service.apply_buffer_padding(rallies, pre_buffer=1.0, post_buffer=1.5, video_duration=20.0)
    assert len(padded) == 2
    assert padded[0] == {"start": 2.0, "end": 7.5, "duration": 5.5}
    assert padded[1] == {"start": 9.0, "end": 15.5, "duration": 6.5}

def test_apply_buffer_padding_clamping_and_overlap_merging():
    # Rally 1: 0.5 to 4.0 -> padded [0.0, 5.5] (clamped 0.5 - 1.0 -> 0.0)
    # Rally 2: 4.5 to 8.0 -> padded [3.5, 9.5] -> overlaps with Rally 1!
    # Merged segment: [0.0, 9.5]
    # Rally 3: 18.0 to 20.0 -> padded [17.0, 20.0] (clamped 20.0 + 1.5 -> 20.0 max duration)
    rallies = [
        {"start": 0.5, "end": 4.0},
        {"start": 4.5, "end": 8.0},
        {"start": 18.0, "end": 20.0}
    ]
    padded = ffmpeg_service.apply_buffer_padding(rallies, pre_buffer=1.0, post_buffer=1.5, video_duration=20.0)
    assert len(padded) == 2
    assert padded[0] == {"start": 0.0, "end": 9.5, "duration": 9.5}
    assert padded[1] == {"start": 17.0, "end": 20.0, "duration": 3.0}

def test_apply_buffer_padding_contiguous_merging():
    # Segment 1 end == Segment 2 start -> contiguous, should merge
    rallies = [
        {"start_time": 2.0, "end_time": 4.0},
        {"start_time": 6.5, "end_time": 8.0}
    ]
    padded = ffmpeg_service.apply_buffer_padding(rallies, pre_buffer=1.0, post_buffer=1.5)
    assert len(padded) == 1
    assert padded[0] == {"start": 1.0, "end": 9.5, "duration": 8.5}

def test_apply_buffer_padding_empty_and_tuple_formats():
    assert ffmpeg_service.apply_buffer_padding([]) == []

    tuples_input = [(2.0, 5.0), (10.0, 12.0)]
    padded = ffmpeg_service.apply_buffer_padding(tuples_input, pre_buffer=0.5, post_buffer=0.5)
    assert len(padded) == 2
    assert padded[0]["start"] == 1.5
    assert padded[0]["end"] == 5.5

# -----------------------------------------------------------------------------
# 3. Real FFmpeg Processing Tests (Merged MP4 & ZIP Package)
# -----------------------------------------------------------------------------

def test_process_and_export_merged_mp4(synth_video_path):
    with tempfile.TemporaryDirectory() as out_dir:
        output_file = os.path.join(out_dir, "test_merged.mp4")
        rallies = [
            {"start": 1.0, "end": 4.0},
            {"start": 7.0, "end": 10.0}
        ]
        res_path = ffmpeg_service.process_and_export(
            input_video=synth_video_path,
            rallies=rallies,
            output_path=output_file,
            resolution="1080p",
            pre_buffer=1.0,
            post_buffer=1.0,
            export_type="merged"
        )

        assert isinstance(res_path, str)
        assert res_path == output_file
        assert os.path.exists(res_path)
        assert os.path.getsize(res_path) > 0

        # Verify exported MP4 duration with ffprobe if ffmpeg is available
        if ffmpeg_service.is_ffmpeg_available():
            probe_cmd = [
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                res_path
            ]
            probe_res = subprocess.run(probe_cmd, stdout=subprocess.PIPE, text=True, check=True)
            out_duration = float(probe_res.stdout.strip())
            # Segment 1: [0.0, 5.0] (5s), Segment 2: [6.0, 11.0] (5s) -> Expected duration ~10s
            assert 9.0 <= out_duration <= 11.0

def test_process_and_export_zip_archive(synth_video_path):
    with tempfile.TemporaryDirectory() as out_dir:
        output_file = os.path.join(out_dir, "test_rallies.zip")
        rallies = [
            {"start": 1.0, "end": 3.0},
            {"start": 8.0, "end": 11.0}
        ]
        res_path = ffmpeg_service.process_and_export(
            input_video=synth_video_path,
            rallies=rallies,
            output_path=output_file,
            resolution="720p",
            pre_buffer=0.5,
            post_buffer=0.5,
            export_type="zip"
        )

        assert isinstance(res_path, str)
        assert res_path == output_file
        assert os.path.exists(res_path)
        assert os.path.getsize(res_path) > 0

        # Inspect ZIP archive contents
        with zipfile.ZipFile(res_path, "r") as zf:
            namelist = zf.namelist()
            assert len(namelist) == 2
            assert "rally_01.mp4" in namelist
            assert "rally_02.mp4" in namelist

            if ffmpeg_service.is_ffmpeg_available():
                extracted_path = zf.extract("rally_01.mp4", path=out_dir)
                probe_cmd = [
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    extracted_path
                ]
                probe_res = subprocess.run(probe_cmd, stdout=subprocess.PIPE, text=True, check=True)
                clip1_duration = float(probe_res.stdout.strip())
                # Segment 1: [0.5, 3.5] -> Expected 3s
                assert 2.5 <= clip1_duration <= 3.5

def test_process_and_export_compression_presets(synth_video_path):
    with tempfile.TemporaryDirectory() as out_dir:
        rallies = [{"start": 1.0, "end": 3.0}]

        # Test 1080p
        file_1080 = os.path.join(out_dir, "video_1080p.mp4")
        res_1080 = ffmpeg_service.process_and_export(
            input_video=synth_video_path, rallies=rallies, output_path=file_1080, resolution="1080p"
        )
        assert os.path.exists(res_1080)
        if ffmpeg_service.is_ffmpeg_available():
            probe_1080 = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", res_1080],
                stdout=subprocess.PIPE, text=True, check=True
            )
            assert "1920,1080" in probe_1080.stdout.strip()

        # Test 720p
        file_720 = os.path.join(out_dir, "video_720p.mp4")
        res_720 = ffmpeg_service.process_and_export(
            input_video=synth_video_path, rallies=rallies, output_path=file_720, resolution="720p"
        )
        assert os.path.exists(res_720)
        if ffmpeg_service.is_ffmpeg_available():
            probe_720 = subprocess.run(
                ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height", "-of", "csv=p=0", res_720],
                stdout=subprocess.PIPE, text=True, check=True
            )
            assert "1280,720" in probe_720.stdout.strip()

        # Test original
        file_orig = os.path.join(out_dir, "video_orig.mp4")
        res_orig = ffmpeg_service.process_and_export(
            input_video=synth_video_path, rallies=rallies, output_path=file_orig, resolution="original"
        )
        assert os.path.exists(res_orig)

def test_process_and_export_nonexistent_input():
    with pytest.raises(FileNotFoundError):
        ffmpeg_service.process_and_export(
            input_video="/nonexistent/path/video.mp4",
            rallies=[{"start": 1.0, "end": 5.0}],
            output_path="/tmp/output.mp4"
        )

def test_process_and_export_empty_rallies(synth_video_path):
    with pytest.raises(ValueError):
        ffmpeg_service.process_and_export(
            input_video=synth_video_path,
            rallies=[],
            output_path="/tmp/output.mp4"
        )

def test_process_and_export_mock_mode(synth_video_path):
    with patch.dict(os.environ, {"FFMPEG_MOCK": "1"}):
        with tempfile.TemporaryDirectory() as out_dir:
            mp4_out = os.path.join(out_dir, "mock_export.mp4")
            res_mp4 = ffmpeg_service.process_and_export(
                input_video=synth_video_path,
                rallies=[{"start": 1.0, "end": 3.0}],
                output_path=mp4_out,
                export_type="merged"
            )
            assert os.path.exists(res_mp4)
            assert os.path.getsize(res_mp4) > 0

            zip_out = os.path.join(out_dir, "mock_export.zip")
            res_zip = ffmpeg_service.process_and_export(
                input_video=synth_video_path,
                rallies=[{"start": 1.0, "end": 3.0}, {"start": 5.0, "end": 7.0}],
                output_path=zip_out,
                export_type="zip"
            )
            assert os.path.exists(res_zip)
            with zipfile.ZipFile(res_zip, "r") as zf:
                assert len(zf.namelist()) == 2
                assert "rally_01.mp4" in zf.namelist()

def test_hwaccel_fallback_to_libx264(synth_video_path):
    with tempfile.TemporaryDirectory() as out_dir:
        output_file = os.path.join(out_dir, "hwaccel_fallback.mp4")
        # Mock detect_hwaccel to return h264_videotoolbox, but make the first subprocess.run fail
        orig_run = subprocess.run

        call_count = 0
        def fake_run(cmd, *args, **kwargs):
            nonlocal call_count
            call_count += 1
            if "-c:v" in cmd and "h264_videotoolbox" in cmd:
                raise subprocess.CalledProcessError(1, cmd, stderr="Hardware encoder error")
            return orig_run(cmd, *args, **kwargs)

        with patch.object(ffmpeg_service, "detect_hwaccel", return_value="h264_videotoolbox"):
            with patch("subprocess.run", side_effect=fake_run):
                res_path = ffmpeg_service.process_and_export(
                    input_video=synth_video_path,
                    rallies=[{"start": 1.0, "end": 3.0}],
                    output_path=output_file,
                    resolution="1080p"
                )
                assert os.path.exists(res_path)
