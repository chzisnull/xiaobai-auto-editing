import math
from typing import List, Optional
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator, computed_field

class RallyItem(BaseModel):
    id: Optional[int] = None
    rally_index: int = 1
    start_time: float = Field(..., ge=0.0)
    end_time: float
    duration: float
    confidence: float = Field(default=1.0, ge=0.0, le=1.0)

    @field_validator("start_time", "end_time", "duration", "confidence", mode="before")
    @classmethod
    def validate_finite_floats(cls, v: float) -> float:
        if v is not None:
            val = float(v)
            if not math.isfinite(val):
                raise ValueError("Float value must be finite (not NaN or Inf)")
            return val
        return v

    @model_validator(mode="after")
    def validate_timestamps(self) -> "RallyItem":
        if self.end_time <= self.start_time:
            raise ValueError(f"end_time ({self.end_time}) must be strictly greater than start_time ({self.start_time})")
        return self

    @computed_field
    @property
    def start(self) -> float:
        return self.start_time

    @computed_field
    @property
    def end(self) -> float:
        return self.end_time

    model_config = ConfigDict(from_attributes=True)

class RallyListResponse(BaseModel):
    task_id: str
    status: str
    total_rallies: int
    original_duration: float
    edited_duration: float
    rallies: List[RallyItem]

    model_config = ConfigDict(from_attributes=True)


class CourtPoint(BaseModel):
    x: float = Field(..., ge=0.0, le=1.0)
    y: float = Field(..., ge=0.0, le=1.0)


class AnalyzeRequest(BaseModel):
    court_roi: Optional[List[CourtPoint]] = Field(default=None, min_length=4, max_length=4)

    @model_validator(mode="after")
    def validate_court_area(self) -> "AnalyzeRequest":
        if self.court_roi is None:
            return self
        points = [(point.x, point.y) for point in self.court_roi]
        area = abs(sum(
            x1 * y2 - x2 * y1
            for (x1, y1), (x2, y2) in zip(points, points[1:] + points[:1])
        )) / 2.0
        if area < 0.02:
            raise ValueError("court_roi must cover a visible court area")
        return self
