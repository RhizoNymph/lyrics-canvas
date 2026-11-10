import enum
import uuid
from datetime import datetime

from sqlalchemy import CheckConstraint, DateTime, Enum, ForeignKey, Index, Integer, LargeBinary, String, Text, func
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, new_uuid

LANGUAGE_MAX_LENGTH = 8


class SplitMode(str, enum.Enum):
    """How a voice take's transcript was broken into lines."""

    taps = "taps"  # the user's single taps during recording
    auto = "auto"  # the server's auto split (pause, balance, rhyme)
    single = "single"  # the auto split kept the take as one line


class SnippetRecording(Base):
    """The audio of a voice take, attached to the snippet its lines went into.

    No ORM relationship to ``Snippet`` on purpose: snippet loads never pull audio, and deleting a
    snippet (or its canvas) removes its recordings through the database's ON DELETE CASCADE.
    A merge moves recordings to the merged snippet before the originals are deleted.
    See docs/features/voice_capture.md.

    The dataset columns (migration 007, docs/features/voice_dataset.md) are all nullable; rows from
    before it keep nulls. ``words``/``line_spans`` are read and written through
    app/services/voice/timings.py, which enforces the span invariants.
    """

    __tablename__ = "snippet_recordings"
    __table_args__ = (
        Index("ix_snippet_recordings_snippet_created", "snippet_id", "created_at"),
        CheckConstraint(
            "(verified_text IS NULL) = (verified_at IS NULL)", name="ck_snippet_recordings_verified_pair"
        ),
    )
    # RETURNING the server-side created_at on insert, so a fresh row is usable without a reload.
    __mapper_args__ = {"eager_defaults": True}

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    snippet_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("snippets.id", ondelete="CASCADE"), nullable=False
    )
    mime_type: Mapped[str] = mapped_column(String(127), nullable=False)
    duration_ms: Mapped[int] = mapped_column(Integer, nullable=False)
    byte_size: Mapped[int] = mapped_column(Integer, nullable=False)
    # Deferred: listing recordings never loads the audio; only the audio endpoint reads it.
    audio: Mapped[bytes] = mapped_column(LargeBinary, nullable=False, deferred=True)
    transcript: Mapped[str] = mapped_column(Text, nullable=False)
    split_mode: Mapped[SplitMode] = mapped_column(
        Enum(SplitMode, name="recording_split_mode"), nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    # STT words in service order: [{"text", "start_ms", "end_ms", "probability"}]
    words: Mapped[list | None] = mapped_column(JSONB(none_as_null=True), nullable=True)
    # per assembled line, [start, end) indices into ``words``
    line_spans: Mapped[list | None] = mapped_column(JSONB(none_as_null=True), nullable=True)
    language: Mapped[str | None] = mapped_column(String(LANGUAGE_MAX_LENGTH), nullable=True)
    initial_prompt: Mapped[str | None] = mapped_column(Text, nullable=True)  # exactly what STT was sent
    # the user's checked transcript, lines joined by "\n"; both null or both set (check constraint)
    verified_text: Mapped[str | None] = mapped_column(Text, nullable=True)
    verified_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
