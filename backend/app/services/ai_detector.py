"""
Backward-compatible facade for AudioVisualRallyDetector.
Imports and exposes AudioVisualRallyDetector from backend.app.detectors.
"""

from backend.app.detectors.audio_visual import AudioVisualRallyDetector
from backend.app.detectors.base import BaseRallyDetector

rally_detector = AudioVisualRallyDetector()

__all__ = ["AudioVisualRallyDetector", "BaseRallyDetector", "rally_detector"]
