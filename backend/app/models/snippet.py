import enum
import uuid
from datetime import datetime

from sqlalchemy import Boolean, CheckConstraint, Enum, ForeignKey, DateTime, Integer, Text, event, false, func
from sqlalchemy.dialects.postgresql import UUID, JSONB
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid
from app.models.versioned import starts_at_initial_version, version_check, version_column


class SnippetType(str, enum.Enum):
    verse = "verse"
    chorus = "chorus"
    bridge = "bridge"
    hook = "hook"
    freestyle = "freestyle"


class GridMode(str, enum.Enum):
    auto = "auto"
    fixed = "fixed"


@starts_at_initial_version
class Snippet(Base):
    __tablename__ = "snippets"
    __table_args__ = (
        version_check("snippets"),
        # named as migration 012 names it: no staff view without a score
        CheckConstraint("score IS NOT NULL OR NOT score_view", name="ck_snippets_score_view_needs_score"),
    )

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
    # Score format v1 (spec/score_format.md), canonical JSON (score_to_json); SQL NULL = no score.
    # none_as_null: Python None must be SQL NULL, never the JSON value null.
    # Written by the save flow (app/services/score_save.py, docs/features/score_backend.md) and fork.
    score: Mapped[dict | None] = mapped_column(JSONB(none_as_null=True), nullable=True, default=None)
    # Which view the snippet shows: True = staff. Always False when score is NULL.
    score_view: Mapped[bool] = mapped_column(Boolean, nullable=False, default=False, server_default=false())
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now()
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )
    # Last content edit (text, type, grid, score, score view). Unlike updated_at, moves, resizes, placements and tags leave it alone;
    # snippet_service.update_snippet and the voice-take append (recording_service) bump it.
    # See docs/features/snippet_content_timestamp.md.
    content_updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    # Content version: +1 per change of text, type, grid mode, beats, grid, score, score view or tag links; layout and
    # phonetics-only recomputes leave it alone (docs/features/sync_versions.md#versions).
    version: Mapped[int] = version_column()

    # DB cascades remove placements with the snippet; the ORM never loads them implicitly.
    placements = relationship(
        "SnippetPlacement", back_populates="snippet", cascade="save-update, merge, delete", passive_deletes=True, lazy="raise"
    )
    tags = relationship("Tag", secondary="snippet_tags", back_populates="snippets")
    embedding = relationship("SnippetEmbedding", back_populates="snippet", uselist=False, cascade="all, delete-orphan")


@event.listens_for(Snippet, "init")
def _no_score_yet(target: Snippet, args: tuple, kwargs: dict) -> None:
    """A freshly constructed snippet reads ``score_view == False`` before it is flushed (the column
    default only applies on INSERT), like ``version`` (models/versioned.py)."""
    kwargs.setdefault("score_view", False)
