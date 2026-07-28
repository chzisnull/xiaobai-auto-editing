import os
from abc import ABC, abstractmethod
from typing import List, Dict, Any

class BaseRallyDetector(ABC):
    """
    Abstract base class for all rally detection implementations.
    Enforces the single entry point contract `analyze_video`.
    """

    @abstractmethod
    def analyze_video(self, video_path: str) -> List[Dict[str, Any]]:
        """
        Analyze an input badminton match video and return detected rally segments.

        :param video_path: Path to the input video file.
        :return: List of dictionaries, each containing:
                 {"id": int, "start": float, "end": float, "duration": float}
        """
        pass

    @staticmethod
    def validate_video_path(video_path: str) -> None:
        """
        Validate that the video path exists and is a non-empty file.

        :param video_path: Path to input video file.
        :raises FileNotFoundError: If video_path does not exist on filesystem.
        :raises ValueError: If video_path exists but is empty (0 bytes).
        """
        if not os.path.exists(video_path):
            raise FileNotFoundError(f"Video file not found: {video_path}")
        if os.path.getsize(video_path) == 0:
            raise ValueError(f"Invalid or corrupt video file (0 bytes): {video_path}")

    @staticmethod
    def _format_and_validate_rallies(
        raw_rallies: List[Dict[str, Any]],
        merge_gap_threshold: float = 0.5
    ) -> List[Dict[str, Any]]:
        """
        Sort, validate, merge adjacent/overlapping segments, and format rally data.

        :param raw_rallies: List of raw segment dicts with at least 'start' and 'end'.
        :param merge_gap_threshold: Max gap (seconds) below which adjacent rallies merge.
        :return: Clean list of formatted dicts with sequential 1-indexed IDs.
        """
        if not raw_rallies:
            return []

        # Sort chronologically by start timestamp
        sorted_rallies = sorted(raw_rallies, key=lambda x: x["start"])

        # Validate start and end timestamps
        validated_segments = []
        for segment in sorted_rallies:
            start = float(segment["start"])
            end = float(segment["end"])
            if start < 0.0:
                raise ValueError(f"Invalid rally start timestamp: {start} < 0.0")
            if end <= start:
                raise ValueError(f"Invalid rally timestamps: end ({end}) must be > start ({start})")
            
            validated_segments.append({
                "start": start,
                "end": end,
                "confidence": float(segment.get("confidence", 1.0))
            })

        # Merge overlapping or near-contiguous segments
        merged_segments = []
        current = validated_segments[0]

        for next_seg in validated_segments[1:]:
            if next_seg["start"] - current["end"] <= merge_gap_threshold:
                # Merge segments
                current["end"] = max(current["end"], next_seg["end"])
                current["confidence"] = round(min(current["confidence"], next_seg["confidence"]), 3)
            else:
                merged_segments.append(current)
                current = next_seg
        merged_segments.append(current)

        # Format output dictionary contract with sequential 1-indexed IDs
        formatted_rallies = []
        for idx, seg in enumerate(merged_segments, start=1):
            start = round(seg["start"], 3)
            end = round(seg["end"], 3)
            duration = round(end - start, 3)
            item = {
                "id": idx,
                "start": start,
                "end": end,
                "duration": duration,
                "confidence": seg["confidence"]
            }
            formatted_rallies.append(item)

        return formatted_rallies
