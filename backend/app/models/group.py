import uuid
from datetime import datetime

from sqlalchemy import String, Float, ForeignKey, DateTime, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid


class Group(Base):
    __tablename__ = "groups"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    canvas_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("canvases.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    position_x: Mapped[float] = mapped_column(Float, default=0.0)
    position_y: Mapped[float] = mapped_column(Float, default=0.0)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )

    canvas = relationship("Canvas", back_populates="groups")
    snippets = relationship("Snippet", back_populates="group")
