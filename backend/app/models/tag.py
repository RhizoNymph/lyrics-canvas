import uuid
from datetime import datetime

from sqlalchemy import Column, DateTime, ForeignKey, Index, String, Table, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid

DEFAULT_TAG_COLOR = "#6366f1"

snippet_tags = Table(
    "snippet_tags",
    Base.metadata,
    Column("snippet_id", UUID(as_uuid=True), ForeignKey("snippets.id", ondelete="CASCADE"), primary_key=True),
    Column("tag_id", UUID(as_uuid=True), ForeignKey("tags.id", ondelete="CASCADE"), primary_key=True),
)


class Tag(Base):
    """A user-wide label (one list per user, shared by all canvases); names unique ignoring case."""

    __tablename__ = "tags"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    color: Mapped[str] = mapped_column(String(7), default=DEFAULT_TAG_COLOR)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    snippets = relationship("Snippet", secondary=snippet_tags, back_populates="tags")


Index("uq_tags_user_lower_name", Tag.user_id, func.lower(Tag.name), unique=True)
