from contextlib import asynccontextmanager
from fastapi import FastAPI, Response
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import RedirectResponse
from fastapi.staticfiles import StaticFiles
from backend.app.db.session import init_db
from backend.app.api import video_router, ai_router, export_router, downloads_router
from backend.app.core.config import get_frontend_dir, get_upload_dir

@asynccontextmanager
async def lifespan(app: FastAPI):
    # Initialize SQLite database tables on startup
    init_db()
    yield

app = FastAPI(
    title="Badminton Auto-Editing API",
    description="Automatic Badminton Match Video Editing & Compression Service",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["Content-Disposition", "X-Export-Filename", "X-File-Size-MB"],
)

# Include API Routers
app.include_router(video_router)
app.include_router(ai_router)
app.include_router(export_router)
app.include_router(downloads_router)

upload_dir = get_upload_dir()
upload_dir.mkdir(parents=True, exist_ok=True)
app.mount("/uploads", StaticFiles(directory=str(upload_dir)), name="uploads")
app.mount("/app", StaticFiles(directory=str(get_frontend_dir()), html=True), name="frontend")


@app.get("/", include_in_schema=False)
def read_root() -> RedirectResponse:
    return RedirectResponse(url="/app/")


@app.get("/api/health", tags=["Health"])
def health_check():
    return {"status": "ok", "service": "badminton-auto-editing"}


@app.get("/favicon.ico", include_in_schema=False)
def favicon() -> Response:
    return Response(status_code=204)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run("backend.app.main:app", host="0.0.0.0", port=8000, reload=True)
