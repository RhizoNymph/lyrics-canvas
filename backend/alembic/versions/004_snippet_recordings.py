"""Voice-take recordings attached to snippets

Revision ID: 004
Revises: 003
Create Date: 2026-11-01

One row per voice take: the audio (bytea), its MIME type, size and duration, the transcript as
assembled into lines, and how it was split. Deleting a snippet cascades to its recordings; a merge
moves them to the merged snippet first (snippet_service.replace_with_merged).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '004'
down_revision: Union[str, None] = '003'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

SPLIT_MODE = postgresql.ENUM('taps', 'auto', 'single', name='recording_split_mode', create_type=False)


def upgrade() -> None:
    SPLIT_MODE.create(op.get_bind(), checkfirst=True)
    op.create_table(
        'snippet_recordings',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            'snippet_id', postgresql.UUID(as_uuid=True),
            sa.ForeignKey('snippets.id', ondelete='CASCADE'), nullable=False,
        ),
        sa.Column('mime_type', sa.String(127), nullable=False),
        sa.Column('duration_ms', sa.Integer(), nullable=False),
        sa.Column('byte_size', sa.Integer(), nullable=False),
        sa.Column('audio', sa.LargeBinary(), nullable=False),
        sa.Column('transcript', sa.Text(), nullable=False),
        sa.Column('split_mode', SPLIT_MODE, nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index('ix_snippet_recordings_snippet_created', 'snippet_recordings', ['snippet_id', 'created_at'])


def downgrade() -> None:
    op.drop_index('ix_snippet_recordings_snippet_created', table_name='snippet_recordings')
    op.drop_table('snippet_recordings')
    SPLIT_MODE.drop(op.get_bind(), checkfirst=True)
