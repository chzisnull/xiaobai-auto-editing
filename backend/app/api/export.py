from fastapi import APIRouter, Depends
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session
from backend.app.db.session import get_db
from backend.app.schemas.export import ExportRequest
from backend.app.services.export_service import export_service

router = APIRouter(prefix="/api/v1/video", tags=["Export"])

@router.post("/export/{task_id}", response_class=FileResponse)
async def export_video(
    task_id: str,
    req: ExportRequest,
    db: Session = Depends(get_db)
) -> FileResponse:
    return export_service.process_export(task_id, req, db)
