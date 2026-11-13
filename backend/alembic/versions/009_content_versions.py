"""Content versions for offline sync: ``version`` on snippets, canvases, tags and bank_words

Revision ID: 009
Revises: 008
Create Date: 2026-11-11

See docs/features/sync_versions.md#migration-009.

Upgrade: add ``version INTEGER NOT NULL DEFAULT 1`` with ``CHECK (version >= 1)`` to each table;
existing rows get 1.

Downgrade: drop the check constraints and the columns; the rows are kept.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = '009'
down_revision: Union[str, None] = '008'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

TABLES = ('snippets', 'canvases', 'tags', 'bank_words')


def check_name(table: str) -> str:
    return f'ck_{table}_version_positive'


def upgrade() -> None:
    for table in TABLES:
        op.add_column(table, sa.Column('version', sa.Integer, nullable=False, server_default=sa.text('1')))
        op.create_check_constraint(check_name(table), table, 'version >= 1')


def downgrade() -> None:
    for table in TABLES:
        op.drop_constraint(check_name(table), table, type_='check')
        op.drop_column(table, 'version')
