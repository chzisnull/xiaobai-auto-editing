# Project: Automatic Badminton Match Video Editing Software

## Architecture
- **Backend**: FastAPI, Python 3.9+, SQLite + SQLAlchemy for task metadata and AI rally timestamps. Rendered exports are never persisted.
- **AI Detection Engine**: `CourtAwareRallyDetector` fuses 2kHz-6kHz impact candidates with motion inside a calibrated four-point court ROI. It backtracks court motion to include the serve, then uses an MIT-licensed TrackNetV3 checkpoint on short boundary windows to extend reliable moving-shuttle trajectories through landing.
- **FFmpeg Engine**: Precise trimming, optional buffer padding, overlap merging, H.264/HEVC CRF compression, maximum-size 1080p/720p presets that never upscale smaller sources, and merged MP4 / split ZIP export. Software encoding is the default; hardware acceleration is opt-in.
- **Frontend**: Responsive H5 video player, draggable court calibration overlay, confidence-aware editable rally timeline, task restoration, export controls, and one-time browser downloads.

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Clean Backend & SQLite Database | SQLAlchemy models (Task, Rally), DB session, upload & task API endpoints | none | DONE |
| M2 | Court-Aware + TrackNetV3 Detection | Audio peaks, calibrated court motion, serve backtracking, trajectory boundary refinement and dynamic confidence | M1 | DONE |
| M3 | FFmpeg Trimming & Compression Engine | Buffer padding, clip cutting, seamless merging, H.264/HEVC compression, MP4/ZIP export | M1, M2 | DONE |
| M4 | Web/H5 Interactive Platform | Responsive H5 player, interactive timeline editing, export controls, browser download | M1, M2, M3 | DONE |
| M5 | E2E Integration & Verification | Automated API, detector, FFmpeg, adversarial, desktop and mobile browser verification | M1-M4 | DONE |

## Interface Contracts
### API Endpoint Specifications
- `POST /api/v1/video/upload` -> `{ "task_id": str, "filename": str, "video_url": str, "duration": float }`
- `GET /api/v1/video/task/{task_id}` -> persisted task metadata used by `?task={task_id}` frontend restoration
- `POST /api/v1/ai/analyze/{task_id}?background=true` with optional `{ "court_roi": [{ "x": float, "y": float }, ... x4] }` -> `{ "task_id": str, "status": str }`
- `GET /api/v1/ai/result/{task_id}` -> `{ "task_id": str, "status": str, "total_rallies": int, "original_duration": float, "edited_duration": float, "rallies": list }`
- `POST /api/v1/video/export/{task_id}` -> One-time `video/mp4` or `application/zip` file response. Headers include `X-Export-Filename`, `X-File-Size-MB`, and `Cache-Control: no-store`.

The export is rendered in a system temporary directory and deleted after the response transfer. No export file or `ExportJob` row is retained by the server.

TrackNetV3 is applied only around candidate rally boundaries to control latency. Moving trajectories refine the timestamps; static detections and unavailable weights fall back to court motion. A future detector can still add player-pose semantics behind `BaseRallyDetector`.

### BaseRallyDetector Interface Contract
- `analyze_video(video_path: str) -> List[Dict[str, float]]` returning list of `{"id": int, "start": float, "end": float, "duration": float}`

### FFmpegService Interface Contract
- `process_and_export(input_video: str, rallies: List[Dict[str, float]], output_path: str, resolution: str, pre_buffer: float, post_buffer: float, export_type: str) -> str`

## Code Layout
- `backend/app/main.py`: FastAPI application entry point
- `backend/app/db/`: SQLite connection & session management
- `backend/app/models/`: SQLAlchemy task and rally data models; the legacy `ExportJob` table is retained for database compatibility but is not written by exports
- `backend/app/api/`: REST API routes
- `backend/app/detectors/`: `BaseRallyDetector` ABC and implementations
- `backend/model_assets/`: deployed TrackNetV3 inference weights and third-party license notice
- `backend/app/services/`: FFmpeg pipeline & processing services
- `frontend/`: Responsive H5 web application
- `tests/`: Automated unit & E2E integration test suite
