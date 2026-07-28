import os
import shutil
import tempfile
from pathlib import Path
from fastapi import HTTPException, status
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session
from starlette.background import BackgroundTask
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.schemas.export import ExportRequest
from backend.app.services.ffmpeg_service import ffmpeg_service


def _cleanup_export_artifacts(temp_dir: Path, source_path: str) -> None:
    """Remove the one-time export and, in desktop mode, its uploaded source copy."""
    shutil.rmtree(temp_dir, ignore_errors=True)
    if os.getenv("DELETE_SOURCE_AFTER_EXPORT", "").lower() not in {"1", "true", "yes"}:
        return
    try:
        Path(source_path).unlink(missing_ok=True)
    except OSError:
        pass


class ExportService:
    @staticmethod
    def process_export(task_id: str, req: ExportRequest, db: Session) -> FileResponse:
        task = db.query(Task).filter(Task.id == task_id).first()
        if not task:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Task with id '{task_id}' not found."
            )

        # Validate task state (must be completed or have detected rallies)
        rallies_in_db = db.query(Rally).filter(Rally.task_id == task_id).order_by(Rally.rally_index).all()
        if task.status != "completed" and not rallies_in_db and not req.rallies:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Task with id '{task_id}' is not ready for export (status: '{task.status}')."
            )

        # Determine rallies list
        if req.rallies:
            rallies = [item.model_dump() for item in req.rallies]
        elif rallies_in_db:
            rallies = [{"start": r.start_time, "end": r.end_time} for r in rallies_in_db]
        else:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="No rally segments are available for export."
            )

        input_video_path = task.video_path

        if not os.path.exists(input_video_path):
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="The uploaded source video is no longer available."
            )

        # Render into an isolated system directory that is deleted after streaming.
        res_clean = req.resolution.lower()
        export_type_clean = req.export_type.lower()
        ext = ".zip" if export_type_clean == "zip" else ".mp4"
        filename = f"badminton_highlight_{task_id}_{res_clean}_{req.codec}{ext}"
        temp_dir = Path(tempfile.mkdtemp(prefix=f"badminton-export-{task_id}-"))
        target_output_path = str(temp_dir / filename)

        try:
            exported_path = ffmpeg_service.process_and_export(
                input_video=input_video_path,
                rallies=rallies,
                output_path=target_output_path,
                resolution=req.resolution,
                pre_buffer=req.pre_buffer,
                post_buffer=req.post_buffer,
                export_type=req.export_type,
                codec=req.codec,
                crf=req.crf,
            )
        except ValueError as ve:
            shutil.rmtree(temp_dir, ignore_errors=True)
            raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail=str(ve))
        except FileNotFoundError as fnfe:
            shutil.rmtree(temp_dir, ignore_errors=True)
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(fnfe))
        except Exception as e:
            shutil.rmtree(temp_dir, ignore_errors=True)
            raise HTTPException(status_code=status.HTTP_500_INTERNAL_SERVER_ERROR, detail=f"Export failed: {str(e)}")

        file_size_bytes = os.path.getsize(exported_path)
        file_size_mb = round(file_size_bytes / (1024 * 1024), 2)
        media_type = "application/zip" if export_type_clean == "zip" else "video/mp4"

        return FileResponse(
            path=exported_path,
            filename=filename,
            media_type=media_type,
            headers={
                "X-Export-Filename": filename,
                "X-File-Size-MB": f"{file_size_mb:.2f}",
                "Cache-Control": "no-store",
            },
            background=BackgroundTask(_cleanup_export_artifacts, temp_dir, input_video_path),
        )

export_service = ExportService()
