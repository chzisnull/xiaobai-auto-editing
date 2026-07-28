import os
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[3]


def _configured_path(env_name: str, default_name: str) -> Path:
    configured = Path(os.getenv(env_name, default_name)).expanduser()
    if not configured.is_absolute():
        configured = PROJECT_ROOT / configured
    return configured.resolve()


def get_upload_dir() -> Path:
    return _configured_path("UPLOAD_DIR", "uploads")


def get_exports_dir() -> Path:
    return _configured_path("EXPORTS_DIR", "exports")


def get_frontend_dir() -> Path:
    return PROJECT_ROOT / "frontend"
