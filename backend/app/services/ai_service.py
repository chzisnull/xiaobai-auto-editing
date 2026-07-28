from typing import Dict, List, Optional
from fastapi import BackgroundTasks, HTTPException, status
from sqlalchemy.orm import Session

from backend.app.db.session import SessionLocal
from backend.app.models.task import Task
from backend.app.models.rally import Rally
from backend.app.schemas.rally import RallyItem, RallyListResponse
from backend.app.detectors.audio_visual import AudioVisualRallyDetector  # compatibility import for detector integrations
from backend.app.detectors.court_aware import CourtAwareRallyDetector

class AIService:
    @staticmethod
    def queue_task_analysis(
        task_id: str,
        db: Session,
        background_tasks: BackgroundTasks,
        court_roi: Optional[List[List[float]]] = None,
    ) -> Dict[str, str]:
        task = db.query(Task).filter(Task.id == task_id).first()
        if not task:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Task with id '{task_id}' not found."
            )
        if task.status == "processing":
            return {"task_id": task_id, "status": "processing"}

        task.status = "processing"
        db.commit()
        background_tasks.add_task(AIService._run_background_analysis, task_id, court_roi)
        return {"task_id": task_id, "status": "processing"}

    @staticmethod
    def _run_background_analysis(
        task_id: str,
        court_roi: Optional[List[List[float]]] = None,
    ) -> None:
        db = SessionLocal()
        try:
            AIService.analyze_task_video(task_id, db, court_roi)
        except HTTPException:
            # The synchronous worker has already persisted the failed state.
            pass
        finally:
            db.close()

    @staticmethod
    def analyze_task_video(
        task_id: str,
        db: Session,
        court_roi: Optional[List[List[float]]] = None,
    ) -> Dict[str, str]:
        task = db.query(Task).filter(Task.id == task_id).first()
        if not task:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Task with id '{task_id}' not found."
            )

        # Transition lifecycle: uploaded -> processing
        task.status = "processing"
        db.commit()

        try:
            # Clear existing rallies for this task if re-analyzing
            db.query(Rally).filter(Rally.task_id == task_id).delete()

            # Execute Audio-Visual Rally Detector
            detector = CourtAwareRallyDetector(court_roi=court_roi)
            raw_rallies = detector.analyze_video(task.video_path)

            # Persist Rally ORM instances to SQLite DB
            for item in raw_rallies:
                start_time = max(0.0, float(item["start"]))
                end_time = float(item["end"])
                if task.duration > 0:
                    end_time = min(end_time, task.duration)
                if end_time <= start_time:
                    continue
                rally = Rally(
                    task_id=task_id,
                    rally_index=item["id"],
                    start_time=round(start_time, 3),
                    end_time=round(end_time, 3),
                    duration=round(end_time - start_time, 3),
                    confidence=item.get("confidence", 1.0)
                )
                db.add(rally)

            task.status = "completed"
            db.commit()

            return {
                "task_id": task_id,
                "status": "completed"
            }
        except RuntimeError as exc:
            db.rollback()
            # Clear any stale/previous rallies and set task status to failed
            db.query(Rally).filter(Rally.task_id == task_id).delete()
            failed_task = db.query(Task).filter(Task.id == task_id).first()
            if failed_task:
                failed_task.status = "failed"
                db.commit()
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=f"Rally detection failed: {str(exc)}"
            )
        except Exception as exc:
            db.rollback()
            # Clear any stale/previous rallies and set task status to failed
            db.query(Rally).filter(Rally.task_id == task_id).delete()
            failed_task = db.query(Task).filter(Task.id == task_id).first()
            if failed_task:
                failed_task.status = "failed"
                db.commit()
            raise HTTPException(
                status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
                detail=f"Rally detection failed: {str(exc)}"
            )

    @staticmethod
    def get_task_analysis_result(task_id: str, db: Session) -> RallyListResponse:
        task = db.query(Task).filter(Task.id == task_id).first()
        if not task:
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail=f"Task with id '{task_id}' not found."
            )

        rallies = db.query(Rally).filter(Rally.task_id == task_id).order_by(Rally.rally_index).all()
        total_rallies = len(rallies)
        edited_duration = sum(r.duration for r in rallies)

        rally_items = [
            RallyItem(
                id=r.id,
                rally_index=r.rally_index,
                start_time=r.start_time,
                end_time=r.end_time,
                duration=r.duration,
                confidence=r.confidence
            )
            for r in rallies
        ]

        return RallyListResponse(
            task_id=task.id,
            status=task.status,
            total_rallies=total_rallies,
            original_duration=task.duration,
            edited_duration=round(edited_duration, 2),
            rallies=rally_items
        )

ai_service = AIService()
