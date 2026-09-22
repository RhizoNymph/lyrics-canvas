import uuid
from datetime import datetime

from sqlalchemy import String, Boolean, ForeignKey, DateTime, func
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid


class Canvas(Base):
    __tablename__ = "canvases"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    owner_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("users.id"), nullable=False)
    title: Mapped[str] = mapped_column(String(255), nullable=False, default="Untitled Canvas")
    is_public: Mapped[bool] = mapped_column(Boolean, default=False)
    share_token: Mapped[str | None] = mapped_column(String(64), unique=True, nullable=True)
    viewport_state: Mapped[dict | None] = mapped_column(JSONB, nullable=True, default=lambda: {"x": 0, "y": 0, "zoom": 1})
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    owner = relationship("User", back_populates="canvases")
    snippets = relationship("Snippet", back_populates="canvas", cascade="all, delete-orphan")
    groups = relationship("Group", back_populates="canvas", cascade="all, delete-orphan")
    tags = relationship("Tag", back_populates="canvas", cascade="all, delete-orphan")
