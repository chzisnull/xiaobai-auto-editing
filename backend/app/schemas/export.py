import math
from typing import List, Optional
from pydantic import BaseModel, Field, field_validator, model_validator

ALLOWED_RESOLUTIONS = {"original", "1080p", "720p"}
ALLOWED_EXPORT_TYPES = {"merged", "zip"}
ALLOWED_CODECS = {"h264", "hevc"}


class ExportRally(BaseModel):
    start: float = Field(..., ge=0.0)
    end: float = Field(..., gt=0.0)

    @field_validator("start", "end", mode="before")
    @classmethod
    def validate_finite(cls, value: float) -> float:
        parsed = float(value)
        if not math.isfinite(parsed):
            raise ValueError("Rally timestamps must be finite numbers")
        return parsed

    @model_validator(mode="after")
    def validate_order(self) -> "ExportRally":
        if self.end <= self.start:
            raise ValueError("Rally end must be greater than start")
        return self

class ExportRequest(BaseModel):
    rallies: Optional[List[ExportRally]] = Field(default=None, max_length=500)
    resolution: str = Field(default="1080p")
    pre_buffer: float = Field(default=1.0, ge=0.0)
    post_buffer: float = Field(default=1.5, ge=0.0)
    export_type: str = Field(default="merged")
    codec: str = Field(default="h264")
    crf: Optional[int] = Field(default=None, ge=16, le=35)

    @field_validator("resolution")
    @classmethod
    def validate_resolution(cls, v: str) -> str:
        if v.lower() not in ALLOWED_RESOLUTIONS:
            raise ValueError(f"resolution must be one of {ALLOWED_RESOLUTIONS}, got '{v}'")
        return v.lower()

    @field_validator("export_type")
    @classmethod
    def validate_export_type(cls, v: str) -> str:
        if v.lower() not in ALLOWED_EXPORT_TYPES:
            raise ValueError(f"export_type must be one of {ALLOWED_EXPORT_TYPES}, got '{v}'")
        return v.lower()

    @field_validator("codec")
    @classmethod
    def validate_codec(cls, v: str) -> str:
        if v.lower() not in ALLOWED_CODECS:
            raise ValueError(f"codec must be one of {ALLOWED_CODECS}, got '{v}'")
        return v.lower()
