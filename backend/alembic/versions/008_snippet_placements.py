"""Snippet placements: a snippet's layout per canvas, so one snippet can be on several canvases

Revision ID: 008
Revises: 007
Create Date: 2026-11-09

See docs/features/snippet_placements.md#migration-008.

Upgrade:
  1. create ``snippet_placements`` and copy one placement per snippet from its layout columns
     (``group_id`` is kept only when the group belongs to the snippet's canvas);
  2. add ``snippets.owner_id`` (FK users ON DELETE CASCADE, indexed), set from the canvas owner;
  3. drop ``snippets.canvas_id, position_x, position_y, width, height, z_index, group_id``.

Downgrade:
  1. re-add the columns;
  2. fill them from each snippet's oldest placement (created_at, then id);
  3. every additional placement becomes a duplicated snippet row (content columns and tag links
     copied, recordings and embeddings not);
  4. drop ``owner_id`` and the table. A snippet with no placement (the invariant forbids it) has no
     canvas to return to and is deleted.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '008'
down_revision: Union[str, None] = '007'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

UNIQUE = 'uq_snippet_placements_snippet_canvas'
OWNER_INDEX = 'ix_snippets_owner_id'
OWNER_FK = 'fk_snippets_owner_id'
LAYOUT = ('canvas_id', 'position_x', 'position_y', 'width', 'height', 'z_index', 'group_id')
# Content columns copied onto the snippet rows a downgrade splits off (owner_id is dropped after).
CONTENT = (
    'snippet_type', 'grid_mode', 'beats_per_line', 'grid_data', 'raw_text', 'phonetics', 'derived_from',
    'created_at', 'updated_at', 'content_updated_at',
)


def upgrade() -> None:
    op.create_table(
        'snippet_placements',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column('snippet_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('snippets.id', ondelete='CASCADE'), nullable=False),
        sa.Column('canvas_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('canvases.id', ondelete='CASCADE'), nullable=False),
        sa.Column('position_x', sa.Float, nullable=False, server_default=sa.text('0')),
        sa.Column('position_y', sa.Float, nullable=False, server_default=sa.text('0')),
        sa.Column('width', sa.Float, nullable=False, server_default=sa.text('300')),
        sa.Column('height', sa.Float, nullable=False, server_default=sa.text('200')),
        sa.Column('z_index', sa.Integer, nullable=False, server_default=sa.text('0')),
        sa.Column('group_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('groups.id', ondelete='SET NULL'), nullable=True),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.UniqueConstraint('snippet_id', 'canvas_id', name=UNIQUE),
    )
    op.create_index('ix_snippet_placements_canvas_id', 'snippet_placements', ['canvas_id'])
    op.create_index('ix_snippet_placements_snippet_id', 'snippet_placements', ['snippet_id'])
    op.execute(
        """
        INSERT INTO snippet_placements
            (id, snippet_id, canvas_id, position_x, position_y, width, height, z_index, group_id, created_at)
        SELECT gen_random_uuid(), s.id, s.canvas_id,
               coalesce(s.position_x, 0), coalesce(s.position_y, 0),
               coalesce(s.width, 300), coalesce(s.height, 200), coalesce(s.z_index, 0),
               g.id, coalesce(s.created_at, now())
        FROM snippets s
        LEFT JOIN groups g ON g.id = s.group_id AND g.canvas_id = s.canvas_id
        """
    )

    op.add_column('snippets', sa.Column('owner_id', postgresql.UUID(as_uuid=True), nullable=True))
    op.execute("UPDATE snippets SET owner_id = c.owner_id FROM canvases c WHERE c.id = snippets.canvas_id")
    op.alter_column('snippets', 'owner_id', nullable=False)
    op.create_foreign_key(OWNER_FK, 'snippets', 'users', ['owner_id'], ['id'], ondelete='CASCADE')
    op.create_index(OWNER_INDEX, 'snippets', ['owner_id'])

    # their foreign keys (canvases, groups) go with the columns
    for column in LAYOUT:
        op.drop_column('snippets', column)


def downgrade() -> None:
    op.add_column('snippets', sa.Column('canvas_id', postgresql.UUID(as_uuid=True), nullable=True))
    op.add_column('snippets', sa.Column('position_x', sa.Float, nullable=True))
    op.add_column('snippets', sa.Column('position_y', sa.Float, nullable=True))
    op.add_column('snippets', sa.Column('width', sa.Float, nullable=True))
    op.add_column('snippets', sa.Column('height', sa.Float, nullable=True))
    op.add_column('snippets', sa.Column('z_index', sa.Integer, nullable=True))
    op.add_column('snippets', sa.Column('group_id', postgresql.UUID(as_uuid=True), nullable=True))

    # rank 1 is the snippet's oldest placement and keeps the snippet's id; every other placement
    # gets a new snippet id
    op.execute(
        """
        CREATE TEMPORARY TABLE placement_split ON COMMIT DROP AS
        SELECT p.*,
               CASE WHEN p.rn = 1 THEN p.snippet_id ELSE gen_random_uuid() END AS new_snippet_id
        FROM (
            SELECT sp.*, row_number() OVER (PARTITION BY sp.snippet_id ORDER BY sp.created_at, sp.id) AS rn
            FROM snippet_placements sp
        ) p
        """
    )
    layout_set = ", ".join(f"{c} = ps.{c}" for c in LAYOUT)
    op.execute(
        f"UPDATE snippets SET {layout_set} FROM placement_split ps WHERE ps.rn = 1 AND ps.snippet_id = snippets.id"
    )
    content = ", ".join(CONTENT)
    content_from_s = ", ".join(f"s.{c}" for c in CONTENT)
    layout = ", ".join(LAYOUT)
    layout_from_ps = ", ".join(f"ps.{c}" for c in LAYOUT)
    op.execute(
        f"""
        INSERT INTO snippets (id, owner_id, {content}, {layout})
        SELECT ps.new_snippet_id, s.owner_id, {content_from_s}, {layout_from_ps}
        FROM placement_split ps JOIN snippets s ON s.id = ps.snippet_id
        WHERE ps.rn > 1
        """
    )
    op.execute(
        """
        INSERT INTO snippet_tags (snippet_id, tag_id)
        SELECT ps.new_snippet_id, st.tag_id
        FROM placement_split ps JOIN snippet_tags st ON st.snippet_id = ps.snippet_id
        WHERE ps.rn > 1
        """
    )
    # no placement means no canvas to return to (the invariant forbids it; recordings cascade)
    op.execute("DELETE FROM snippets WHERE canvas_id IS NULL")
    op.execute("DROP TABLE placement_split")

    op.alter_column('snippets', 'canvas_id', nullable=False)
    op.create_foreign_key('snippets_canvas_id_fkey', 'snippets', 'canvases', ['canvas_id'], ['id'])
    op.create_foreign_key('snippets_group_id_fkey', 'snippets', 'groups', ['group_id'], ['id'])
    op.drop_index(OWNER_INDEX, table_name='snippets')
    op.drop_constraint(OWNER_FK, 'snippets', type_='foreignkey')
    op.drop_column('snippets', 'owner_id')
    op.drop_index('ix_snippet_placements_snippet_id', table_name='snippet_placements')
    op.drop_index('ix_snippet_placements_canvas_id', table_name='snippet_placements')
    op.drop_table('snippet_placements')
