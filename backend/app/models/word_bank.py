"""Word bank: a per-user list of words and short phrases, tagged with the user-wide tags
(docs/features/word_bank.md#data-model)."""

import uuid
from datetime import datetime

from sqlalchemy import Column, DateTime, ForeignKey, Index, String, Table, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.models.base import Base, new_uuid

MAX_WORD_LENGTH = 64  # code points; the column width (validated before it reaches the DB)

bank_word_tags = Table(
    "bank_word_tags",
    Base.metadata,
    Column("bank_word_id", UUID(as_uuid=True), ForeignKey("bank_words.id", ondelete="CASCADE"), primary_key=True),
    Column("tag_id", UUID(as_uuid=True), ForeignKey("tags.id", ondelete="CASCADE"), primary_key=True),
)


class BankWord(Base):
    """A word or short phrase in a user's bank. ``text`` keeps its case; unique per user ignoring case."""

    __tablename__ = "bank_words"

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    text: Mapped[str] = mapped_column(String(MAX_WORD_LENGTH), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)

    # tag deletes remove the links by the FK cascade, not through the ORM
    tags = relationship("Tag", secondary=bank_word_tags, passive_deletes=True, lazy="raise")


Index("uq_bank_words_user_lower_text", BankWord.user_id, func.lower(BankWord.text), unique=True)
