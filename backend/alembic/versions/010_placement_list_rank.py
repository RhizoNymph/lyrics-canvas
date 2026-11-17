"""Custom list order: ``snippet_placements.list_rank``

Revision ID: 010
Revises: 009
Create Date: 2026-11-14

See docs/features/snippet_placements.md#migration-010.

Upgrade:
  1. add ``list_rank VARCHAR(64) COLLATE "C"`` (nullable for the backfill);
  2. per canvas, in the list order clients showed until now (snippet ``content_updated_at`` desc,
     ``created_at`` desc, snippet id asc), give the placements ``generateNKeysBetween(null, null, n)``
     = ``a0, a1, ..., az, b00, ...``;
  3. make it NOT NULL and index ``(canvas_id, list_rank)``.

Downgrade: drop the index and the column; the rows are kept.
"""
from collections.abc import Iterator
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa

revision: str = '010'
down_revision: Union[str, None] = '009'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

INDEX = 'ix_snippet_placements_canvas_list_rank'
DIGITS = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'


def _increment(key: str) -> str:
    """``incrementInteger`` of fractional-indexing 3.2.0 for the integer keys ``a0`` upwards (a frozen
    copy: a migration must not change when app code does). ``z`` + 26 ``z`` digits is never reached."""
    head, digits = key[0], list(key[1:])
    for i in range(len(digits) - 1, -1, -1):
        d = DIGITS.index(digits[i]) + 1
        if d < len(DIGITS):
            digits[i] = DIGITS[d]
            return head + ''.join(digits)
        digits[i] = DIGITS[0]
    # every digit carried: the next head letter has one more digit
    return chr(ord(head) + 1) + ''.join(digits) + DIGITS[0]


def sequential_keys() -> Iterator[str]:
    """``generateNKeysBetween(null, null, n)`` for any n: ``a0``, ``a1``, ... (tests/placements check
    it against the port)."""
    key = 'a0'
    while True:
        yield key
        key = _increment(key)


def upgrade() -> None:
    op.add_column('snippet_placements', sa.Column('list_rank', sa.String(64, collation='C'), nullable=True))
    conn = op.get_bind()
    rows = conn.execute(sa.text(
        'SELECT p.id, p.canvas_id FROM snippet_placements p JOIN snippets s ON s.id = p.snippet_id '
        'ORDER BY p.canvas_id, s.content_updated_at DESC, s.created_at DESC, s.id'
    )).all()
    updates: list[dict[str, object]] = []
    canvas = None
    keys = sequential_keys()
    for placement_id, canvas_id in rows:
        if canvas_id != canvas:
            canvas, keys = canvas_id, sequential_keys()
        updates.append({'id': placement_id, 'rank': next(keys)})
    if updates:
        conn.execute(sa.text('UPDATE snippet_placements SET list_rank = :rank WHERE id = :id'), updates)
    op.alter_column('snippet_placements', 'list_rank', nullable=False)
    op.create_index(INDEX, 'snippet_placements', ['canvas_id', 'list_rank'])


def downgrade() -> None:
    op.drop_index(INDEX, table_name='snippet_placements')
    op.drop_column('snippet_placements', 'list_rank')
