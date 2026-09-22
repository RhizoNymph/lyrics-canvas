import uuid
from datetime import datetime

from sqlalchemy import ForeignKey, DateTime, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship
from pgvector.sqlalchemy import Vector

from app.models.base import Base


class SnippetEmbedding(Base):
    __tablename__ = "snippet_embeddings"

    snippet_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("snippets.id", ondelete="CASCADE"), primary_key=True
    )
    embedding = mapped_column(Vector(384), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now()
    )

    snippet = relationship("Snippet", back_populates="embedding")
