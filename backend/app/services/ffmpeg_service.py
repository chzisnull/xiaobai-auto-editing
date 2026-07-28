import os
import shutil
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple, Union

class FFmpegService:
    """
    FFmpeg 视频剪辑、缓冲扩展、硬件加速与压缩导出引擎 (Milestone 3)
    """
    PRESETS = {
        "original": {"scale": None, "crf": 20, "preset": "fast"},
        "1080p": {"scale": "1920:1080", "crf": 24, "preset": "medium"},
        "720p": {"scale": "1280:720", "crf": 28, "preset": "faster"}
    }

    def is_ffmpeg_available(self) -> bool:
        """
        检查 FFmpeg 是否可用。若设置 FFMPEG_MOCK=1 或未安装 ffmpeg，返回 False。
        """
        if os.getenv("FFMPEG_MOCK", "").lower() in ("1", "true", "yes"):
            return False
        return shutil.which("ffmpeg") is not None

    def detect_hwaccel(self, codec: str = "h264") -> str:
        """
        检查系统可用的 FFmpeg 编码器:
        h264_videotoolbox (macOS), h264_nvenc (NVIDIA), 降级回 libx264。
        """
        software_encoder = "libx265" if codec == "hevc" else "libx264"
        if os.getenv("FFMPEG_HWACCEL", "").lower() not in ("1", "true", "yes"):
            return software_encoder
        if not self.is_ffmpeg_available():
            return software_encoder
        try:
            res = subprocess.run(
                ["ffmpeg", "-encoders"],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                check=False
            )
            stdout = res.stdout or ""
            if codec == "hevc":
                if "hevc_videotoolbox" in stdout:
                    return "hevc_videotoolbox"
                if "hevc_nvenc" in stdout:
                    return "hevc_nvenc"
                return software_encoder
            if "h264_videotoolbox" in stdout:
                return "h264_videotoolbox"
            if "h264_nvenc" in stdout:
                return "h264_nvenc"
        except Exception:
            pass
        return software_encoder

    @staticmethod
    def _scale_filter(scale: Optional[str]) -> str:
        """Scale down to a preset ceiling without enlarging smaller sources."""
        if not scale:
            return ""
        width, height = scale.split(":")
        return (
            f",scale=w='min(iw,{width})':h='min(ih,{height})':"
            "force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1"
        )

    def merge_overlapping_segments(
        self,
        segments: List[Tuple[float, float]]
    ) -> List[Tuple[float, float]]:
        """
        合并重叠或相邻的片段 (Contiguous / Overlapping Segments Merge)
        输入为 [(start, end), ...] 元组列表。
        """
        if not segments:
            return []

        # 按 Start 时间升序排序
        sorted_segs = sorted(segments, key=lambda x: x[0])
        merged: List[Tuple[float, float]] = []

        current_start, current_end = sorted_segs[0]

        for s, e in sorted_segs[1:]:
            if s <= current_end:  # 重叠或相邻
                current_end = max(current_end, e)
            else:
                merged.append((round(current_start, 3), round(current_end, 3)))
                current_start, current_end = s, e

        merged.append((round(current_start, 3), round(current_end, 3)))
        return merged

    def apply_buffer_padding(
        self,
        rallies: List[Any],
        pre_buffer: float = 1.0,
        post_buffer: float = 1.5,
        video_duration: Optional[float] = None
    ) -> List[Dict[str, float]]:
        """
        对 AI 提取的回合时间轴应用前后缓冲扩展 (pre_buffer, post_buffer)，
        限制在 [0.0, video_duration] 范围，并自动合并重叠/相邻片段。
        """
        if not rallies:
            return []

        pre_buf = max(0.0, float(pre_buffer))
        post_buf = max(0.0, float(post_buffer))

        raw_segments = []
        for r in rallies:
            if isinstance(r, dict):
                start = r.get("start", r.get("start_time", 0.0))
                end = r.get("end", r.get("end_time", 0.0))
            elif isinstance(r, (list, tuple)):
                start, end = r[0], r[1]
            else:
                start = getattr(r, "start_time", getattr(r, "start", 0.0))
                end = getattr(r, "end_time", getattr(r, "end", 0.0))

            start_buf = max(0.0, float(start) - pre_buf)
            end_buf = float(end) + post_buf

            if video_duration is not None and video_duration > 0:
                start_buf = min(float(video_duration), start_buf)
                end_buf = min(float(video_duration), end_buf)

            if end_buf > start_buf:
                raw_segments.append((start_buf, end_buf))

        merged_tuples = self.merge_overlapping_segments(raw_segments)

        return [
            {
                "start": s,
                "end": e,
                "duration": round(e - s, 3)
            }
            for s, e in merged_tuples
        ]

    def probe_video_info(self, video_path: str) -> Dict[str, Any]:
        """
        探针获取视频时长与音频流存在性
        """
        info = {"duration": None, "has_audio": True}
        if not self.is_ffmpeg_available():
            return info

        try:
            cmd = [
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video_path
            ]
            res = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
            if res.returncode == 0 and res.stdout.strip():
                info["duration"] = float(res.stdout.strip())
        except Exception:
            pass

        try:
            cmd_audio = [
                "ffprobe", "-v", "error",
                "-select_streams", "a:0",
                "-show_entries", "stream=codec_name",
                "-of", "default=noprint_wrappers=1:nokey=1",
                video_path
            ]
            res_audio = subprocess.run(cmd_audio, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=False)
            if res_audio.returncode == 0 and not res_audio.stdout.strip():
                info["has_audio"] = False
        except Exception:
            pass

        return info

    def _generate_synthetic_video(self, output_path: str, duration: float = 30.0) -> None:
        """
        生成合成测试视频 (lavfi testsrc + sine audio)
        """
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)
        cmd = [
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", "testsrc=size=1280x720:rate=30",
            "-f", "lavfi", "-i", f"sine=frequency=1000:duration={duration}",
            "-t", str(duration),
            "-c:v", "libx264", "-c:a", "aac",
            output_path
        ]
        subprocess.run(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

    def _ensure_valid_input_video(
        self,
        input_video_path: str,
        min_duration: float = 30.0
    ) -> Tuple[str, bool]:
        """Validate that the source can be probed before starting an export."""
        if not os.path.exists(input_video_path):
            raise FileNotFoundError(f"Input video file not found: {input_video_path}")

        info = self.probe_video_info(input_video_path)
        if info.get("duration") is not None and info["duration"] > 0:
            return input_video_path, False

        raise ValueError("The uploaded source is not a readable video file.")

    def process_and_export(
        self,
        input_video: str = None,
        rallies: List[Dict[str, float]] = None,
        output_path: str = None,
        resolution: str = "1080p",
        pre_buffer: float = 1.0,
        post_buffer: float = 1.5,
        export_type: str = "merged",
        codec: str = "h264",
        crf: Optional[int] = None,
        **kwargs
    ) -> str:
        """
        Enforce signature contract:
        process_and_export(self, input_video: str, rallies: List[Dict[str, float]], output_path: str,
                           resolution: str = "1080p", pre_buffer: float = 1.0, post_buffer: float = 1.5,
                           export_type: str = "merged") -> str
        """
        # Alias support for kwargs (input_video_path / output_dir)
        if input_video is None:
            input_video = kwargs.get("input_video_path")
        if output_path is None:
            output_path = kwargs.get("output_dir")

        if not input_video:
            raise ValueError("input_video is required.")
        if not output_path:
            raise ValueError("output_path is required.")

        if not os.path.exists(input_video):
            raise FileNotFoundError(f"Input video file not found: {input_video}")

        if rallies is None or len(rallies) == 0:
            raise ValueError("No rally segments provided for export.")

        export_type_clean = export_type.lower()
        res_clean = resolution.lower()
        codec_clean = codec.lower()
        if res_clean not in self.PRESETS:
            raise ValueError(f"Invalid resolution preset: '{resolution}'")
        if export_type_clean not in {"merged", "zip"}:
            raise ValueError(f"Invalid export_type: '{export_type}'")
        if codec_clean not in {"h264", "hevc"}:
            raise ValueError(f"Invalid codec: '{codec}'")
        if crf is not None and not 16 <= int(crf) <= 35:
            raise ValueError("crf must be between 16 and 35")

        # Determine output file path
        ext = ".zip" if export_type_clean == "zip" else ".mp4"
        if output_path.endswith(".mp4") or output_path.endswith(".zip"):
            export_filepath = output_path
        else:
            filename = f"badminton_highlight_{uuid.uuid4().hex[:8]}_{res_clean}{ext}"
            export_filepath = os.path.join(output_path, filename)

        export_dir = os.path.dirname(export_filepath) or "."
        os.makedirs(export_dir, exist_ok=True)

        # Mock mode or ffmpeg binary missing
        if not self.is_ffmpeg_available():
            self._export_mock(export_filepath, export_type_clean, rallies)
            return export_filepath

        # Calculate max duration needed
        max_end = 30.0
        for r in rallies:
            if isinstance(r, dict):
                end_val = float(r.get("end", r.get("end_time", 0.0)))
            elif isinstance(r, (list, tuple)):
                end_val = float(r[1])
            else:
                end_val = float(getattr(r, "end_time", getattr(r, "end", 0.0)))
            if end_val + post_buffer + 5.0 > max_end:
                max_end = end_val + post_buffer + 5.0

        effective_video_path, is_temp = self._ensure_valid_input_video(input_video, min_duration=max_end)

        try:
            info = self.probe_video_info(effective_video_path)
            video_duration = info.get("duration")
            has_audio = info.get("has_audio", True)

            segments = self.apply_buffer_padding(rallies, pre_buffer, post_buffer, video_duration)
            if not segments:
                raise ValueError("No valid rally segments found for export.")

            preset_cfg = dict(self.PRESETS[res_clean])
            if crf is not None:
                preset_cfg["crf"] = int(crf)
            encoder = self.detect_hwaccel(codec_clean)

            if export_type_clean == "merged":
                self._export_merged(
                    input_video_path=effective_video_path,
                    segments=segments,
                    output_filepath=export_filepath,
                    preset_cfg=preset_cfg,
                    encoder=encoder,
                    has_audio=has_audio
                )
            elif export_type_clean == "zip":
                self._export_zip(
                    input_video_path=effective_video_path,
                    segments=segments,
                    output_filepath=export_filepath,
                    preset_cfg=preset_cfg,
                    encoder=encoder,
                    has_audio=has_audio
                )

        except Exception as e:
            if not self.is_ffmpeg_available() or os.getenv("FFMPEG_MOCK", "").lower() in ("1", "true", "yes"):
                self._export_mock(export_filepath, export_type_clean, rallies)
            else:
                raise RuntimeError(f"FFmpeg processing failed: {str(e)}")
        finally:
            if is_temp and os.path.exists(effective_video_path):
                try:
                    os.remove(effective_video_path)
                except Exception:
                    pass

        return export_filepath

    def _export_mock(self, output_filepath: str, export_type: str, rallies: List[Any]) -> None:
        """
        Mock export generator for test environments.
        Produces valid ZIP archives or valid mock MP4 header files.
        """
        os.makedirs(os.path.dirname(output_filepath) or ".", exist_ok=True)
        if export_type == "zip":
            with zipfile.ZipFile(output_filepath, "w", zipfile.ZIP_STORED) as zf:
                num_clips = len(rallies) if rallies else 1
                for idx in range(1, num_clips + 1):
                    clip_name = f"rally_{idx:02d}.mp4"
                    zf.writestr(clip_name, f"MOCK_MP4_DATA_RALLY_{idx:02d}".encode("utf-8"))
        else:
            with open(output_filepath, "wb") as f:
                f.write(b"MOCK_MP4_VIDEO_HEADER_DATA_BADMINTON_HIGHLIGHT")

    def _export_merged(
        self,
        input_video_path: str,
        segments: List[Dict[str, float]],
        output_filepath: str,
        preset_cfg: Dict[str, Any],
        encoder: str,
        has_audio: bool
    ) -> None:
        scale = preset_cfg["scale"]
        crf = preset_cfg["crf"]
        preset_name = preset_cfg["preset"]

        filter_parts = []
        n = len(segments)

        for i, seg in enumerate(segments):
            s = seg["start"]
            e = seg["end"]
            v_filt = f"[0:v]trim=start={s}:end={e},setpts=PTS-STARTPTS,fps=30"
            v_filt += self._scale_filter(scale)
            v_filt += f"[v{i}]"
            filter_parts.append(v_filt)

            if has_audio:
                a_filt = f"[0:a]atrim=start={s}:end={e},asetpts=PTS-STARTPTS,aresample=async=1[a{i}]"
                filter_parts.append(a_filt)

        concat_v_a = []
        for i in range(n):
            if has_audio:
                concat_v_a.append(f"[v{i}][a{i}]")
            else:
                concat_v_a.append(f"[v{i}]")

        a_concat_flag = "1" if has_audio else "0"
        concat_str = "".join(concat_v_a) + f"concat=n={n}:v=1:a={a_concat_flag}[outv]" + ("[outa]" if has_audio else "")
        filter_parts.append(concat_str)

        filter_complex = ";".join(filter_parts)

        cmd = ["ffmpeg", "-y", "-i", input_video_path, "-filter_complex", filter_complex, "-map", "[outv]"]
        if has_audio:
            cmd.extend(["-map", "[outa]"])

        cmd.extend(self._get_encoding_flags(encoder, crf, preset_name, has_audio))
        cmd.append(output_filepath)

        try:
            subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
        except (subprocess.CalledProcessError, FileNotFoundError):
            software_encoder = "libx265" if encoder == "libx265" or encoder.startswith("hevc_") else "libx264"
            if encoder != software_encoder:
                cmd_fb = ["ffmpeg", "-y", "-i", input_video_path, "-filter_complex", filter_complex, "-map", "[outv]"]
                if has_audio:
                    cmd_fb.extend(["-map", "[outa]"])
                cmd_fb.extend(self._get_encoding_flags(software_encoder, crf, preset_name, has_audio))
                cmd_fb.append(output_filepath)
                subprocess.run(cmd_fb, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
            else:
                raise

    def _export_zip(
        self,
        input_video_path: str,
        segments: List[Dict[str, float]],
        output_filepath: str,
        preset_cfg: Dict[str, Any],
        encoder: str,
        has_audio: bool
    ) -> None:
        scale = preset_cfg["scale"]
        crf = preset_cfg["crf"]
        preset_name = preset_cfg["preset"]

        with tempfile.TemporaryDirectory() as temp_dir:
            clip_paths = []
            for i, seg in enumerate(segments):
                clip_filename = f"rally_{i+1:02d}.mp4"
                clip_path = os.path.join(temp_dir, clip_filename)
                s = seg["start"]
                e = seg["end"]

                v_filt = f"trim=start={s}:end={e},setpts=PTS-STARTPTS,fps=30"
                v_filt += self._scale_filter(scale)

                cmd = ["ffmpeg", "-y", "-i", input_video_path, "-vf", v_filt]
                if has_audio:
                    a_filt = f"atrim=start={s}:end={e},asetpts=PTS-STARTPTS,aresample=async=1"
                    cmd.extend(["-af", a_filt])

                cmd.extend(self._get_encoding_flags(encoder, crf, preset_name, has_audio))
                cmd.append(clip_path)

                try:
                    subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
                except (subprocess.CalledProcessError, FileNotFoundError):
                    software_encoder = "libx265" if encoder == "libx265" or encoder.startswith("hevc_") else "libx264"
                    if encoder != software_encoder:
                        cmd_fb = ["ffmpeg", "-y", "-i", input_video_path, "-vf", v_filt]
                        if has_audio:
                            cmd_fb.extend(["-af", a_filt])
                        cmd_fb.extend(self._get_encoding_flags(software_encoder, crf, preset_name, has_audio))
                        cmd_fb.append(clip_path)
                        subprocess.run(cmd_fb, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
                    else:
                        raise

                clip_paths.append((clip_filename, clip_path))

            with zipfile.ZipFile(output_filepath, "w", zipfile.ZIP_DEFLATED) as zf:
                for fname, fpath in clip_paths:
                    zf.write(fpath, arcname=fname)

    def _get_encoding_flags(
        self,
        encoder: str,
        crf: int,
        preset_name: str,
        has_audio: bool
    ) -> List[str]:
        flags = ["-c:v", encoder]
        if encoder in {"libx264", "libx265"}:
            flags.extend(["-crf", str(crf), "-preset", preset_name])
        elif encoder in {"h264_videotoolbox", "hevc_videotoolbox"}:
            quality = max(35, min(90, 100 - int(crf)))
            flags.extend(["-q:v", str(quality)])
        elif encoder in {"h264_nvenc", "hevc_nvenc"}:
            flags.extend(["-cq", str(crf), "-preset", preset_name])

        if encoder in {"libx264", "h264_videotoolbox", "h264_nvenc"}:
            flags.extend(["-pix_fmt", "yuv420p"])
        else:
            flags.extend(["-tag:v", "hvc1"])

        if has_audio:
            flags.extend(["-c:a", "aac", "-b:a", "192k"])

        flags.extend(["-movflags", "+faststart"])

        return flags

ffmpeg_service = FFmpegService()
