import enum
import uuid
from datetime import datetime

from sqlalchemy import DateTime, Enum, ForeignKey, Index, Integer, LargeBinary, String, Text, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, new_uuid


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
    """

    __tablename__ = "snippet_recordings"
    __table_args__ = (Index("ix_snippet_recordings_snippet_created", "snippet_id", "created_at"),)
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
