from fastapi import APIRouter
from fastapi.responses import FileResponse
from backend.app.services.download_service import download_service

router = APIRouter(tags=["Downloads"])

@router.get("/downloads/{filename:path}")
async def download_file(filename: str) -> FileResponse:
    return download_service.get_download_file(filename)
