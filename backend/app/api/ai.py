from typing import Dict, Optional
from fastapi import APIRouter, BackgroundTasks, Depends, Query
from sqlalchemy.orm import Session
from backend.app.db.session import get_db
from backend.app.schemas.rally import AnalyzeRequest, RallyListResponse
from backend.app.services.ai_service import ai_service

router = APIRouter(prefix="/api/v1/ai", tags=["AI Analysis"])

@router.post("/analyze/{task_id}")
async def analyze_video(
    task_id: str,
    background_tasks: BackgroundTasks,
    request: Optional[AnalyzeRequest] = None,
    background: bool = Query(default=False),
    db: Session = Depends(get_db)
) -> Dict[str, str]:
    court_roi = None
    if request and request.court_roi:
        court_roi = [[point.x, point.y] for point in request.court_roi]
    if background:
        return ai_service.queue_task_analysis(task_id, db, background_tasks, court_roi)
    return ai_service.analyze_task_video(task_id, db, court_roi)

@router.get("/result/{task_id}", response_model=RallyListResponse)
async def get_analysis_result(
    task_id: str,
    db: Session = Depends(get_db)
) -> RallyListResponse:
    return ai_service.get_task_analysis_result(task_id, db)
