import enum
import uuid
from datetime import datetime

from sqlalchemy import String, Float, Integer, Enum, ForeignKey, DateTime, Text, func
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid


class SnippetType(str, enum.Enum):
    verse = "verse"
    chorus = "chorus"
    bridge = "bridge"
    hook = "hook"
    freestyle = "freestyle"


class GridMode(str, enum.Enum):
    auto = "auto"
    fixed = "fixed"


class Snippet(Base):
    __tablename__ = "snippets"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    canvas_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("canvases.id"), nullable=False)
    snippet_type: Mapped[SnippetType] = mapped_column(Enum(SnippetType), default=SnippetType.freestyle)
    grid_mode: Mapped[GridMode] = mapped_column(Enum(GridMode), default=GridMode.auto)
    beats_per_line: Mapped[int | None] = mapped_column(Integer, nullable=True)
    grid_data: Mapped[dict | None] = mapped_column(JSONB, nullable=True, default=lambda: {"lines": []})
    raw_text: Mapped[str] = mapped_column(Text, default="")
    position_x: Mapped[float] = mapped_column(Float, default=0.0)
    position_y: Mapped[float] = mapped_column(Float, default=0.0)
    width: Mapped[float] = mapped_column(Float, default=300.0)
    height: Mapped[float] = mapped_column(Float, default=200.0)
    z_index: Mapped[int] = mapped_column(Integer, default=0)
    group_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), ForeignKey("groups.id"), nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    canvas = relationship("Canvas", back_populates="snippets")
    group = relationship("Group", back_populates="snippets")
    tags = relationship("Tag", secondary="snippet_tags", back_populates="snippets")
    embedding = relationship("SnippetEmbedding", back_populates="snippet", uselist=False, cascade="all, delete-orphan")
