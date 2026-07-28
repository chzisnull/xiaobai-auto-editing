from datetime import datetime
from typing import Optional
from pydantic import BaseModel, ConfigDict, Field, AliasChoices

class TaskCreate(BaseModel):
    filename: str
    video_path: str
    duration: float = 0.0

class TaskResponse(BaseModel):
    task_id: str = Field(..., validation_alias=AliasChoices("task_id", "id"))
    filename: str
    video_url: str = Field(default="")
    duration: float
    status: str
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None

    model_config = ConfigDict(from_attributes=True, populate_by_name=True)
