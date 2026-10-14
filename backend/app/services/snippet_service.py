import logging
import uuid
from collections.abc import Mapping, Sequence

from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.snippet import Snippet
from app.models.canvas import Canvas
from app.models.tag import Tag
from app.services.merge_service import MergePersistError

logger = logging.getLogger(__name__)


async def create_snippet(db: AsyncSession, canvas_id: str, owner_id: str, **kwargs) -> Snippet | None:
    result = await db.execute(
        select(Canvas).where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    canvas = result.scalar_one_or_none()
    if not canvas:
        return None

    snippet = Snippet(canvas_id=uuid.UUID(canvas_id), **kwargs)
    db.add(snippet)
    await db.commit()
    # Re-fetch with tags loaded so the response includes them
    result2 = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == snippet.id)
    )
    return result2.scalar_one()


async def get_snippet(db: AsyncSession, snippet_id: str) -> Snippet | None:
    result = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == uuid.UUID(snippet_id))
    )
    return result.scalar_one_or_none()


async def get_owned_snippet(db: AsyncSession, snippet_id: str, owner_id: str) -> Snippet | None:
    result = await db.execute(
        select(Snippet).join(Canvas).options(selectinload(Snippet.tags))
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    return result.scalar_one_or_none()


# The columns whose change is a content edit and bumps ``content_updated_at``. Layout
# (position, size, z_index), grouping and derived phonetics are deliberately absent.
CONTENT_FIELDS: frozenset[str] = frozenset({"raw_text", "snippet_type", "grid_mode", "beats_per_line", "grid_data"})


def content_edits_differ(snippet: Snippet, edits: Mapping[str, object]) -> bool:
    """True when any content field in ``edits`` differs from what ``snippet`` stores.

    Keys outside ``CONTENT_FIELDS`` and ``None`` values (not sent) are ignored.
    """
    return any(
        key in CONTENT_FIELDS and value is not None and getattr(snippet, key) != value
        for key, value in edits.items()
    )


async def update_snippet(
    db: AsyncSession, snippet_id: str, owner_id: str, *, content_edits: Mapping[str, object], **kwargs
) -> Snippet | None:
    """Apply the non-``None`` ``kwargs``; bump ``content_updated_at`` if ``content_edits`` (the
    content fields the client sent, not derived values such as a re-syllabified grid) change
    anything stored."""
    result = await db.execute(
        select(Snippet).join(Canvas).options(selectinload(Snippet.tags))
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    snippet = result.scalar_one_or_none()
    if not snippet:
        return None
    content_changed = content_edits_differ(snippet, content_edits)
    for key, value in kwargs.items():
        if value is not None:
            setattr(snippet, key, value)
    if content_changed:
        # SQL now(): the transaction clock, so it equals the updated_at written by the same UPDATE.
        snippet.content_updated_at = func.now()
    logger.debug(
        "snippet.update snippet_id=%s fields=%s content_changed=%s",
        snippet_id, sorted(k for k, v in kwargs.items() if v is not None), content_changed,
    )
    await db.commit()
    # Re-fetch with tags loaded
    result2 = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == snippet.id)
    )
    return result2.scalar_one()


async def delete_snippet(db: AsyncSession, snippet_id: str, owner_id: str) -> bool:
    result = await db.execute(
        select(Snippet).join(Canvas)
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == uuid.UUID(owner_id))
    )
    snippet = result.scalar_one_or_none()
    if not snippet:
        return False
    await db.delete(snippet)
    await db.commit()
    return True


async def batch_update_positions(db: AsyncSession, updates: list[dict], owner_id: str) -> int:
    count = 0
    for update in updates:
        result = await db.execute(
            select(Snippet).join(Canvas)
            .where(Snippet.id == uuid.UUID(update["id"]), Canvas.owner_id == uuid.UUID(owner_id))
        )
        snippet = result.scalar_one_or_none()
        if snippet:
            snippet.position_x = update.get("position_x", snippet.position_x)
            snippet.position_y = update.get("position_y", snippet.position_y)
            count += 1
    await db.commit()
    return count


async def get_canvas_snippets(
    db: AsyncSession, canvas_id: uuid.UUID, owner_id: str, snippet_ids: Sequence[uuid.UUID]
) -> list[Snippet]:
    """The requested snippets that are in the canvas and owned by ``owner_id``, tags loaded.
    Missing, foreign or other-canvas ids are simply absent from the result."""
    result = await db.execute(
        select(Snippet).join(Canvas).options(selectinload(Snippet.tags))
        .where(Snippet.id.in_(list(snippet_ids)), Snippet.canvas_id == canvas_id, Canvas.owner_id == uuid.UUID(owner_id))
    )
    return list(result.scalars().all())


async def replace_with_merged(
    db: AsyncSession,
    canvas_id: uuid.UUID,
    fields: Mapping[str, object],
    tags: Sequence[Tag],
    originals: Sequence[Snippet],
) -> Snippet:
    """Create the merged snippet with ``tags`` and delete ``originals`` in one transaction.

    All or nothing: any database error rolls the whole transaction back and raises
    ``MergePersistError``; on success there is exactly one commit.
    """
    snippet = Snippet(canvas_id=canvas_id, **fields)
    snippet.tags = list(tags)
    try:
        db.add(snippet)
        await db.flush()
        for original in originals:
            await db.delete(original)
        await db.commit()
    except SQLAlchemyError as exc:
        await db.rollback()
        logger.error(
            "merge.persist.failed canvas_id=%s originals=%s error=%s",
            canvas_id, [str(o.id) for o in originals], exc,
        )
        raise MergePersistError("could not store the merge; nothing changed") from exc
    result = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == snippet.id)
    )
    return result.scalar_one()
