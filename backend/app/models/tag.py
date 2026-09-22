import uuid
from datetime import datetime

from sqlalchemy import String, ForeignKey, UniqueConstraint, Column, Table
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid

snippet_tags = Table(
    "snippet_tags",
    Base.metadata,
    Column("snippet_id", UUID(as_uuid=True), ForeignKey("snippets.id", ondelete="CASCADE"), primary_key=True),
    Column("tag_id", UUID(as_uuid=True), ForeignKey("tags.id", ondelete="CASCADE"), primary_key=True),
)


class Tag(Base):
    __tablename__ = "tags"
    __table_args__ = (UniqueConstraint("canvas_id", "name", name="uq_tag_canvas_name"),)

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    canvas_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), ForeignKey("canvases.id"), nullable=False)
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    color: Mapped[str] = mapped_column(String(7), default="#6366f1")

    canvas = relationship("Canvas", back_populates="tags")
    snippets = relationship("Snippet", secondary=snippet_tags, back_populates="tags")
