"""Where a snippet sits on one canvas (docs/features/snippet_placements.md#data-model).

A snippet's content (text, grid, phonetics, tags, recordings) lives on ``snippets``; its layout on
each canvas holding it lives here, one row per (snippet, canvas). ``group_id`` must name a group of
the placement's own canvas; the services enforce that (app/services/placements/queries.py).
Every snippet has at least one placement; the operations that remove placements delete a snippet
left with none in the same transaction (app/services/placements/removal.py).
"""

import uuid
from datetime import datetime

from sqlalchemy import DateTime, Float, ForeignKey, Integer, UniqueConstraint, func, text
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid

DEFAULT_WIDTH = 300.0
DEFAULT_HEIGHT = 200.0


class SnippetPlacement(Base):
    __tablename__ = "snippet_placements"
    __table_args__ = (UniqueConstraint("snippet_id", "canvas_id", name="uq_snippet_placements_snippet_canvas"),)
    # RETURNING the server-side created_at on insert, so a fresh row is usable without a reload.
    __mapper_args__ = {"eager_defaults": True}

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    snippet_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("snippets.id", ondelete="CASCADE"), nullable=False, index=True
    )
    canvas_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("canvases.id", ondelete="CASCADE"), nullable=False, index=True
    )
    position_x: Mapped[float] = mapped_column(Float, nullable=False, default=0.0, server_default=text("0"))
    position_y: Mapped[float] = mapped_column(Float, nullable=False, default=0.0, server_default=text("0"))
    width: Mapped[float] = mapped_column(Float, nullable=False, default=DEFAULT_WIDTH, server_default=text("300"))
    height: Mapped[float] = mapped_column(Float, nullable=False, default=DEFAULT_HEIGHT, server_default=text("200"))
    z_index: Mapped[int] = mapped_column(Integer, nullable=False, default=0, server_default=text("0"))
    group_id: Mapped[uuid.UUID | None] = mapped_column(
        UUID(as_uuid=True), ForeignKey("groups.id", ondelete="SET NULL"), nullable=True
    )
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    snippet = relationship("Snippet", back_populates="placements", lazy="raise")
    group = relationship("Group", back_populates="placements", lazy="raise")
