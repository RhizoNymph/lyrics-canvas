"""Word bank DB operations, every one scoped to ``owner_id`` (docs/features/word_bank.md#http-api).

A word or tag of another user is reported as not found. Case-insensitive matching uses the
database's ``lower()`` (the same function as the ``uq_bank_words_user_lower_text`` index), so
batch de-duplication, "already in the bank" and rename collisions agree with the constraint.
Failures are the typed errors of app/services/word_bank/errors.py and app/services/tag_errors.py.

Content versions (docs/features/sync_versions.md#versions): a text change and every tag link or
unlink that changes a word's tags bump that word once; a new word starts at 1.
"""

from __future__ import annotations

import logging
import uuid
from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum

from sqlalchemy import String, delete, func, literal, select
from sqlalchemy.dialects.postgresql import ARRAY, insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.tag import Tag
from app.models.word_bank import BankWord, bank_word_tags
from app.services.sync.bump import bump, bump_words
from app.services.sync.errors import IdConflictError
from app.services.tag_errors import TagNotFoundError
from app.services.tag_expr import Expr, SnippetFacts, matches
from app.services.word_bank.errors import WordExistsError, WordNotFoundError
from app.services.word_bank.text import WordText

logger = logging.getLogger(__name__)


class TagOp(StrEnum):
    link = "link"
    unlink = "unlink"


@dataclass(frozen=True, slots=True)
class NewWord:
    """A word to add, with an optional client-chosen id (docs/features/sync_versions.md#client-ids)."""

    text: WordText
    id: uuid.UUID | None = None


@dataclass(frozen=True, slots=True)
class AddResult:
    created: list[BankWord]
    existing: list[BankWord]


def _with_tags():
    return select(BankWord).options(selectinload(BankWord.tags))


def word_matches(word: BankWord, tag_filter: Expr | None) -> bool:
    """A word is a typeless subject: tag names only, every ``type:`` term is false."""
    return matches(tag_filter, SnippetFacts.of(None, (t.name for t in word.tags)))


async def list_words(db: AsyncSession, owner_id: uuid.UUID) -> list[BankWord]:
    """The whole bank, newest first (created_at desc, id)."""
    result = await db.execute(
        _with_tags().where(BankWord.user_id == owner_id).order_by(BankWord.created_at.desc(), BankWord.id)
    )
    return list(result.scalars().all())


async def filtered_words(db: AsyncSession, owner_id: uuid.UUID, tag_filter: Expr | None) -> list[BankWord]:
    return [w for w in await list_words(db, owner_id) if word_matches(w, tag_filter)]


async def get_word(db: AsyncSession, owner_id: uuid.UUID, word_id: uuid.UUID) -> BankWord:
    result = await db.execute(_with_tags().where(BankWord.id == word_id, BankWord.user_id == owner_id))
    word = result.scalar_one_or_none()
    if word is None:
        raise WordNotFoundError(f"word {word_id} not found")
    return word


async def _owned_tags(db: AsyncSession, owner_id: uuid.UUID, tag_ids: Sequence[uuid.UUID]) -> list[uuid.UUID]:
    """The distinct ids, in order; any that is not the user's raises TagNotFoundError."""
    wanted = list(dict.fromkeys(tag_ids))
    if not wanted:
        return []
    result = await db.execute(select(Tag.id).where(Tag.id.in_(wanted), Tag.user_id == owner_id))
    found = set(result.scalars().all())
    missing = [t for t in wanted if t not in found]
    if missing:
        raise TagNotFoundError(f"tag {missing[0]} not found")
    return wanted


async def _lowered(db: AsyncSession, texts: Sequence[str]) -> list[str]:
    """``lower(text)`` as the database computes it, in order."""
    if not texts:
        return []
    values = func.unnest(literal(list(texts), ARRAY(String))).table_valued("x", with_ordinality="ord").render_derived()
    rows = select(func.lower(values.c.x)).order_by(values.c.ord)
    return list((await db.execute(rows)).scalars().all())


async def _link(db: AsyncSession, word_ids: Sequence[uuid.UUID], tag_ids: Sequence[uuid.UUID]) -> set[uuid.UUID]:
    """Link every pair (existing links are kept); returns the words that gained a link."""
    pairs = [{"bank_word_id": w, "tag_id": t} for w in word_ids for t in tag_ids]
    if not pairs:
        return set()
    result = await db.execute(
        insert(bank_word_tags).values(pairs).on_conflict_do_nothing().returning(bank_word_tags.c.bank_word_id)
    )
    return set(result.scalars().all())


async def words_by_ids(db: AsyncSession, ids: Sequence[uuid.UUID]) -> list[BankWord]:
    """The words (tags loaded fresh), in the order of ``ids``; missing ids are skipped."""
    return await _reload(db, ids)


async def _reload(db: AsyncSession, ids: Sequence[uuid.UUID]) -> list[BankWord]:
    """The words (tags loaded fresh), in the order of ``ids``."""
    if not ids:
        return []
    result = await db.execute(
        _with_tags().where(BankWord.id.in_(list(ids))).execution_options(populate_existing=True)
    )
    by_id = {w.id: w for w in result.scalars().all()}
    return [by_id[i] for i in ids if i in by_id]


async def _retried(db: AsyncSession, owner_id: uuid.UUID, batch: dict[str, NewWord]) -> set[uuid.UUID]:
    """The client ids of the batch whose word already exists from an identical earlier add (same
    owner, same text). An id naming another user's word, or a word with another text, raises
    IdConflictError before anything is written."""
    wanted = {w.id: w for w in batch.values() if w.id is not None}
    if not wanted:
        return set()
    rows = await db.execute(select(BankWord.id, BankWord.user_id, BankWord.text).where(BankWord.id.in_(list(wanted))))
    retried: set[uuid.UUID] = set()
    for word_id, user_id, text in rows.all():
        if user_id != owner_id or text != wanted[word_id].text:
            raise IdConflictError(f"word id {word_id} is already used by another word")
        retried.add(word_id)
    return retried


async def _insert(db: AsyncSession, owner_id: uuid.UUID, words: Sequence[NewWord]) -> set[uuid.UUID]:
    """Insert the words; a text already in the bank is skipped. Returns the inserted ids."""
    if not words:
        return set()
    inserted = await db.execute(
        insert(BankWord)
        .values([{"id": w.id or uuid.uuid4(), "user_id": owner_id, "text": w.text} for w in words])
        .on_conflict_do_nothing()
        .returning(BankWord.id)
    )
    return set(inserted.scalars().all())


def _batch(keys: Sequence[str], words: Sequence[NewWord]) -> dict[str, NewWord]:
    """The batch de-duplicated ignoring case (first spelling and id win). One client id given for
    two different words raises IdConflictError."""
    batch: dict[str, NewWord] = {}
    seen_ids: dict[uuid.UUID, str] = {}
    for key, word in zip(keys, words):
        if word.id is not None:
            if seen_ids.setdefault(word.id, key) != key:
                raise IdConflictError(f"word id {word.id} is given for two different words")
        batch.setdefault(key, word)
    return batch


async def add_words(
    db: AsyncSession, owner_id: uuid.UUID, texts: Sequence[WordText | NewWord], tag_ids: Sequence[uuid.UUID]
) -> AddResult:
    """Add a validated batch. Duplicates inside it collapse ignoring case (the first spelling wins);
    a word already in the bank goes to ``existing`` with its stored spelling. ``tag_ids`` are linked
    to every created and existing word; an existing word that gains a link is bumped. Both lists
    keep the input order.

    A ``NewWord`` with an ``id`` is created with that id; when that word already exists from an
    identical earlier add it is returned in ``created`` again (a safe retry). A text already in the
    bank under another id still goes to ``existing``."""
    words = [w if isinstance(w, NewWord) else NewWord(w) for w in texts]
    tags = await _owned_tags(db, owner_id, tag_ids)
    keys = await _lowered(db, [w.text for w in words])
    batch = _batch(keys, words)
    retried = await _retried(db, owner_id, batch)

    new_ids = await _insert(db, owner_id, [w for w in batch.values() if w.id not in retried])
    created_ids = new_ids | retried
    rows = await db.execute(
        select(BankWord.id, func.lower(BankWord.text)).where(
            BankWord.user_id == owner_id, func.lower(BankWord.text).in_(list(batch))
        )
    )
    id_by_key = {key: word_id for word_id, key in rows.all()}
    ordered = [id_by_key[k] for k in batch if k in id_by_key]
    await bump_words(db, await _link(db, ordered, tags) - new_ids)
    await db.commit()

    stored = await _reload(db, ordered)
    created = [w for w in stored if w.id in created_ids]
    existing = [w for w in stored if w.id not in created_ids]
    logger.info(
        "word_bank.added user_id=%s submitted=%d created=%d existing=%d tags=%d",
        owner_id, len(texts), len(created), len(existing), len(tags),
    )
    return AddResult(created=created, existing=existing)


async def rename_word(db: AsyncSession, owner_id: uuid.UUID, word_id: uuid.UUID, text: WordText) -> BankWord:
    """Change the text; a change of case only is allowed. Another word with the same text
    (ignoring case) raises WordExistsError."""
    word = await get_word(db, owner_id, word_id)
    clash = await db.execute(
        select(BankWord.id).where(
            BankWord.user_id == owner_id, BankWord.id != word_id, func.lower(BankWord.text) == func.lower(text)
        )
    )
    if clash.first() is not None:
        raise WordExistsError(f"{text!r} is already in the word bank")
    if word.text != text:
        word.text = text
        bump(word)
    try:
        await db.commit()
    except IntegrityError as exc:
        await db.rollback()
        raise WordExistsError(f"{text!r} is already in the word bank") from exc
    logger.info("word_bank.renamed user_id=%s word_id=%s", owner_id, word_id)
    return (await _reload(db, [word_id]))[0]


async def delete_words(db: AsyncSession, owner_id: uuid.UUID, word_ids: Sequence[uuid.UUID]) -> int:
    """Delete the user's words among ``word_ids`` (others are ignored); returns how many went."""
    if not word_ids:
        return 0
    result = await db.execute(
        delete(BankWord).where(BankWord.id.in_(list(set(word_ids))), BankWord.user_id == owner_id)
    )
    await db.commit()
    deleted = result.rowcount or 0
    logger.info("word_bank.deleted user_id=%s requested=%d deleted=%d", owner_id, len(word_ids), deleted)
    return deleted


async def set_tag(
    db: AsyncSession, owner_id: uuid.UUID, word_ids: Sequence[uuid.UUID], tag_id: uuid.UUID, op: TagOp
) -> list[BankWord]:
    """Link or unlink one tag on many words (idempotent). Any word that is not the user's raises
    WordNotFoundError and nothing changes. Returns the words in request order (duplicates once)."""
    await _owned_tags(db, owner_id, [tag_id])
    ids = list(dict.fromkeys(word_ids))
    result = await db.execute(select(BankWord.id).where(BankWord.id.in_(ids), BankWord.user_id == owner_id))
    found = set(result.scalars().all())
    missing = [w for w in ids if w not in found]
    if missing:
        raise WordNotFoundError(f"word {missing[0]} not found")
    match op:
        case TagOp.link:
            changed = await _link(db, ids, [tag_id])
        case TagOp.unlink:
            result = await db.execute(
                bank_word_tags.delete()
                .where(bank_word_tags.c.bank_word_id.in_(ids), bank_word_tags.c.tag_id == tag_id)
                .returning(bank_word_tags.c.bank_word_id)
            )
            changed = set(result.scalars().all())
    await bump_words(db, changed)
    await db.commit()
    logger.info("word_bank.tagged user_id=%s op=%s tag_id=%s words=%d", owner_id, op.value, tag_id, len(ids))
    return await _reload(db, ids)


async def ids_by_lower_text(db: AsyncSession, owner_id: uuid.UUID, texts: Sequence[str]) -> dict[str, uuid.UUID]:
    """``{lower(text): id}`` of the user's words whose text is one of ``texts`` ignoring case."""
    if not texts:
        return {}
    rows = await db.execute(
        select(func.lower(BankWord.text), BankWord.id).where(
            BankWord.user_id == owner_id, func.lower(BankWord.text).in_(await _lowered(db, texts))
        )
    )
    return {key: word_id for key, word_id in rows.all()}
