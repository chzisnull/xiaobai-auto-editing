import os
import uuid
import shutil
import subprocess
from pathlib import Path
from fastapi import HTTPException, UploadFile, status
from sqlalchemy.orm import Session
from backend.app.core.config import get_upload_dir
from backend.app.models.task import Task
from backend.app.schemas.task import TaskResponse

ALLOWED_VIDEO_SUFFIXES = {".mp4", ".mov", ".mkv", ".m4v", ".avi", ".webm"}
# Kept for older integrations that patch this module attribute directly.
UPLOAD_DIR = str(get_upload_dir())

class VideoService:
    @staticmethod
    def get_video_task(task_id: str, db: Session) -> TaskResponse:
        task = db.query(Task).filter(Task.id == task_id).first()
        if not task:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Task with id '{task_id}' not found.",
            )
        return TaskResponse.model_validate(task)

    @staticmethod
    def process_video_upload(file: UploadFile, db: Session) -> TaskResponse:
        upload_dir = get_upload_dir()
        upload_dir.mkdir(parents=True, exist_ok=True)
        safe_filename = Path(file.filename).name if file.filename else "video.mp4"
        safe_filename = os.path.basename(safe_filename)

        if Path(safe_filename).suffix.lower() not in ALLOWED_VIDEO_SUFFIXES:
            raise HTTPException(
                status_code=status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
                detail="Unsupported video format. Use MP4, MOV, MKV, M4V, AVI, or WebM."
            )

        task_id = str(uuid.uuid4())[:8]
        file_path = upload_dir / f"{task_id}_{safe_filename}"

        # Streamed upload using chunked I/O
        with open(file_path, "wb") as buffer:
            shutil.copyfileobj(file.file, buffer)

        if file_path.stat().st_size == 0:
            file_path.unlink(missing_ok=True)
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Uploaded video is empty."
            )

        duration = VideoService._probe_duration(file_path)

        task = Task(
            id=task_id,
            filename=safe_filename,
            video_path=str(file_path),
            duration=duration,
            status="uploaded"
        )

        db.add(task)
        db.commit()
        db.refresh(task)

        return TaskResponse.model_validate(task)

    @staticmethod
    def _probe_duration(video_path: Path) -> float:
        try:
            result = subprocess.run(
                [
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    str(video_path),
                ],
                capture_output=True,
                text=True,
                check=False,
                timeout=30,
            )
            if result.returncode == 0 and result.stdout.strip():
                return round(max(0.0, float(result.stdout.strip())), 3)
        except (FileNotFoundError, subprocess.TimeoutExpired, ValueError):
            return 0.0
        return 0.0

video_service = VideoService()
