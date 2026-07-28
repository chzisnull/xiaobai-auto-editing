from fastapi import APIRouter, UploadFile, File, Depends, status
from sqlalchemy.orm import Session
from backend.app.db.session import get_db
from backend.app.schemas.task import TaskResponse
from backend.app.services.video_service import video_service

router = APIRouter(prefix="/api/v1/video", tags=["Video"])


@router.get("/task/{task_id}", response_model=TaskResponse)
def get_video_task(
    task_id: str,
    db: Session = Depends(get_db),
) -> TaskResponse:
    return video_service.get_video_task(task_id, db)

@router.post("/upload", response_model=TaskResponse, status_code=status.HTTP_201_CREATED)
def upload_video(
    file: UploadFile = File(...),
    db: Session = Depends(get_db)
) -> TaskResponse:
    return video_service.process_video_upload(file, db)
