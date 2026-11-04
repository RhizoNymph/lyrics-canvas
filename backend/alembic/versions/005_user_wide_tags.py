"""User-wide tags (duplicates merged) and saved tag-expression presets

Revision ID: 005
Revises: 004
Create Date: 2026-11-03

Tags move from canvases to users (docs/features/tag_expressions.md). Each tag gets the owner of
its canvas; tags of one user whose names are equal ignoring case are merged into the one with the
smallest ``id::text`` (its name and color win), their snippet links are repointed (duplicates
dropped) and the others are deleted. ``canvas_id`` goes, a unique index on
``(user_id, lower(name))`` replaces ``uq_tag_canvas_name``, and ``created_at`` is added.

``tag_presets`` holds named, normalized expressions per user, unique on ``(user_id, lower(name))``.

The downgrade drops the presets and splits each tag back into one copy per canvas it is used on
(the first canvas by ``id::text`` keeps the original id); an unused tag goes to the user's oldest
canvas, or is deleted when the user has none. Merged names stay merged.
"""
from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision: str = '005'
down_revision: Union[str, None] = '004'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

# survivor of each (user, lower(name)) group: the smallest id::text
_RANKED = """
    SELECT id, first_value(id) OVER (PARTITION BY user_id, lower(name) ORDER BY id::text) AS survivor
    FROM tags
"""


def upgrade() -> None:
    op.add_column('tags', sa.Column('user_id', postgresql.UUID(as_uuid=True), nullable=True))
    op.add_column(
        'tags', sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False)
    )
    op.execute("UPDATE tags SET user_id = canvases.owner_id FROM canvases WHERE canvases.id = tags.canvas_id")

    op.execute(f"""
        INSERT INTO snippet_tags (snippet_id, tag_id)
        SELECT st.snippet_id, r.survivor
        FROM snippet_tags st JOIN ({_RANKED}) r ON r.id = st.tag_id
        WHERE r.id <> r.survivor
        ON CONFLICT DO NOTHING
    """)
    # the losers' own links go with them (snippet_tags.tag_id ON DELETE CASCADE)
    op.execute(f"DELETE FROM tags WHERE id IN (SELECT id FROM ({_RANKED}) r WHERE r.id <> r.survivor)")

    op.alter_column('tags', 'user_id', nullable=False)
    op.create_foreign_key('fk_tags_user_id', 'tags', 'users', ['user_id'], ['id'], ondelete='CASCADE')
    op.drop_constraint('uq_tag_canvas_name', 'tags', type_='unique')
    op.drop_column('tags', 'canvas_id')
    op.create_index('uq_tags_user_lower_name', 'tags', ['user_id', sa.text('lower(name)')], unique=True)

    op.create_table(
        'tag_presets',
        sa.Column('id', postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column(
            'user_id', postgresql.UUID(as_uuid=True), sa.ForeignKey('users.id', ondelete='CASCADE'), nullable=False
        ),
        sa.Column('name', sa.String(100), nullable=False),
        sa.Column('expression', sa.Text(), nullable=False),
        sa.Column('created_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column('updated_at', sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
    )
    op.create_index(
        'uq_tag_presets_user_lower_name', 'tag_presets', ['user_id', sa.text('lower(name)')], unique=True
    )


def downgrade() -> None:
    op.drop_index('uq_tag_presets_user_lower_name', table_name='tag_presets')
    op.drop_table('tag_presets')

    op.drop_index('uq_tags_user_lower_name', table_name='tags')
    op.add_column('tags', sa.Column('canvas_id', postgresql.UUID(as_uuid=True), nullable=True))
    op.execute("""
        CREATE TEMP TABLE tag_split AS
        SELECT tag_id, canvas_id, rn, CASE WHEN rn = 1 THEN tag_id ELSE gen_random_uuid() END AS new_id
        FROM (
            SELECT tag_id, canvas_id, row_number() OVER (PARTITION BY tag_id ORDER BY canvas_id::text) AS rn
            FROM (
                SELECT DISTINCT st.tag_id, s.canvas_id
                FROM snippet_tags st JOIN snippets s ON s.id = st.snippet_id
            ) used
        ) ranked
    """)
    op.execute("UPDATE tags SET canvas_id = ts.canvas_id FROM tag_split ts WHERE ts.tag_id = tags.id AND ts.rn = 1")
    op.execute("""
        INSERT INTO tags (id, user_id, name, color, created_at, canvas_id)
        SELECT ts.new_id, t.user_id, t.name, t.color, t.created_at, ts.canvas_id
        FROM tag_split ts JOIN tags t ON t.id = ts.tag_id
        WHERE ts.rn > 1
    """)
    op.execute("""
        UPDATE snippet_tags SET tag_id = ts.new_id
        FROM tag_split ts, snippets s
        WHERE s.id = snippet_tags.snippet_id AND ts.tag_id = snippet_tags.tag_id
          AND ts.canvas_id = s.canvas_id AND ts.rn > 1
    """)
    op.execute("""
        UPDATE tags SET canvas_id = (
            SELECT c.id FROM canvases c WHERE c.owner_id = tags.user_id ORDER BY c.created_at, c.id::text LIMIT 1
        )
        WHERE canvas_id IS NULL
    """)
    op.execute("DELETE FROM tags WHERE canvas_id IS NULL")
    op.execute("DROP TABLE tag_split")

    op.alter_column('tags', 'canvas_id', nullable=False)
    op.create_foreign_key('tags_canvas_id_fkey', 'tags', 'canvases', ['canvas_id'], ['id'])
    op.create_unique_constraint('uq_tag_canvas_name', 'tags', ['canvas_id', 'name'])
    op.drop_constraint('fk_tags_user_id', 'tags', type_='foreignkey')
    op.drop_column('tags', 'user_id')
    op.drop_column('tags', 'created_at')
