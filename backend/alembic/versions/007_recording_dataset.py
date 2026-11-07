"""Recording timings, STT prompt and transcript verification

Revision ID: 007
Revises: 006
Create Date: 2026-11-06

``snippet_recordings`` gains six nullable columns (docs/features/voice_dataset.md): the take's STT
``words`` (jsonb), the ``line_spans`` behind its assembled lines (jsonb), the STT ``language``, the
``initial_prompt`` sent to STT, and the user's checked transcript ``verified_text`` with
``verified_at``. A check constraint keeps the last two both null or both set. Existing rows keep
null everywhere. The downgrade drops all six columns.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '007'
down_revision: Union[str, None] = '006'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

VERIFIED_PAIR = 'ck_snippet_recordings_verified_pair'


def upgrade() -> None:
    op.add_column('snippet_recordings', sa.Column('words', postgresql.JSONB(), nullable=True))
    op.add_column('snippet_recordings', sa.Column('line_spans', postgresql.JSONB(), nullable=True))
    op.add_column('snippet_recordings', sa.Column('language', sa.String(8), nullable=True))
    op.add_column('snippet_recordings', sa.Column('initial_prompt', sa.Text(), nullable=True))
    op.add_column('snippet_recordings', sa.Column('verified_text', sa.Text(), nullable=True))
    op.add_column('snippet_recordings', sa.Column('verified_at', sa.DateTime(timezone=True), nullable=True))
    op.create_check_constraint(
        VERIFIED_PAIR, 'snippet_recordings', '(verified_text IS NULL) = (verified_at IS NULL)'
    )


def downgrade() -> None:
    op.drop_constraint(VERIFIED_PAIR, 'snippet_recordings', type_='check')
    for column in ('verified_at', 'verified_text', 'initial_prompt', 'language', 'line_spans', 'words'):
        op.drop_column('snippet_recordings', column)
