"""Word bank: per-user words and their tag links

Revision ID: 006
Revises: 005
Create Date: 2026-11-04

``bank_words`` holds a user's words and short phrases (docs/features/word_bank.md), unique on
``(user_id, lower(text))``; ``bank_word_tags`` links them to the user-wide ``tags``. Deleting a
user, a word or a tag removes the links by cascade. The downgrade drops both tables.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '006'
down_revision: Union[str, None] = '005'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.create_table(
        'bank_words',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            'user_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False
        ),
        sa.Column('text', sa.String(64), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index('ix_bank_words_user_id', 'bank_words', ['user_id'])
    op.create_index('uq_bank_words_user_lower_text', 'bank_words', ['user_id', sa.text('lower(text)')], unique=True)
    op.create_table(
        'bank_word_tags',
        sa.Column(
            'bank_word_id',
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey('bank_words.id', ondelete='CASCADE'),
            primary_key=True,
        ),
        sa.Column(
            'tag_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('tags.id', ondelete='CASCADE'), primary_key=True
        ),
    )


def downgrade() -> None:
    op.drop_table('bank_word_tags')
    op.drop_index('uq_bank_words_user_lower_text', table_name='bank_words')
    op.drop_index('ix_bank_words_user_id', table_name='bank_words')
    op.drop_table('bank_words')
