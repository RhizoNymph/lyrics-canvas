"""``base_version`` checks (docs/features/sync_versions.md#base_version).

Each ``check_*`` runs in the route's session before the service call it guards, so the row lock it
takes (SELECT ... FOR UPDATE) holds until the service commits: nothing can change the entity
between the check and the write. A ``None`` base version checks nothing and touches no row, which
keeps clients that never send one (the web app) on exactly the old path. A mismatch raises
``VersionConflictError`` carrying the entity as it is now (rendered by the route as ``current``);
nothing has been written then, and the session's rollback releases the lock. An entity that does
not exist is left to the service, which reports its usual 404.
"""

from __future__ import annotations

import uuid
from collections.abc import Mapping, Sequence
from typing import Protocol

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.snippet import Snippet
from app.models.tag import Tag
from app.models.word_bank import BankWord
from app.services import tag_service
from app.services.placements.queries import first_placed, placed_snippet
from app.services.snippet_service import CONTENT_FIELDS
from app.services.sync.errors import VersionConflictError
from app.services.tag_service import TagSummary
from app.services.word_bank import service as word_bank


class HasVersion(Protocol):
    @property
    def id(self) -> uuid.UUID: ...

    @property
    def version(self) -> int: ...


def conflicts(current: int, base_version: int | None) -> bool:
    """True when a base version was sent and it is not the current one."""
    return base_version is not None and current != base_version


def has_content(fields: Mapping[str, object]) -> bool:
    """True when a snippet PUT body sets a content field; only then is its base version checked."""
    return any(key in CONTENT_FIELDS and value is not None for key, value in fields.items())


def _message(kind: str, entity_id: object, current: int, base_version: int) -> str:
    return f"{kind} {entity_id} is at version {current} on the server, not {base_version}"


def _as_uuid(value: str | uuid.UUID) -> uuid.UUID | None:
    if isinstance(value, uuid.UUID):
        return value
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


# ---- snippets ----


async def _locked_snippet_version(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID) -> int | None:
    result = await db.execute(
        select(Snippet.version).where(Snippet.id == snippet_id, Snippet.owner_id == owner_id).with_for_update()
    )
    return result.scalar_one_or_none()


async def check_placed_snippet(
    db: AsyncSession, snippet_id: uuid.UUID, canvas_id: uuid.UUID, owner_id: uuid.UUID, base_version: int | None
) -> None:
    """A content edit of the snippet as placed on ``canvas_id`` (PUT /api/snippets/{id}); the
    conflict's ``current`` is the snippet in that canvas's context."""
    if base_version is None:
        return
    placed = await placed_snippet(db, snippet_id, canvas_id, owner_id, lock=True)
    if placed is not None and conflicts(placed.version, base_version):
        raise VersionConflictError(_message("snippet", snippet_id, placed.version, base_version), placed)


async def check_snippet(db: AsyncSession, snippet_id: uuid.UUID, owner_id: uuid.UUID, base_version: int | None) -> None:
    """A change with no canvas named (delete everywhere, tag link and unlink); the conflict's
    ``current`` is the snippet in the context of its oldest placement."""
    if base_version is None:
        return
    current = await _locked_snippet_version(db, snippet_id, owner_id)
    if current is None or not conflicts(current, base_version):
        return
    placed = await first_placed(db, snippet_id)
    if placed is not None:
        raise VersionConflictError(_message("snippet", snippet_id, current, base_version), placed)


async def check_removal(
    db: AsyncSession, canvas_id: uuid.UUID, snippet_id: uuid.UUID, owner_id: uuid.UUID, base_version: int | None
) -> None:
    """Remove from canvas: checked only when this is the snippet's last placement, so the removal
    would delete it for good. Taking it off one of several canvases is layout."""
    if base_version is None:
        return
    placed = await placed_snippet(db, snippet_id, canvas_id, owner_id, lock=True)
    if placed is None or len(placed.canvases) > 1:
        return
    if conflicts(placed.version, base_version):
        raise VersionConflictError(_message("snippet", snippet_id, placed.version, base_version), placed)


def check_originals[T: HasVersion](originals: Sequence[T], base_versions: Mapping[uuid.UUID, int]) -> None:
    """Merge: every original named in ``base_versions`` must be at that version (the originals are
    already locked by the merge's load). The conflict's ``current`` lists the stale ones, in the
    order of ``originals``; ids not among the originals are ignored."""
    stale = [o for o in originals if o.id in base_versions and conflicts(o.version, base_versions[o.id])]
    if stale:
        ids = ", ".join(str(o.id) for o in stale)
        raise VersionConflictError(f"snippets changed on the server since their base versions: {ids}", stale)


# ---- canvases ----


async def check_canvas(db: AsyncSession, canvas_id: str | uuid.UUID, owner_id: uuid.UUID, base_version: int | None) -> None:
    """Canvas rename and delete; ``current`` is the canvas."""
    if base_version is None:
        return
    cid = _as_uuid(canvas_id)
    if cid is None:
        return
    result = await db.execute(
        select(Canvas).where(Canvas.id == cid, Canvas.owner_id == owner_id)
        .with_for_update().execution_options(populate_existing=True)
    )
    canvas = result.scalar_one_or_none()
    if canvas is not None and conflicts(canvas.version, base_version):
        raise VersionConflictError(_message("canvas", cid, canvas.version, base_version), canvas)


# ---- tags ----


async def check_tag(db: AsyncSession, tag_id: uuid.UUID, owner_id: uuid.UUID, base_version: int | None) -> None:
    """Tag update and delete; ``current`` is the tag with its counts (``TagSummary``)."""
    if base_version is None:
        return
    result = await db.execute(select(Tag.version).where(Tag.id == tag_id, Tag.user_id == owner_id).with_for_update())
    current = result.scalar_one_or_none()
    if current is not None and conflicts(current, base_version):
        summary: TagSummary = await tag_service.summary(db, tag_id)
        raise VersionConflictError(_message("tag", tag_id, current, base_version), summary)


# ---- word bank ----


async def _locked_word_versions(db: AsyncSession, owner_id: uuid.UUID, ids: Sequence[uuid.UUID]) -> dict[uuid.UUID, int]:
    if not ids:
        return {}
    result = await db.execute(
        select(BankWord.id, BankWord.version)
        .where(BankWord.id.in_(list(ids)), BankWord.user_id == owner_id)
        .order_by(BankWord.id)
        .with_for_update()
    )
    return dict(result.tuples().all())


async def check_word(db: AsyncSession, word_id: uuid.UUID, owner_id: uuid.UUID, base_version: int | None) -> None:
    """Word rename; ``current`` is the word."""
    if base_version is None:
        return
    current = (await _locked_word_versions(db, owner_id, [word_id])).get(word_id)
    if current is not None and conflicts(current, base_version):
        (word,) = await word_bank.words_by_ids(db, [word_id])
        raise VersionConflictError(_message("word", word_id, current, base_version), word)


async def check_words(db: AsyncSession, owner_id: uuid.UUID, base_versions: Mapping[uuid.UUID, int] | None) -> None:
    """Bulk delete and bulk tags; ``current`` lists the stale words in the order of
    ``base_versions``. Words that are not the user's (or gone) are left to the service."""
    if not base_versions:
        return
    found = await _locked_word_versions(db, owner_id, list(base_versions))
    stale = [w for w, base in base_versions.items() if w in found and conflicts(found[w], base)]
    if stale:
        words = await word_bank.words_by_ids(db, stale)
        ids = ", ".join(str(w) for w in stale)
        raise VersionConflictError(f"words changed on the server since their base versions: {ids}", words)

