"""TrackNetV3 shuttle trajectory inference for rally boundary refinement.

The network architecture is adapted from qaz812345/TrackNetV3 (MIT License).
Bundled open weights ship with this repo — no paid API required.

Inference modes (TRACKNET_MODE):
  - boundary: short windows around first/last hit (default, fast)
  - candidate: full candidate span + pad (slower, optional precision mode)
"""

from dataclasses import dataclass
import logging
import math
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class TrajectoryPoint:
    time: float
    x: float
    y: float
    confidence: float


def _build_tracknet(torch: Any, in_dim: int, out_dim: int) -> Any:
    nn = torch.nn

    class Conv2DBlock(nn.Module):
        def __init__(self, input_channels: int, output_channels: int) -> None:
            super().__init__()
            self.conv = nn.Conv2d(input_channels, output_channels, 3, padding="same", bias=False)
            self.bn = nn.BatchNorm2d(output_channels)
            self.relu = nn.ReLU()

        def forward(self, tensor: Any) -> Any:
            return self.relu(self.bn(self.conv(tensor)))

    class Double2DConv(nn.Module):
        def __init__(self, input_channels: int, output_channels: int) -> None:
            super().__init__()
            self.conv_1 = Conv2DBlock(input_channels, output_channels)
            self.conv_2 = Conv2DBlock(output_channels, output_channels)

        def forward(self, tensor: Any) -> Any:
            return self.conv_2(self.conv_1(tensor))

    class Triple2DConv(nn.Module):
        def __init__(self, input_channels: int, output_channels: int) -> None:
            super().__init__()
            self.conv_1 = Conv2DBlock(input_channels, output_channels)
            self.conv_2 = Conv2DBlock(output_channels, output_channels)
            self.conv_3 = Conv2DBlock(output_channels, output_channels)

        def forward(self, tensor: Any) -> Any:
            return self.conv_3(self.conv_2(self.conv_1(tensor)))

    class TrackNet(nn.Module):
        def __init__(self) -> None:
            super().__init__()
            self.down_block_1 = Double2DConv(in_dim, 64)
            self.down_block_2 = Double2DConv(64, 128)
            self.down_block_3 = Triple2DConv(128, 256)
            self.bottleneck = Triple2DConv(256, 512)
            self.up_block_1 = Triple2DConv(768, 256)
            self.up_block_2 = Double2DConv(384, 128)
            self.up_block_3 = Double2DConv(192, 64)
            self.predictor = nn.Conv2d(64, out_dim, (1, 1))

        def forward(self, tensor: Any) -> Any:
            first = self.down_block_1(tensor)
            second = self.down_block_2(nn.functional.max_pool2d(first, 2))
            third = self.down_block_3(nn.functional.max_pool2d(second, 2))
            tensor = self.bottleneck(nn.functional.max_pool2d(third, 2))
            tensor = torch.cat((nn.functional.interpolate(tensor, scale_factor=2), third), dim=1)
            tensor = self.up_block_1(tensor)
            tensor = torch.cat((nn.functional.interpolate(tensor, scale_factor=2), second), dim=1)
            tensor = self.up_block_2(tensor)
            tensor = torch.cat((nn.functional.interpolate(tensor, scale_factor=2), first), dim=1)
            return torch.sigmoid(self.predictor(self.up_block_3(tensor)))

    return TrackNet()


class TrackNetV3BoundaryRefiner:
    HEIGHT = 288
    WIDTH = 512

    def __init__(
        self,
        model_path: Optional[str] = None,
        batch_size: int = 4,
        pre_window: float = 3.2,
        post_window: float = 3.4,
        threshold: float = 0.5,
        mode: Optional[str] = None,
        candidate_pad_pre: float = 1.5,
        candidate_pad_post: float = 2.0,
    ) -> None:
        default_path = Path(__file__).resolve().parents[2] / "model_assets" / "tracknetv3_track.pt"
        self.model_path = Path(model_path or os.getenv("TRACKNET_MODEL_PATH", default_path))
        self.batch_size = max(1, min(int(batch_size), 8))
        # Wider windows recover serve toss / late landing when audio cuts early.
        self.pre_window = max(1.0, min(float(pre_window), 5.0))
        self.post_window = max(1.0, min(float(post_window), 5.0))
        self.threshold = max(0.1, min(float(threshold), 0.9))
        # Default to boundary for interactive desktop/web speed. Users can opt into
        # TRACKNET_MODE=candidate when they want slower, denser trajectory coverage.
        requested_mode = (mode or os.getenv("TRACKNET_MODE", "boundary")).strip().lower()
        self.mode = requested_mode if requested_mode in {"boundary", "candidate"} else "boundary"
        self.candidate_pad_pre = max(
            0.5,
            min(float(os.getenv("TRACKNET_CANDIDATE_PAD_PRE", candidate_pad_pre)), 4.0),
        )
        self.candidate_pad_post = max(
            0.5,
            min(float(os.getenv("TRACKNET_CANDIDATE_PAD_POST", candidate_pad_post)), 5.0),
        )
        self._model = None
        self._torch = None
        self._device = None
        self._sequence_length = 8

    @property
    def available(self) -> bool:
        return self.model_path.is_file()

    def _load_model(self) -> None:
        if self._model is not None:
            return
        if not self.available:
            raise FileNotFoundError(f"TrackNetV3 weights not found: {self.model_path}")

        import torch

        checkpoint = torch.load(self.model_path, map_location="cpu", weights_only=True)
        params = checkpoint.get("param_dict", {})
        self._sequence_length = int(params.get("seq_len", 8))
        background_mode = params.get("bg_mode", "concat")
        if background_mode != "concat":
            raise ValueError(f"Unsupported TrackNetV3 background mode: {background_mode}")
        model = _build_tracknet(
            torch,
            in_dim=(self._sequence_length + 1) * 3,
            out_dim=self._sequence_length,
        )
        model.load_state_dict(checkpoint["model"])

        requested = os.getenv("TRACKNET_DEVICE", "auto").lower()
        if requested == "auto":
            if torch.cuda.is_available():
                device = "cuda"
            elif torch.backends.mps.is_available():
                device = "mps"
            else:
                device = "cpu"
        elif requested in {"cpu", "mps", "cuda"}:
            device = requested
        else:
            raise ValueError(f"Unsupported TRACKNET_DEVICE: {requested}")
        self._torch = torch
        self._device = torch.device(device)
        self._model = model.to(self._device).eval()

    @staticmethod
    def _merge_windows(windows: List[Tuple[float, float]]) -> List[Tuple[float, float]]:
        if not windows:
            return []
        merged: List[Tuple[float, float]] = []
        for start, end in sorted(windows):
            if merged and start <= merged[-1][1] + 0.1:
                merged[-1] = (merged[-1][0], max(merged[-1][1], end))
            else:
                merged.append((max(0.0, start), end))
        return merged

    def _read_window_frames(
        self,
        video_path: str,
        windows: List[Tuple[float, float]],
    ) -> Tuple[float, List[Tuple[float, List[np.ndarray]]]]:
        import cv2

        cap = cv2.VideoCapture(video_path)
        if not cap.isOpened():
            cap.release()
            raise RuntimeError(f"TrackNetV3 could not open video: {video_path}")
        fps = float(cap.get(cv2.CAP_PROP_FPS) or 30.0)
        collected: List[Tuple[float, List[np.ndarray]]] = []
        try:
            for start, end in windows:
                cap.set(cv2.CAP_PROP_POS_MSEC, start * 1000.0)
                frames: List[np.ndarray] = []
                times: List[float] = []
                while True:
                    ok, frame = cap.read()
                    if not ok:
                        break
                    timestamp = float(cap.get(cv2.CAP_PROP_POS_MSEC)) / 1000.0
                    if timestamp > end + (1.0 / fps):
                        break
                    frames.append(frame[:, :, ::-1])
                    times.append(timestamp)
                if frames:
                    collected.append((times[0], frames))
        finally:
            cap.release()
        return fps, collected

    def _predict_points(
        self,
        fps: float,
        frame_windows: List[Tuple[float, List[np.ndarray]]],
    ) -> List[TrajectoryPoint]:
        import cv2

        self._load_model()
        torch = self._torch
        sequence_length = self._sequence_length
        all_frames = [frame for _, frames in frame_windows for frame in frames]
        if not all_frames:
            return []
        median_indices = np.linspace(0, len(all_frames) - 1, min(48, len(all_frames))).astype(int)
        median = np.median(np.asarray([all_frames[index] for index in median_indices]), axis=0).astype(np.uint8)
        median = cv2.resize(median, (self.WIDTH, self.HEIGHT), interpolation=cv2.INTER_AREA)
        median_chw = np.moveaxis(median, -1, 0)

        prepared: List[np.ndarray] = []
        sequence_times: List[List[float]] = []
        for window_start, frames in frame_windows:
            for offset in range(0, len(frames), sequence_length):
                chunk = frames[offset:offset + sequence_length]
                if len(chunk) < sequence_length:
                    chunk = chunk + [chunk[-1]] * (sequence_length - len(chunk))
                channels = [median_chw]
                for frame in chunk:
                    resized = cv2.resize(frame, (self.WIDTH, self.HEIGHT), interpolation=cv2.INTER_AREA)
                    channels.append(np.moveaxis(resized, -1, 0))
                prepared.append(np.concatenate(channels, axis=0).astype(np.float32) / 255.0)
                sequence_times.append([
                    window_start + min(offset + index, len(frames) - 1) / fps
                    for index in range(sequence_length)
                ])

        points: List[TrajectoryPoint] = []
        with torch.inference_mode():
            for offset in range(0, len(prepared), self.batch_size):
                batch = torch.from_numpy(np.asarray(prepared[offset:offset + self.batch_size])).to(self._device)
                predictions = self._model(batch).detach().cpu().numpy()
                for batch_index, heatmaps in enumerate(predictions):
                    times = sequence_times[offset + batch_index]
                    for frame_index, heatmap in enumerate(heatmaps):
                        confidence = float(np.max(heatmap))
                        binary = (heatmap >= self.threshold).astype(np.uint8)
                        contours, _ = cv2.findContours(binary, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
                        if not contours:
                            continue
                        contour = max(contours, key=cv2.contourArea)
                        x, y, width, height = cv2.boundingRect(contour)
                        points.append(TrajectoryPoint(
                            time=times[frame_index],
                            x=(x + width / 2.0) / self.WIDTH,
                            y=(y + height / 2.0) / self.HEIGHT,
                            confidence=confidence,
                        ))
        return points

    @staticmethod
    def _trajectory_clusters(points: List[TrajectoryPoint], max_gap: float = 0.22) -> List[List[TrajectoryPoint]]:
        if not points:
            return []
        clusters: List[List[TrajectoryPoint]] = [[points[0]]]
        for point in points[1:]:
            if point.time - clusters[-1][-1].time <= max_gap:
                clusters[-1].append(point)
            else:
                clusters.append([point])
        return clusters

    @staticmethod
    def _is_moving_trajectory(cluster: List[TrajectoryPoint]) -> bool:
        if len(cluster) < 3:
            return False
        path_length = sum(
            math.hypot(current.x - previous.x, current.y - previous.y)
            for previous, current in zip(cluster, cluster[1:])
        )
        displacement = math.hypot(
            cluster[-1].x - cluster[0].x,
            cluster[-1].y - cluster[0].y,
        )
        return path_length >= 0.025 and (displacement >= 0.012 or path_length >= 0.06)

    @staticmethod
    def _inside_target_span(point: TrajectoryPoint, roi: Sequence[Sequence[float]]) -> bool:
        xs = [float(item[0]) for item in roi]
        ys = [float(item[1]) for item in roi]
        margin = 0.08
        return min(xs) - margin <= point.x <= max(xs) + margin and 0.02 <= point.y <= max(ys) + margin

    def _candidate_windows(
        self,
        rallies: List[Dict[str, Any]],
    ) -> List[Tuple[float, float]]:
        windows: List[Tuple[float, float]] = []
        if self.mode == "boundary":
            for rally in rallies:
                first_hit = float(rally.get("first_hit", rally["start"]))
                last_hit = float(rally.get("last_hit", rally["end"]))
                windows.append((first_hit - self.pre_window, first_hit + 0.45))
                windows.append((last_hit - 0.15, last_hit + self.post_window))
            return self._merge_windows(windows)

        for rally in rallies:
            start = float(rally["start"]) - self.candidate_pad_pre
            end = float(rally["end"]) + self.candidate_pad_post
            # Always cover serve lead-in / landing even if coarse start/end are tight.
            first_hit = float(rally.get("first_hit", rally["start"]))
            last_hit = float(rally.get("last_hit", rally["end"]))
            start = min(start, first_hit - self.pre_window * 0.7)
            end = max(end, last_hit + self.post_window * 0.7)
            windows.append((max(0.0, start), end))
        return self._merge_windows(windows)

    def track_points(
        self,
        video_path: str,
        rallies: List[Dict[str, Any]],
        court_roi: Sequence[Sequence[float]],
    ) -> Tuple[float, List[TrajectoryPoint]]:
        """Infer shuttle points for candidate (or boundary) windows."""
        if not self.available or not rallies:
            return 30.0, []
        merged_windows = self._candidate_windows(rallies)
        fps, frame_windows = self._read_window_frames(video_path, merged_windows)
        points = [
            point for point in self._predict_points(fps, frame_windows)
            if self._inside_target_span(point, court_roi)
        ]
        logger.info(
            "TrackNetV3 mode=%s windows=%d points=%d",
            self.mode,
            len(merged_windows),
            len(points),
        )
        return fps, points

    def refine(
        self,
        video_path: str,
        rallies: List[Dict[str, Any]],
        court_roi: Sequence[Sequence[float]],
    ) -> List[Dict[str, Any]]:
        """Legacy boundary-only refine kept for tests and fallback path."""
        if not self.available:
            return rallies
        fps, points = self.track_points(video_path, rallies, court_roi)
        del fps  # points already time-stamped

        refined: List[Dict[str, Any]] = []
        for rally in rallies:
            item = dict(rally)
            first_hit = float(item.get("first_hit", item["start"]))
            last_hit = float(item.get("last_hit", item["end"]))

            start_points = [
                point for point in points
                if first_hit - self.pre_window <= point.time <= first_hit + 0.25
            ]
            start_clusters = [
                cluster for cluster in self._trajectory_clusters(start_points)
                if self._is_moving_trajectory(cluster)
            ]
            candidates = [cluster for cluster in start_clusters if cluster[-1].time >= first_hit - 0.35]
            if candidates:
                trajectory_start = max(0.0, candidates[-1][0].time - 0.3)
                item["start"] = min(float(item["start"]), trajectory_start)

            end_points = [
                point for point in points
                if last_hit - 0.1 <= point.time <= last_hit + self.post_window
            ]
            end_clusters = [
                cluster for cluster in self._trajectory_clusters(end_points)
                if self._is_moving_trajectory(cluster)
            ]
            candidates = [cluster for cluster in end_clusters if cluster[0].time <= last_hit + 0.4]
            if candidates:
                trajectory_end = min(last_hit + self.post_window, candidates[0][-1].time + 0.25)
                item["end"] = max(float(item["end"]), trajectory_end)
            refined.append(item)

        logger.info("TrackNetV3 refined %d rally boundaries from %d trajectory points", len(refined), len(points))
        return refined
