"""Word bank routes (docs/features/word_bank.md#http-api). All data is scoped to the current user."""

from __future__ import annotations

import asyncio
import logging
import time
import uuid
from collections.abc import Sequence

from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.api.tag_errors import expression_http_error, owner_id
from app.api.word_bank_errors import word_bank_errors, word_bank_http_error
from app.config import settings
from app.db.database import get_db
from app.models.user import User
from app.models.word_bank import BankWord
from app.schemas.tag import SnippetTagResponse
from app.schemas.word_bank import (
    MAX_ADD_TAGS,
    MAX_ADD_WORDS,
    MAX_IDS,
    BankFamiliesRequest,
    BankFamiliesResponse,
    BankFamily,
    BankRhymeAnchor,
    BankRhymeMatch,
    BankRhymesRequest,
    BankRhymesResponse,
    BankWordResponse,
    BankWordsCreate,
    BankWordsCreateResponse,
    BankWordsDelete,
    BankWordsResponse,
    BankWordTagsUpdate,
    BankWordUpdate,
    RelatedWordResult,
    RelatedWordsResponse,
)
from app.services.phonetics import DEFAULT_DIALECT_MAP, PronunciationError, unit_from_text
from app.services.tag_errors import TagNotFoundError, TagServiceError
from app.services.tag_expr import Expr, TagExpressionError, parse
from app.services.word_bank import service
from app.services.word_bank.errors import TooManyWordsError, WordNotFoundError
from app.services.word_bank.families import FamiliesResult, rhyme_families
from app.services.word_bank.phonetic import PairScorer, PhoneticWord, phonetic_words
from app.services.word_bank.related import RelatedWords, default_builder
from app.services.word_bank.rhymes import RhymeMatch, rank_rhymes
from app.services.word_bank.service import TagOp
from app.services.word_bank.text import related_query, word_text, word_texts

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/word-bank", tags=["word-bank"])

# the process-wide related-word index (built once, off the event loop; see related.py)
related_words = RelatedWords(default_builder(settings.related_words_cache_dir))


def get_related_words() -> RelatedWords:
    return related_words


# ---- helpers ----


def _uuid(value: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


def _word_ids(values: Sequence[str]) -> list[uuid.UUID]:
    """Parsed ids; one that is not a UUID cannot be the user's word."""
    ids: list[uuid.UUID] = []
    for value in values:
        parsed = _uuid(value)
        if parsed is None:
            raise WordNotFoundError(f"word {value} not found")
        ids.append(parsed)
    return ids


def _tag_ids(values: Sequence[str]) -> list[uuid.UUID]:
    ids: list[uuid.UUID] = []
    for value in values:
        parsed = _uuid(value)
        if parsed is None:
            raise TagNotFoundError(f"tag {value} not found")
        ids.append(parsed)
    return ids


def _at_most(count: int, limit: int, what: str) -> None:
    if count > limit:
        raise TooManyWordsError(f"at most {limit} {what} per request, got {count}")


def _tag_filter(text: str | None) -> Expr | None:
    try:
        return parse(text) if text else None
    except TagExpressionError as exc:
        raise expression_http_error(exc) from exc


def word_response(word: BankWord) -> BankWordResponse:
    tags = sorted(word.tags, key=lambda t: (t.name.lower(), str(t.id)))
    return BankWordResponse(
        id=str(word.id),
        text=word.text,
        tags=[SnippetTagResponse(id=str(t.id), name=t.name, color=t.color) for t in tags],
        created_at=word.created_at,
    )


def _plain(words: Sequence[BankWord]) -> list[tuple[str, str]]:
    return [(str(w.id), w.text) for w in words]


# ---- CRUD ----


@router.get("", response_model=list[BankWordResponse])
async def list_words(user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """The whole bank, newest first. Clients filter locally with the shared tag expression evaluator."""
    return [word_response(w) for w in await service.list_words(db, owner_id(user))]


@router.post("", response_model=BankWordsCreateResponse, status_code=201, responses=word_bank_errors(404, 422))
async def add_words(req: BankWordsCreate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Add a batch. One invalid word rejects it (``invalid_word`` + ``index``); words already in the
    bank (ignoring case) come back in ``existing``; ``tag_ids`` are linked to all of them."""
    try:
        _at_most(len(req.words), MAX_ADD_WORDS, "words")
        _at_most(len(req.tag_ids), MAX_ADD_TAGS, "tags")
        texts = word_texts(req.words)
        result = await service.add_words(db, owner_id(user), texts, _tag_ids(req.tag_ids))
    except TagServiceError as exc:
        raise word_bank_http_error(exc) from exc
    return BankWordsCreateResponse(
        created=[word_response(w) for w in result.created], existing=[word_response(w) for w in result.existing]
    )


@router.put("/{word_id}", response_model=BankWordResponse, responses=word_bank_errors(404, 409, 422))
async def rename_word(
    word_id: uuid.UUID, req: BankWordUpdate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)
):
    try:
        word = await service.rename_word(db, owner_id(user), word_id, word_text(req.text))
    except TagServiceError as exc:
        raise word_bank_http_error(exc) from exc
    return word_response(word)


@router.post("/delete", status_code=204, responses=word_bank_errors(422))
async def delete_words(req: BankWordsDelete, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Ids that are not the user's (or not ids at all) are ignored."""
    try:
        _at_most(len(req.ids), MAX_IDS, "ids")
    except TagServiceError as exc:
        raise word_bank_http_error(exc) from exc
    ids = [i for i in (_uuid(v) for v in req.ids) if i is not None]
    await service.delete_words(db, owner_id(user), ids)


@router.post("/tags", response_model=BankWordsResponse, responses=word_bank_errors(404, 422))
async def set_tag(req: BankWordTagsUpdate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Link or unlink one tag on many words; idempotent. Any word that is not the user's is a 404
    and nothing changes."""
    try:
        _at_most(len(req.word_ids), MAX_IDS, "ids")
        (tag_id,) = _tag_ids([req.tag_id])
        words = await service.set_tag(db, owner_id(user), _word_ids(req.word_ids), tag_id, TagOp(req.op))
    except TagServiceError as exc:
        raise word_bank_http_error(exc) from exc
    return BankWordsResponse(words=[word_response(w) for w in words])


# ---- rhymes ----


def _matches(
    anchor_text: str, anchor_id: str | None, candidates: list[tuple[str, str]], req: BankRhymesRequest
) -> tuple[BankRhymeAnchor, list[RhymeMatch]]:
    """CPU work for /rhymes (runs in a worker thread)."""
    try:
        unit = unit_from_text(anchor_text, DEFAULT_DIALECT_MAP)
    except PronunciationError:
        return BankRhymeAnchor(text=anchor_text, rhyme_key=[], syllable_count=0), []
    anchor = BankRhymeAnchor(text=anchor_text, rhyme_key=list(unit.rhyme_key), syllable_count=unit.syllable_count)
    found = rank_rhymes(
        unit,
        phonetic_words(candidates),
        PairScorer(req.k),
        min_score=req.min_score,
        limit=req.limit,
        exclude_id=anchor_id,
        exclude_text=anchor_text if anchor_id is None else None,
    )
    return anchor, found


@router.post("/rhymes", response_model=BankRhymesResponse, responses=word_bank_errors(404, 422))
async def rhymes(req: BankRhymesRequest, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Bank words (matching ``tag_filter``) ranked against one word of the bank or a typed text."""
    started = time.perf_counter()
    tag_filter = _tag_filter(req.tag_filter)
    owner = owner_id(user)
    try:
        if req.word_id is not None:
            (word_id,) = _word_ids([req.word_id])
            anchor_word = await service.get_word(db, owner, word_id)
            anchor_text, anchor_id = anchor_word.text, str(anchor_word.id)
        else:
            anchor_text, anchor_id = word_text(req.text or ""), None
    except TagServiceError as exc:
        raise word_bank_http_error(exc) from exc
    words = await service.filtered_words(db, owner, tag_filter)
    by_id = {str(w.id): w for w in words}
    anchor, found = await asyncio.to_thread(_matches, anchor_text, anchor_id, _plain(words), req)
    logger.info(
        "word_bank.rhymes user_id=%s candidates=%d matches=%d k=%d elapsed_ms=%.1f",
        owner, len(words), len(found), req.k, (time.perf_counter() - started) * 1000,
    )
    return BankRhymesResponse(
        anchor=anchor,
        matches=[
            BankRhymeMatch(word=word_response(by_id[m.word.id]), score=m.score, match_depth=m.match_depth) for m in found
        ],
    )


# ---- families ----


def _families(words: list[tuple[str, str]], min_score: float, k: int) -> FamiliesResult:
    """CPU work for /families (runs in a worker thread)."""
    return rhyme_families(phonetic_words(words), PairScorer(k), min_score=min_score)


@router.post("/families", response_model=BankFamiliesResponse, responses=word_bank_errors(422))
async def families(req: BankFamiliesRequest, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """The filtered bank grouped into rhyme families (at most 1000 words)."""
    started = time.perf_counter()
    tag_filter = _tag_filter(req.tag_filter)
    owner = owner_id(user)
    words = await service.filtered_words(db, owner, tag_filter)
    try:
        result = await asyncio.to_thread(_families, _plain(words), req.min_score, req.k)
    except TagServiceError as exc:
        raise word_bank_http_error(exc) from exc
    by_id = {str(w.id): w for w in words}

    def out(items: Sequence[PhoneticWord]) -> list[BankWordResponse]:
        return [word_response(by_id[w.id]) for w in items]

    logger.info(
        "word_bank.families user_id=%s words=%d families=%d unmatched=%d k=%d elapsed_ms=%.1f",
        owner, len(words), len(result.families), len(result.unmatched), req.k, (time.perf_counter() - started) * 1000,
    )
    return BankFamiliesResponse(
        families=[
            BankFamily(label=f.label, rhyme_key=list(f.rhyme_key), words=out(f.words), cohesion=f.cohesion)
            for f in result.families
        ],
        unmatched=out(result.unmatched),
    )


# ---- related words ----


@router.get("/related", response_model=RelatedWordsResponse, responses=word_bank_errors(422, 503))
async def related(
    q: str,
    limit: int = Query(30, ge=1, le=100),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    index: RelatedWords = Depends(get_related_words),
):
    """Dictionary words related in meaning to ``q`` (a word, phrase or theme). 503
    ``related_words_warming`` (with Retry-After) while the index is built, 503
    ``related_words_unavailable`` when the embedding model cannot be loaded."""
    try:
        query = related_query(q)
        hits = await index.search(query, limit)
    except TagServiceError as exc:
        raise word_bank_http_error(exc) from exc
    in_bank = await service.ids_by_lower_text(db, owner_id(user), [h.text for h in hits])
    return RelatedWordsResponse(
        query=query,
        results=[
            RelatedWordResult(
                text=h.text,
                similarity=h.similarity,
                bank_word_id=str(in_bank[h.text.lower()]) if h.text.lower() in in_bank else None,
            )
            for h in hits
        ],
    )
