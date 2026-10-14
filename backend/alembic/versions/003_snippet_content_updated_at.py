"""Snippet content edit time, separate from updated_at

Revision ID: 003
Revises: 002
Create Date: 2026-10-13

updated_at moves on every write (drags, resizes); content_updated_at only on content edits.
Existing rows start from their updated_at, the best available estimate.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = '003'
down_revision: Union[str, None] = '002'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        'snippets',
        sa.Column('content_updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=True),
    )
    # updated_at is nullable in 001, hence the fallbacks.
    op.execute("UPDATE snippets SET content_updated_at = COALESCE(updated_at, created_at, now())")
    op.alter_column('snippets', 'content_updated_at', nullable=False)


def downgrade() -> None:
    op.drop_column('snippets', 'content_updated_at')
