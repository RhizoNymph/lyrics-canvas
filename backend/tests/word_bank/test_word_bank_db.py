"""word_bank service and tag counts/cascades against a real, throwaway PostgreSQL database.

See tests/tags/db.py for how to point this at a throwaway database.
"""

from __future__ import annotations

import uuid

import pytest
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.models.user import User
from app.models.word_bank import bank_word_tags
from app.services import tag_service
from app.services.tag_errors import TagNotFoundError
from app.services.tag_expr import parse
from app.services.tag_values import tag_color, tag_name
from app.services.word_bank import service
from app.services.word_bank.errors import WordExistsError, WordNotFoundError
from app.services.word_bank.service import TagOp
from app.services.word_bank.text import word_text, word_texts
from tests.tags.db import DB_URL, alembic, requires_db

pytestmark = requires_db


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


async def make_user(db: AsyncSession) -> uuid.UUID:
    user = User(email=f"{uuid.uuid4()}@example.test", display_name="t")
    db.add(user)
    await db.commit()
    return user.id


async def new_tag(db, owner, name):
    return (await tag_service.create_tag(db, owner, tag_name(name), tag_color("#6366f1"))).id


async def add(db, owner, *texts, tags=()):
    return await service.add_words(db, owner, word_texts(list(texts)), list(tags))


def texts(words):
    return [w.text for w in words]


def tag_names(word):
    return sorted(t.name for t in word.tags)


# ---- add ----


async def test_add_creates_in_input_order(db):
    owner = await make_user(db)
    result = await add(db, owner, "night", "Broken Heart", "day")
    assert texts(result.created) == ["night", "Broken Heart", "day"]
    assert result.existing == []
    assert all(w.user_id == owner and w.created_at is not None for w in result.created)


async def test_add_collapses_batch_duplicates_first_spelling_wins(db):
    owner = await make_user(db)
    result = await add(db, owner, "Night", "day", "NIGHT", "night", "Day")
    assert texts(result.created) == ["Night", "day"]
    assert result.existing == []
    assert texts(await service.list_words(db, owner)) and len(await service.list_words(db, owner)) == 2


async def test_add_existing_words_keep_their_stored_spelling(db):
    owner = await make_user(db)
    await add(db, owner, "Night")
    result = await add(db, owner, "new", "NIGHT", "other")
    assert texts(result.created) == ["new", "other"]
    assert texts(result.existing) == ["Night"]


async def test_add_unicode_case_folding_matches_the_index(db):
    owner = await make_user(db)
    await add(db, owner, "Über")
    result = await add(db, owner, "über", "ÜBER")
    assert result.created == []
    assert texts(result.existing) == ["Über"]


async def test_add_links_tags_to_created_and_existing(db):
    owner = await make_user(db)
    love, dark = await new_tag(db, owner, "love"), await new_tag(db, owner, "dark")
    await add(db, owner, "night", tags=[love])
    result = await add(db, owner, "day", "night", tags=[love, dark, love])
    assert [tag_names(w) for w in result.created] == [["dark", "love"]]
    assert [tag_names(w) for w in result.existing] == [["dark", "love"]]
    # idempotent
    again = await add(db, owner, "night", tags=[love])
    assert tag_names(again.existing[0]) == ["dark", "love"]


async def test_add_with_foreign_or_unknown_tag_changes_nothing(db):
    owner, other = await make_user(db), await make_user(db)
    foreign = await new_tag(db, other, "love")
    for tag in (foreign, uuid.uuid4()):
        with pytest.raises(TagNotFoundError):
            await add(db, owner, "night", tags=[tag])
    assert await service.list_words(db, owner) == []


async def test_words_are_per_user(db):
    owner, other = await make_user(db), await make_user(db)
    await add(db, owner, "night")
    result = await add(db, other, "night")
    assert texts(result.created) == ["night"]
    assert texts(await service.list_words(db, owner)) == ["night"]


# ---- list / get ----


async def test_list_is_newest_first(db):
    owner = await make_user(db)
    await add(db, owner, "first")
    await add(db, owner, "second")
    await add(db, owner, "third")
    words = await service.list_words(db, owner)
    assert texts(words) == ["third", "second", "first"]
    assert [w.created_at for w in words] == sorted((w.created_at for w in words), reverse=True)


async def test_get_word_is_owner_scoped(db):
    owner, other = await make_user(db), await make_user(db)
    (word,) = (await add(db, owner, "night")).created
    assert (await service.get_word(db, owner, word.id)).text == "night"
    with pytest.raises(WordNotFoundError):
        await service.get_word(db, other, word.id)
    with pytest.raises(WordNotFoundError):
        await service.get_word(db, owner, uuid.uuid4())


async def test_filtered_words_use_tag_names_and_no_type(db):
    owner = await make_user(db)
    love = await new_tag(db, owner, "Love")
    await add(db, owner, "heart", tags=[love])
    await add(db, owner, "night")
    assert texts(await service.filtered_words(db, owner, parse("love"))) == ["heart"]
    assert texts(await service.filtered_words(db, owner, parse("!love"))) == ["night"]
    assert texts(await service.filtered_words(db, owner, parse("type:verse"))) == []
    assert texts(await service.filtered_words(db, owner, parse("!type:verse"))) == ["night", "heart"]
    assert len(await service.filtered_words(db, owner, None)) == 2


# ---- rename ----


async def test_rename_and_case_only_change(db):
    owner = await make_user(db)
    (word,) = (await add(db, owner, "night")).created
    assert (await service.rename_word(db, owner, word.id, word_text("Night"))).text == "Night"
    renamed = await service.rename_word(db, owner, word.id, word_text("midnight"))
    assert renamed.text == "midnight"
    assert renamed.id == word.id


async def test_rename_collision_ignores_case(db):
    owner = await make_user(db)
    a, b = (await add(db, owner, "night", "day")).created
    with pytest.raises(WordExistsError):
        await service.rename_word(db, owner, b.id, word_text("NIGHT"))
    assert (await service.get_word(db, owner, b.id)).text == "day"


async def test_rename_foreign_word_is_not_found(db):
    owner, other = await make_user(db), await make_user(db)
    (word,) = (await add(db, owner, "night")).created
    with pytest.raises(WordNotFoundError):
        await service.rename_word(db, other, word.id, word_text("day"))


async def test_rename_keeps_tags(db):
    owner = await make_user(db)
    love = await new_tag(db, owner, "love")
    (word,) = (await add(db, owner, "night", tags=[love])).created
    assert tag_names(await service.rename_word(db, owner, word.id, word_text("day"))) == ["love"]


# ---- delete ----


async def test_delete_ignores_foreign_and_unknown_ids(db):
    owner, other = await make_user(db), await make_user(db)
    a, b = (await add(db, owner, "a", "b")).created
    (theirs,) = (await add(db, other, "c")).created
    assert await service.delete_words(db, owner, [a.id, theirs.id, uuid.uuid4(), a.id]) == 1
    assert texts(await service.list_words(db, owner)) == ["b"]
    assert texts(await service.list_words(db, other)) == ["c"]


async def test_delete_removes_links(db):
    owner = await make_user(db)
    love = await new_tag(db, owner, "love")
    (word,) = (await add(db, owner, "night", tags=[love])).created
    await service.delete_words(db, owner, [word.id])
    rows = await db.execute(select(bank_word_tags).where(bank_word_tags.c.tag_id == love))
    assert rows.all() == []


# ---- tag link / unlink ----


async def test_link_and_unlink_are_idempotent(db):
    owner = await make_user(db)
    love = await new_tag(db, owner, "love")
    a, b = (await add(db, owner, "a", "b")).created
    words = await service.set_tag(db, owner, [b.id, a.id, b.id], love, TagOp.link)
    assert texts(words) == ["b", "a"]
    assert [tag_names(w) for w in words] == [["love"], ["love"]]
    words = await service.set_tag(db, owner, [a.id], love, TagOp.link)
    assert tag_names(words[0]) == ["love"]
    words = await service.set_tag(db, owner, [a.id, b.id], love, TagOp.unlink)
    assert [tag_names(w) for w in words] == [[], []]
    words = await service.set_tag(db, owner, [a.id], love, TagOp.unlink)
    assert tag_names(words[0]) == []


async def test_set_tag_with_a_foreign_word_changes_nothing(db):
    owner, other = await make_user(db), await make_user(db)
    love = await new_tag(db, owner, "love")
    (mine,) = (await add(db, owner, "a")).created
    (theirs,) = (await add(db, other, "b")).created
    for bad in (theirs.id, uuid.uuid4()):
        with pytest.raises(WordNotFoundError):
            await service.set_tag(db, owner, [mine.id, bad], love, TagOp.link)
    assert tag_names(await service.get_word(db, owner, mine.id)) == []


async def test_set_tag_with_a_foreign_tag_is_tag_not_found(db):
    owner, other = await make_user(db), await make_user(db)
    foreign = await new_tag(db, other, "love")
    (mine,) = (await add(db, owner, "a")).created
    with pytest.raises(TagNotFoundError):
        await service.set_tag(db, owner, [mine.id], foreign, TagOp.link)


async def test_word_tags_sorted_by_name_in_responses_is_the_api_job(db):
    owner = await make_user(db)
    tags = [await new_tag(db, owner, n) for n in ("b", "A", "c")]
    (word,) = (await add(db, owner, "x", tags=tags)).created
    assert tag_names(word) == ["A", "b", "c"]


# ---- tags: word_count and cascade ----


async def test_tag_word_count_and_snippet_count_are_independent(db):
    owner = await make_user(db)
    love = await new_tag(db, owner, "love")
    await new_tag(db, owner, "unused")
    await add(db, owner, "a", "b", "c", tags=[love])
    summaries = {t.name: t for t in await tag_service.list_tags(db, owner)}
    assert (summaries["love"].word_count, summaries["love"].snippet_count) == (3, 0)
    assert summaries["unused"].word_count == 0
    updated = await tag_service.update_tag(db, love, owner, None, tag_color("#000000"))
    assert updated.word_count == 3


async def test_tag_delete_removes_word_links_but_keeps_words(db):
    owner = await make_user(db)
    love = await new_tag(db, owner, "love")
    (word,) = (await add(db, owner, "night", tags=[love])).created
    word_id = word.id
    await tag_service.delete_tag(db, love, owner)
    db.expire_all()
    assert tag_names(await service.get_word(db, owner, word_id)) == []
    rows = await db.execute(select(bank_word_tags))
    assert all(r.tag_id != love for r in rows.all())


# ---- lookups ----


async def test_ids_by_lower_text(db):
    owner, other = await make_user(db), await make_user(db)
    night, day = (await add(db, owner, "Night", "day")).created
    await add(db, other, "moon")
    found = await service.ids_by_lower_text(db, owner, ["night", "DAY", "moon", "none"])
    assert found == {"night": night.id, "day": day.id}
    assert await service.ids_by_lower_text(db, owner, []) == {}
