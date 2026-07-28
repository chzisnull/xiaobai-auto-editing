# Original User Request

## Initial Request — 2026-07-27T03:05:01Z

Automatic Badminton Match Video Editing Software featuring serve-to-point-end rally detection, non-rally footage trimming, video compression options, and a web/H5 frontend interface.

Working directory: /Library/meibaoProject/badminton-family/badmintonMatchAutoEditing
Integrity mode: development

## Requirements

### R1. Modular & Plugable AI Detection Architecture
- Abstract Detector Interface (BaseRallyDetector): Define clear interfaces so different algorithms (e.g., AudioVisualDetector, TrackNetDetector, PoseDetector) can be swapped seamlessly.
- Audio Peak & Spectrogram Analysis: Detect high-frequency crisp badminton hit sounds and rally hit density.
- Visual Motion & Action Filtering: Combine audio cues with frame-difference / YOLO motion analysis to pinpoint serve start times and point end (shuttle dropping/player walking) times.
- Rally Segment Output: Generate frame-accurate timestamp segments [(start_1, end_1), (start_2, end_2), ...].

### R2. Extensible Python FastAPI Backend & FFmpeg Processing Engine
- Clean Architecture & SQLite Persistence: Use SQLite + SQLAlchemy/SQLModel for structured storage of video upload metadata, AI rally timestamps, and rendering state.
- FFmpeg Pipeline Abstraction: Cut, trim buffer padding (pre-serve & post-point seconds), merge clips into unified video, and apply user-selected compression presets (720p/1080p, CRF/bitrate tuning, H.264/HEVC) with clear hardware acceleration fallback options.

### R3. Responsive & Maintainable Web/H5 Interactive Platform
- Video Player & Interactive Timeline Component: Responsive H5 UI with clean component architecture (upload, interactive canvas/bar timeline showing detected rallies, manual timestamp tweak/delete/add).
- Export Control Panel: Parameter selectors for resolution, compression ratio, buffer padding, and dual export modes (single merged highlight reel or zip of split clips).

## Acceptance Criteria

### Architectural Integrity & Maintainability
- [ ] Code follows strict modularity, clean layer separation (API -> Service -> Detector/Processor), and full type annotations.
- [ ] Database storage uses SQLite with SQLAlchemy models for robust task & rally tracking.
- [ ] AI detection engine uses the BaseRallyDetector interface, making it easy to plug in new detection models.

### Audio-Visual Detection & Pipeline
- [ ] Backend algorithm successfully processes match videos, returning rally start and end timestamps.
- [ ] Non-rally intervals (in-between points walking/waiting) are automatically skipped.

### FFmpeg Processing & Compression
- [ ] Videos can be compressed according to user-selected presets (e.g. 720p 24fps CRF 26 vs 1080p CRF 22).
- [ ] Trimmed video segments merge seamlessly without audio-video desync.

### H5 Interactive User Experience
- [ ] Web H5 page renders responsive video player with visual timeline markers for rallies.
- [ ] User can adjust pre-serve buffer (e.g. +1s) and post-land buffer (e.g. +1.5s) before rendering export.
- [ ] Downloads merged video or segment list cleanly from the web browser.
