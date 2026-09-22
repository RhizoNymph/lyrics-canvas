"""Initial schema

Revision ID: 001
Revises:
Create Date: 2024-01-01

"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '001'
down_revision: Union[str, None] = None
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    # Enable pgvector extension
    op.execute('CREATE EXTENSION IF NOT EXISTS vector')

    op.create_table(
        'users',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('email', sa.String(255), unique=True, nullable=False, index=True),
        sa.Column('password_hash', sa.String(255), nullable=True),
        sa.Column('display_name', sa.String(100), nullable=False),
        sa.Column('oauth_provider', sa.String(50), nullable=True),
        sa.Column('oauth_id', sa.String(255), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    op.create_table(
        'canvases',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('owner_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('users.id'), nullable=False),
        sa.Column('title', sa.String(255), nullable=False),
        sa.Column('is_public', sa.Boolean, default=False),
        sa.Column('share_token', sa.String(64), unique=True, nullable=True),
        sa.Column('viewport_state', postgresql.JSONB, nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    op.create_table(
        'groups',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('canvas_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('canvases.id'), nullable=False),
        sa.Column('name', sa.String(255), nullable=False),
        sa.Column('position_x', sa.Float, default=0.0),
        sa.Column('position_y', sa.Float, default=0.0),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    op.create_table(
        'snippets',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('canvas_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('canvases.id'), nullable=False),
        sa.Column('snippet_type', sa.Enum('verse', 'chorus', 'bridge', 'hook', 'freestyle', name='snippettype'), default='freestyle'),
        sa.Column('grid_mode', sa.Enum('auto', 'fixed', name='gridmode'), default='auto'),
        sa.Column('beats_per_line', sa.Integer, nullable=True),
        sa.Column('grid_data', postgresql.JSONB, nullable=True),
        sa.Column('raw_text', sa.Text, default=''),
        sa.Column('position_x', sa.Float, default=0.0),
        sa.Column('position_y', sa.Float, default=0.0),
        sa.Column('width', sa.Float, default=300.0),
        sa.Column('height', sa.Float, default=200.0),
        sa.Column('z_index', sa.Integer, default=0),
        sa.Column('group_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('groups.id'), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    op.create_table(
        'tags',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('canvas_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('canvases.id'), nullable=False),
        sa.Column('name', sa.String(100), nullable=False),
        sa.Column('color', sa.String(7), default='#6366f1'),
        sa.UniqueConstraint('canvas_id', 'name', name='uq_tag_canvas_name'),
    )

    op.create_table(
        'snippet_tags',
        sa.Column('snippet_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('snippets.id', ondelete='CASCADE'), primary_key=True),
        sa.Column('tag_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('tags.id', ondelete='CASCADE'), primary_key=True),
    )

    op.create_table(
        'snippet_embeddings',
        sa.Column('snippet_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('snippets.id', ondelete='CASCADE'), primary_key=True),
        sa.Column('embedding', sa.LargeBinary, nullable=False),  # pgvector type in prod
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now()),
    )


def downgrade() -> None:
    op.drop_table('snippet_embeddings')
    op.drop_table('snippet_tags')
    op.drop_table('tags')
    op.drop_table('snippets')
    op.drop_table('groups')
    op.drop_table('canvases')
    op.drop_table('users')
    op.execute('DROP TYPE IF EXISTS snippettype')
    op.execute('DROP TYPE IF EXISTS gridmode')
