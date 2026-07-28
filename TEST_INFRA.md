# E2E Test Infra: Automatic Badminton Match Video Editing Software

## Test Philosophy
- Opaque-box, requirement-driven testing derived from `ORIGINAL_REQUEST.md`.
- Synthetic video & audio generation via FFmpeg filtergraphs with millisecond-accurate ground truth timestamps.
- Systematic methodology: Category-Partition + Boundary Value Analysis + Pairwise Combinations + Real-World Workload Testing.

## Feature Inventory
| # | Feature | Source (Requirement) | Tier 1 (Coverage) | Tier 2 (Boundary) | Tier 3 (Pairwise) | Tier 4 (Real-World) |
|---|---------|---------------------|:-----------------:|:-----------------:|:-----------------:|:------------------:|
| F1 | Video Upload API | R2. Clean Architecture | 5 | 2 | ✓ | ✓ |
| F2 | AI Rally Detection | R1. Audio-Visual Engine | 5 | 2 | ✓ | ✓ |
| F3 | Interactive Timeline & Tweak | R3. H5 Interactive Platform | 5 | 2 | ✓ | ✓ |
| F4 | FFmpeg Trimming & Compression | R2. FFmpeg Pipeline | 5 | 2 | ✓ | ✓ |
| F5 | Dual Export & Download | R3. Export Control Panel | 5 | 1 | ✓ | ✓ |
| **Total** | | | **25** | **9** | **4** | **4** |

## Synthetic Video Generator Specification
- **Audio Pulse**: 3.5kHz sine wave tone bursts (matching 2kHz-6kHz bandpass filter) at rally interval timestamps.
- **Visual Motion**: High-contrast moving box patterns across frames during rally windows, static baseline outside rally windows.
- **Ground Truth**: Exact `(start_time, end_time)` pairs embedded in video metadata and sidecar JSON.

## Test Tier Summary
- **Tier 1 (25 tests)**: Feature Coverage for Upload, AI Analyze, Timeline Tweak, FFmpeg Export, Download.
- **Tier 2 (9 tests)**: Boundaries: empty file, 0 rallies found, buffer padding underflow/overflow, max compression CRF 28, non-ASCII filenames.
- **Tier 3 (4 tests)**: Integration Lifecycles: Full E2E Upload -> AI Analyze -> Tweak -> Export Merged/ZIP.
- **Tier 4 (4 tests)**: Stress/Real-world: 30-min video processing, high background noise, rapid rallies, hwaccel fallback.
