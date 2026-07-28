from datetime import datetime, timezone
from typing import TYPE_CHECKING
from sqlalchemy import String, Float, DateTime, ForeignKey
from sqlalchemy.orm import Mapped, mapped_column, relationship
from backend.app.db.session import Base

if TYPE_CHECKING:
    from backend.app.models.task import Task

class ExportJob(Base):
    __tablename__ = "export_jobs"

    id: Mapped[str] = mapped_column(String, primary_key=True, index=True)
    task_id: Mapped[str] = mapped_column(String, ForeignKey("tasks.id", ondelete="CASCADE"), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String, default="completed")  # rendering, completed, failed
    export_type: Mapped[str] = mapped_column(String, default="merged")  # merged, zip
    resolution: Mapped[str] = mapped_column(String, default="1080p")  # original, 1080p, 720p
    pre_buffer: Mapped[float] = mapped_column(Float, default=1.0)
    post_buffer: Mapped[float] = mapped_column(Float, default=1.5)
    export_url: Mapped[str] = mapped_column(String, nullable=False)
    file_size_mb: Mapped[float] = mapped_column(Float, default=0.0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc)
    )

    # Relationships
    task: Mapped["Task"] = relationship("Task", back_populates="export_jobs")
