import os
from pathlib import Path
from fastapi import HTTPException, status
from fastapi.responses import FileResponse
from backend.app.core.config import get_exports_dir

# Kept for older integrations that patch this module attribute directly.
EXPORTS_DIR = str(get_exports_dir())

class DownloadService:
    @staticmethod
    def get_download_file(filename: str) -> FileResponse:
        # Check for path traversal markers in raw filename parameter
        if not filename or ".." in filename or filename.startswith("/") or filename.startswith("\\"):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Path traversal attempt detected."
            )

        # Sanitize filename
        safe_name = os.path.basename(Path(filename).name)
        if not safe_name or safe_name in (".", ".."):
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Invalid filename."
            )

        exports_dir = get_exports_dir()
        exports_dir.mkdir(parents=True, exist_ok=True)

        target_path = (exports_dir / safe_name).resolve()

        try:
            if not target_path.is_relative_to(exports_dir):
                raise HTTPException(
                    status_code=status.HTTP_400_BAD_REQUEST,
                    detail="Path traversal attempt detected."
                )
        except ValueError:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail="Path traversal attempt detected."
            )

        if not target_path.exists() or not target_path.is_file():
            raise HTTPException(
                status_code=status.HTTP_404_NOT_FOUND,
                detail="File not found."
            )

        # Resolve MIME type
        if safe_name.lower().endswith(".mp4"):
            media_type = "video/mp4"
        elif safe_name.lower().endswith(".zip"):
            media_type = "application/zip"
        else:
            media_type = "application/octet-stream"

        return FileResponse(
            path=str(target_path),
            filename=safe_name,
            media_type=media_type
        )

download_service = DownloadService()
