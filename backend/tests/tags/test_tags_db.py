"""tag_service and tag_preset_service against a real, throwaway PostgreSQL database.

See tests/tags/db.py for how to point this at a throwaway database.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.models.canvas import Canvas
from app.models.snippet import Snippet, SnippetType
from app.models.tag import snippet_tags
from app.models.user import User
from app.services import canvas_service, merge_service, snippet_service, tag_preset_service, tag_service
from app.services.merge_service import MergeSource
from app.services.rhyme_suggest_service import DbSnippetRepository
from app.services.tag_errors import (
    PresetExpressionTooLongError,
    PresetNameTakenError,
    PresetNotFoundError,
    SnippetNotFoundError,
    TagNameTakenError,
    TagNotFoundError,
)
from app.services.tag_values import preset_expression, preset_name, tag_color, tag_name
from tests.tags.db import DB_URL, alembic, requires_db

pytestmark = requires_db

DEFAULT = tag_color("#6366f1")


@pytest.fixture(scope="module", autouse=True)
def migrated():
    alembic("downgrade", "base")
    alembic("upgrade", "head")
    yield
    alembic("downgrade", "base")


@pytest.fixture
async def db():
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)()
    yield session
    await session.close()
    await engine.dispose()


async def make_user(db: AsyncSession) -> tuple[uuid.UUID, uuid.UUID, uuid.UUID]:
    """A user with two canvases."""
    user = User(email=f"{uuid.uuid4()}@example.test", display_name="t")
    db.add(user)
    await db.flush()
    first, second = Canvas(owner_id=user.id, title="one"), Canvas(owner_id=user.id, title="two")
    db.add_all([first, second])
    await db.commit()
    return user.id, first.id, second.id


async def add_snippet(db: AsyncSession, canvas_id: uuid.UUID, text: str = "la la", snippet_type: SnippetType = SnippetType.verse) -> uuid.UUID:
    snippet = Snippet(canvas_id=canvas_id, raw_text=text, snippet_type=snippet_type)
    db.add(snippet)
    await db.commit()
    return snippet.id


async def new_tag(db, owner, name, color=DEFAULT):
    return await tag_service.create_tag(db, owner, tag_name(name), color)


async def new_preset(db, owner, name, expression):
    return await tag_preset_service.create_preset(db, owner, preset_name(name), preset_expression(expression))


# ---- tags ----


async def test_tags_are_user_wide_sorted_and_counted(db):
    owner, c1, c2 = await make_user(db)
    night = await new_tag(db, owner, "night")
    love = await new_tag(db, owner, "Love", tag_color("#ff0000"))
    s1, s2 = await add_snippet(db, c1), await add_snippet(db, c2)
    await tag_service.add_tag_to_snippet(db, s1, love.id, owner)
    await tag_service.add_tag_to_snippet(db, s2, love.id, owner)  # same tag on another canvas
    await tag_service.add_tag_to_snippet(db, s2, love.id, owner)  # linking twice is a no-op

    tags = await tag_service.list_tags(db, owner)
    assert [(t.name, t.color, t.snippet_count) for t in tags] == [("Love", "#ff0000", 2), ("night", DEFAULT, 0)]
    assert all(t.created_at is not None for t in tags)
    assert night.snippet_count == 0

    stranger, _, _ = await make_user(db)
    assert await tag_service.list_tags(db, stranger) == []


async def test_names_are_unique_per_user_ignoring_case(db):
    owner, _, _ = await make_user(db)
    await new_tag(db, owner, "Love")
    with pytest.raises(TagNameTakenError):
        await new_tag(db, owner, "LOVE")
    other, _, _ = await make_user(db)
    assert (await new_tag(db, other, "love")).name == "love"


async def test_update_color_and_case_only_rename(db):
    owner, _, _ = await make_user(db)
    tag = await new_tag(db, owner, "love")
    updated = await tag_service.update_tag(db, tag.id, owner, tag_name("Love"), tag_color("#00ff00"))
    assert (updated.name, updated.color) == ("Love", "#00ff00")
    kept = await tag_service.update_tag(db, tag.id, owner, None, None)
    assert (kept.name, kept.color) == ("Love", "#00ff00")


async def test_rename_conflict_and_foreign_tag(db):
    owner, _, _ = await make_user(db)
    love = await new_tag(db, owner, "love")
    await new_tag(db, owner, "night")
    with pytest.raises(TagNameTakenError):
        await tag_service.update_tag(db, love.id, owner, tag_name("NIGHT"), None)
    stranger, _, _ = await make_user(db)
    with pytest.raises(TagNotFoundError):
        await tag_service.update_tag(db, love.id, stranger, tag_name("mine"), None)
    with pytest.raises(TagNotFoundError):
        await tag_service.delete_tag(db, love.id, stranger)


async def test_rename_rewrites_the_users_presets(db):
    owner, _, _ = await make_user(db)
    love = await new_tag(db, owner, "love")
    await new_preset(db, owner, "a", "Love & night")
    await new_preset(db, owner, "b", "type:verse | !LOVE")
    await new_preset(db, owner, "c", "lovely")
    stranger, _, _ = await make_user(db)
    await new_tag(db, stranger, "love")
    await new_preset(db, stranger, "theirs", "love")

    await tag_service.update_tag(db, love.id, owner, tag_name("late night"), None)

    mine = {p.name: p.expression for p in await tag_preset_service.list_presets(db, owner)}
    assert mine == {"a": '"late night" & night', "b": 'type:verse | !"late night"', "c": "lovely"}
    theirs = [p.expression for p in await tag_preset_service.list_presets(db, stranger)]
    assert theirs == ["love"]


async def test_rename_that_would_overflow_a_preset_changes_nothing(db):
    owner, _, _ = await make_user(db)
    tag = await new_tag(db, owner, "a")
    text = " | ".join(["a"] * 120)  # 477 characters
    await new_preset(db, owner, "long", text)
    with pytest.raises(PresetExpressionTooLongError):
        await tag_service.update_tag(db, tag.id, owner, tag_name("abc"), tag_color("#000000"))
    tags = await tag_service.list_tags(db, owner)
    assert [(t.name, t.color) for t in tags] == [("a", DEFAULT)]
    assert [p.expression for p in await tag_preset_service.list_presets(db, owner)] == [text]


async def test_delete_keeps_presets_and_links_go(db):
    owner, c1, _ = await make_user(db)
    tag = await new_tag(db, owner, "love")
    snippet = await add_snippet(db, c1)
    await tag_service.add_tag_to_snippet(db, snippet, tag.id, owner)
    await new_preset(db, owner, "p", "love")
    await tag_service.delete_tag(db, tag.id, owner)
    assert await tag_service.list_tags(db, owner) == []
    assert [p.expression for p in await tag_preset_service.list_presets(db, owner)] == ["love"]
    rows = await db.execute(select(snippet_tags).where(snippet_tags.c.snippet_id == snippet))
    assert rows.all() == []
    with pytest.raises(TagNotFoundError):
        await tag_service.delete_tag(db, tag.id, owner)


async def test_link_requires_owning_both(db):
    owner, c1, _ = await make_user(db)
    stranger, sc, _ = await make_user(db)
    mine = await new_tag(db, owner, "mine")
    theirs = await new_tag(db, stranger, "theirs")
    my_snippet, their_snippet = await add_snippet(db, c1), await add_snippet(db, sc)
    with pytest.raises(TagNotFoundError):
        await tag_service.add_tag_to_snippet(db, my_snippet, theirs.id, owner)
    with pytest.raises(SnippetNotFoundError):
        await tag_service.add_tag_to_snippet(db, their_snippet, mine.id, owner)
    await tag_service.add_tag_to_snippet(db, my_snippet, mine.id, owner)
    with pytest.raises(SnippetNotFoundError):
        await tag_service.remove_tag_from_snippet(db, my_snippet, mine.id, stranger)
    await tag_service.remove_tag_from_snippet(db, my_snippet, mine.id, owner)
    await tag_service.remove_tag_from_snippet(db, my_snippet, mine.id, owner)  # idempotent
    assert (await tag_service.list_tags(db, owner))[0].snippet_count == 0


async def test_canvas_detail_snippets_embed_user_wide_tags(db):
    owner, c1, c2 = await make_user(db)
    tag = await new_tag(db, owner, "love")
    s1, s2 = await add_snippet(db, c1), await add_snippet(db, c2)
    for s in (s1, s2):
        await tag_service.add_tag_to_snippet(db, s, tag.id, owner)
    for canvas_id in (c1, c2):
        canvas = await canvas_service.get_canvas(db, str(canvas_id), str(owner))
        assert [[t.name for t in s.tags] for s in canvas.snippets] == [["love"]]
    # deleting a canvas leaves the user's tags alone
    assert await canvas_service.delete_canvas(db, str(c1), str(owner))
    assert [t.snippet_count for t in await tag_service.list_tags(db, owner)] == [1]


async def test_merge_unions_shared_user_wide_tags(db):
    owner, c1, _ = await make_user(db)
    love, night = await new_tag(db, owner, "love"), await new_tag(db, owner, "night")
    a, b = await add_snippet(db, c1, "a"), await add_snippet(db, c1, "b")
    await tag_service.add_tag_to_snippet(db, a, love.id, owner)
    await tag_service.add_tag_to_snippet(db, b, love.id, owner)
    await tag_service.add_tag_to_snippet(db, b, night.id, owner)
    originals = await snippet_service.get_canvas_snippets(db, c1, str(owner), [a, b])
    sources = [
        MergeSource(
            id=str(s.id), raw_text=s.raw_text, grid=None, snippet_type=s.snippet_type, grid_mode=s.grid_mode,
            beats_per_line=None, group_id=None, position_x=0.0, position_y=float(i), tag_ids=tuple(str(t.id) for t in s.tags),
        )
        for i, s in enumerate(sorted(originals, key=lambda s: s.raw_text))
    ]
    assert merge_service.union_tag_ids(sources) == (str(love.id), str(night.id))
    tags_by_id = {str(t.id): t for s in originals for t in s.tags}
    merged = await snippet_service.replace_with_merged(
        db, c1, {"raw_text": "a\nb"}, [tags_by_id[t] for t in merge_service.union_tag_ids(sources)], originals
    )
    assert sorted(t.name for t in merged.tags) == ["love", "night"]
    assert [(t.name, t.snippet_count) for t in await tag_service.list_tags(db, owner)] == [("love", 1), ("night", 1)]


async def test_rhyme_repository_records_carry_type_and_tag_names(db):
    owner, c1, _ = await make_user(db)
    tag = await new_tag(db, owner, "Love")
    snippet = await add_snippet(db, c1, "the cat", SnippetType.hook)
    await tag_service.add_tag_to_snippet(db, snippet, tag.id, owner)
    records = await DbSnippetRepository(db).user_snippets(str(owner))
    assert [(r.snippet_type, r.tag_names, r.tag_ids) for r in records] == [(SnippetType.hook, ("Love",), (str(tag.id),))]


# ---- presets ----


async def test_presets_sorted_unique_and_owner_scoped(db):
    owner, _, _ = await make_user(db)
    b = await new_preset(db, owner, "beta", "x")
    await new_preset(db, owner, "Alpha", "type:hook")
    assert [p.name for p in await tag_preset_service.list_presets(db, owner)] == ["Alpha", "beta"]
    assert b.created_at is not None and b.updated_at is not None
    with pytest.raises(PresetNameTakenError):
        await new_preset(db, owner, "BETA", "y")
    stranger, _, _ = await make_user(db)
    with pytest.raises(PresetNotFoundError):
        await tag_preset_service.update_preset(db, b.id, stranger, None, preset_expression("y"))
    with pytest.raises(PresetNotFoundError):
        await tag_preset_service.delete_preset(db, b.id, stranger)


async def test_update_and_delete_preset(db):
    owner, _, _ = await make_user(db)
    p = await new_preset(db, owner, "p", "a")
    await new_preset(db, owner, "q", "b")
    before = p.updated_at
    updated = await tag_preset_service.update_preset(db, p.id, owner, preset_name("P"), preset_expression("a b"))
    assert (updated.name, updated.expression) == ("P", "a & b")
    assert updated.updated_at >= before
    with pytest.raises(PresetNameTakenError):
        await tag_preset_service.update_preset(db, p.id, owner, preset_name("Q"), None)
    await tag_preset_service.delete_preset(db, p.id, owner)
    assert [x.name for x in await tag_preset_service.list_presets(db, owner)] == ["q"]
