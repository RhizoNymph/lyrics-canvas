"""Personal API tokens (docs/features/api_tokens.md#data-model).

Only the sha256 of a token is stored (``token_hash``) plus its first 12 chars (``prefix``) for display;
the secret is shown once on create. Revoking sets ``revoked_at``; the row stays.
"""

import uuid
from datetime import datetime

from sqlalchemy import CHAR, DateTime, ForeignKey, String, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import UUID
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, new_uuid

NAME_MAX_LENGTH = 64
PREFIX_LENGTH = 12
HASH_LENGTH = 64


class ApiToken(Base):
    __tablename__ = "api_tokens"
    __table_args__ = (UniqueConstraint("token_hash", name="uq_api_tokens_token_hash"),)
    # RETURNING the server-side created_at on insert, so a fresh row is usable without a reload.
    __mapper_args__ = {"eager_defaults": True}

    id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=new_uuid)
    user_id: Mapped[uuid.UUID] = mapped_column(
        UUID(as_uuid=True), ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True
    )
    name: Mapped[str] = mapped_column(String(NAME_MAX_LENGTH), nullable=False)
    token_hash: Mapped[str] = mapped_column(CHAR(HASH_LENGTH), nullable=False)
    prefix: Mapped[str] = mapped_column(String(PREFIX_LENGTH), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), server_default=func.now(), nullable=False)
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
