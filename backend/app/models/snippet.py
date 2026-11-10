import enum
import uuid
from datetime import datetime

from sqlalchemy import Enum, ForeignKey, DateTime, Integer, Text, func
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
    # Content only: the layout on each canvas lives on SnippetPlacement (docs/features/snippet_placements.md).
    owner_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    snippet_type: Mapped[SnippetType] = mapped_column(Enum(SnippetType), default=SnippetType.freestyle)
    grid_mode: Mapped[GridMode] = mapped_column(Enum(GridMode), default=GridMode.auto)
    beats_per_line: Mapped[int | None] = mapped_column(Integer, nullable=True)
    grid_data: Mapped[dict | None] = mapped_column(JSONB, nullable=True, default=lambda: {"lines": []})
    raw_text: Mapped[str] = mapped_column(Text, default="")
    phonetics: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    derived_from: Mapped[dict | None] = mapped_column(JSONB, nullable=True)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
    # Last content edit (text, type, grid). Unlike updated_at, moves, resizes, placements and tags leave it alone;
    # snippet_service.update_snippet and the voice-take append (recording_service) bump it.
    # See docs/features/snippet_content_timestamp.md.
    content_updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )

    # DB cascades remove placements with the snippet; the ORM never loads them implicitly.
    placements = relationship(
        "SnippetPlacement", back_populates="snippet", cascade="save-update, merge, delete", passive_deletes=True, lazy="raise"
    )
    tags = relationship("Tag", secondary="snippet_tags", back_populates="snippets")
    embedding = relationship("SnippetEmbedding", back_populates="snippet", uselist=False, cascade="all, delete-orphan")
