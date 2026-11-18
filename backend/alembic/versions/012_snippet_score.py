"""Snippet score (musical mode): ``snippets.score`` and ``snippets.score_view``

Revision ID: 012
Revises: 011
Create Date: 2026-11-17

See docs/features/score_backend.md#migration-012 and spec/score_format.md.

Upgrade: add ``score JSONB NULL`` (score format v1; NULL = no score, grid only) and
``score_view BOOLEAN NOT NULL DEFAULT false`` (which view the snippet shows) to ``snippets``, plus
``CHECK (score IS NOT NULL OR NOT score_view)`` so a snippet without a score never shows the staff.
Existing rows get no score and the grid view.

Downgrade: drop the check constraint and both columns (every score is lost).
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '012'
down_revision: Union[str, None] = '011'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

CHECK = 'ck_snippets_score_view_needs_score'


def upgrade() -> None:
    op.add_column('snippets', sa.Column('score', postgresql.JSONB, nullable=True))
    op.add_column('snippets', sa.Column('score_view', sa.Boolean, nullable=False, server_default=sa.false()))
    op.create_check_constraint(CHECK, 'snippets', 'score IS NOT NULL OR NOT score_view')


def downgrade() -> None:
    op.drop_constraint(CHECK, 'snippets', type_='check')
    op.drop_column('snippets', 'score_view')
    op.drop_column('snippets', 'score')
