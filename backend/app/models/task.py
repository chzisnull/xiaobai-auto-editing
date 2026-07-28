from datetime import datetime, timezone
from typing import List, TYPE_CHECKING
from sqlalchemy import String, Float, DateTime
from sqlalchemy.orm import Mapped, mapped_column, relationship
from backend.app.db.session import Base

if TYPE_CHECKING:
    from backend.app.models.rally import Rally
    from backend.app.models.export import ExportJob

import os

class Task(Base):
    __tablename__ = "tasks"

    id: Mapped[str] = mapped_column(String, primary_key=True, index=True)
    filename: Mapped[str] = mapped_column(String, nullable=False)
    video_path: Mapped[str] = mapped_column(String, nullable=False)
    duration: Mapped[float] = mapped_column(Float, default=0.0)
    status: Mapped[str] = mapped_column(String, default="uploaded")  # uploaded, processing, completed, failed
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc)
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc)
    )

    @property
    def task_id(self) -> str:
        return self.id

    @property
    def video_url(self) -> str:
        base_name = os.path.basename(self.video_path) if self.video_path else ""
        return f"/uploads/{base_name}"

    # Relationships
    rallies: Mapped[List["Rally"]] = relationship(
        "Rally", back_populates="task", cascade="all, delete-orphan", order_by="Rally.rally_index"
    )
    export_jobs: Mapped[List["ExportJob"]] = relationship(
        "ExportJob", back_populates="task", cascade="all, delete-orphan"
    )
