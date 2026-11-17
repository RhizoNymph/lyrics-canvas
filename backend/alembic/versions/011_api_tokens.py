"""Personal API tokens: ``api_tokens``

Revision ID: 011
Revises: 010
Create Date: 2026-11-14

See docs/features/api_tokens.md#migration-011.

Upgrade: create ``api_tokens`` (id, user_id FK users ON DELETE CASCADE indexed, name, token_hash
unique sha256 hex, prefix, created_at, last_used_at, expires_at, revoked_at).

Downgrade: drop the table (every token stops working).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '011'
down_revision: Union[str, None] = '010'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'api_tokens',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('user_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False),
        sa.Column('name', sa.String(64), nullable=False),
        sa.Column('token_hash', sa.CHAR(64), nullable=False),
        sa.Column('prefix', sa.String(12), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('last_used_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('expires_at', sa.DateTime(timezone=True), nullable=True),
        sa.Column('revoked_at', sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint('token_hash', name='uq_api_tokens_token_hash'),
    )
    op.create_index('ix_api_tokens_user_id', 'api_tokens', ['user_id'])


def downgrade() -> None:
    op.drop_index('ix_api_tokens_user_id', table_name='api_tokens')
    op.drop_table('api_tokens')
