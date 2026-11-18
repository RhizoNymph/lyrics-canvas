"""Snippet content and per-canvas layout (docs/features/snippet_placements.md).

Content fields live on ``Snippet`` and are shared by every canvas holding it; layout fields
(``LAYOUT_FIELDS``) and the custom list rank (``RANK_FIELDS``) live on the ``SnippetPlacement`` of
one canvas. Ownership of a snippet is
``Snippet.owner_id``; canvas-scoped operations check the placement's canvas owner.

A content change also bumps ``Snippet.version`` once (docs/features/sync_versions.md#versions);
layout changes never do.

Every content update runs the score save flow (app/services/score_save.py): the stored score is
carried over text and grid changes, a sent score replaces it, and the score's gaps are written into
the grid and text. Phonetics and card size follow the final text and grid
(docs/features/score_backend.md#save-flow).
"""

import logging
import uuid
from collections.abc import Awaitable, Callable, Mapping, Sequence

from sqlalchemy import func, select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet
from app.models.tag import Tag
from app.schemas.phonetics import SnippetPhonetics
from app.services.merge_service import MergePersistError
from app.services.placements.errors import PlacementNotFoundError
from app.services.placements.queries import (
    ensure_group_on_canvas,
    owned_canvas,
    placed_by_placement_id,
    placed_snippet,
    placed_snippets,
)
from app.services.placements.list_rank import validate_rank
from app.services.placements.ranks import rank_for_new
from app.services.placements.removal import delete_everywhere, lock_snippets
from app.services.placements.view import PLACEMENT_FIELDS, PlacedSnippet
from app.services.score_save import KeepScore, ScoredContent, ScoreEdit, saved_content
from app.services.snippet_content import content_for
from app.services.sync.bump import bump

logger = logging.getLogger(__name__)


def split_fields(fields: Mapping[str, object]) -> tuple[dict[str, object], dict[str, object]]:
    """``(content, layout)``: the snippet's columns and the placement's columns (``list_rank`` too)."""
    content = {k: v for k, v in fields.items() if k not in PLACEMENT_FIELDS}
    layout = {k: v for k, v in fields.items() if k in PLACEMENT_FIELDS}
    return content, layout


async def create_snippet(
    db: AsyncSession, canvas_id: str, owner_id: str, *, snippet_id: uuid.UUID | None = None, **kwargs
) -> PlacedSnippet | None:
    """A new snippet owned by ``owner_id`` with its placement on the canvas; None when the canvas
    is not the caller's. ``group_id`` must be a group of that canvas (``GroupNotFoundError``).
    ``list_rank`` is used when given (validated, ``InvalidListRankError``), else the placement goes
    to the top of the canvas's custom order.
    ``snippet_id`` is a client-chosen id (app/services/sync/client_ids.py resolves retries)."""
    cid, owner = uuid.UUID(canvas_id), uuid.UUID(owner_id)
    if await owned_canvas(db, cid, owner) is None:
        return None
    content, layout = split_fields(kwargs)
    if layout.get("group_id") is not None:
        await ensure_group_on_canvas(db, layout["group_id"], cid)
    layout["list_rank"] = await rank_for_new(db, cid, layout.pop("list_rank", None))
    snippet = Snippet(id=snippet_id or uuid.uuid4(), owner_id=owner, **content)
    placement = SnippetPlacement(id=uuid.uuid4(), snippet_id=snippet.id, canvas_id=cid, **layout)
    db.add(snippet)
    db.add(placement)
    await db.commit()
    return await placed_by_placement_id(db, placement.id)


async def get_snippet(db: AsyncSession, snippet_id: str) -> Snippet | None:
    result = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags)).where(Snippet.id == uuid.UUID(snippet_id))
    )
    return result.scalar_one_or_none()


async def get_owned_snippet(db: AsyncSession, snippet_id: str, owner_id: str) -> Snippet | None:
    result = await db.execute(
        select(Snippet).options(selectinload(Snippet.tags))
        .where(Snippet.id == uuid.UUID(snippet_id), Snippet.owner_id == uuid.UUID(owner_id))
    )
    return result.scalar_one_or_none()


async def delete_snippet(db: AsyncSession, snippet_id: str, owner_id: str) -> bool:
    """Delete everywhere: the snippet, all its placements and recordings. False if not the caller's."""
    return await delete_everywhere(db, uuid.UUID(snippet_id), uuid.UUID(owner_id))


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


def apply_update(
    placed: PlacedSnippet,
    content_edits: Mapping[str, object],
    changes: Mapping[str, object],
    scored: ScoredContent | None = None,
) -> bool:
    """Set the non-``None`` ``changes``: content on the snippet, layout on this canvas's placement,
    and, when given, ``scored``'s score and view (None is a value there: no score).
    Bumps ``content_updated_at`` and ``version`` when ``content_edits`` change stored content or the
    score or view changes; returns whether it did. Derived values alone (phonetics, a resent grid)
    bump neither."""
    snippet = placed.snippet
    if scored is None:
        content_changed = content_edits_differ(snippet, content_edits)
    else:
        # After the save flow, compare what will be stored: the sent text and grid as the flow left
        # them (a client resending its already linked grid, text and score bumps nothing).
        final = {**content_edits}
        if "raw_text" in final:
            final["raw_text"] = scored.raw_text
        if "grid_data" in final:
            final["grid_data"] = scored.grid
        content_changed = content_edits_differ(snippet, final) or (
            scored.score != snippet.score or scored.score_view != snippet.score_view
        )
    for key, value in changes.items():
        if value is None:
            continue
        target = placed.placement if key in PLACEMENT_FIELDS else snippet
        setattr(target, key, value)
    if scored is not None:
        snippet.score = scored.score
        snippet.score_view = scored.score_view
    if content_changed:
        # SQL now(): the transaction clock, so it equals the updated_at written by the same UPDATE.
        snippet.content_updated_at = func.now()
        bump(snippet)
    return content_changed


def runs_score_flow(changes: Mapping[str, object], edit: ScoreEdit, view: bool | None) -> bool:
    """Whether an update touches what the score depends on (text, grid) or the score itself."""
    return (
        changes.get("raw_text") is not None
        or changes.get("grid_data") is not None
        or not isinstance(edit, KeepScore)
        or view is not None
    )


def scored_changes(
    snippet: Snippet, content_edits: Mapping[str, object], changes: Mapping[str, object], edit: ScoreEdit, view: bool | None
) -> tuple[dict[str, object], ScoredContent]:
    """Run the save flow on the update's text and grid (``changes``, else the stored ones). When the
    flow rewrote them (rest link, notes moved by a rest-only edit), the changes get the final text
    and grid and phonetics and size computed from them: with no overrides when the client sent
    text, as the route does, else with the stored overrides. Raises the flow's ScoreErrors."""
    stored = ScoredContent(snippet.raw_text, snippet.grid_data, snippet.score, snippet.score_view)
    text = changes.get("raw_text")
    grid = changes.get("grid_data")
    text = snippet.raw_text if text is None else text
    grid = snippet.grid_data if grid is None else grid
    scored = saved_content(stored, raw_text=str(text), grid=grid, edit=edit, view=view)  # type: ignore[arg-type]
    out = dict(changes)
    if scored.raw_text != text or scored.grid != grid:
        overrides = None
        if "raw_text" not in content_edits and snippet.phonetics:
            overrides = SnippetPhonetics.model_validate(snippet.phonetics).overrides
        content = content_for(scored.raw_text, scored.grid or {"lines": []}, overrides)
        out.update(raw_text=scored.raw_text, grid_data=scored.grid, phonetics=content.phonetics,
                   width=content.width, height=content.height)
    return out, scored


async def update_snippet(
    db: AsyncSession,
    snippet_id: str,
    owner_id: str,
    *,
    canvas_id: uuid.UUID,
    content_edits: Mapping[str, object],
    score_edit: ScoreEdit = KeepScore(),
    score_view: bool | None = None,
    **kwargs,
) -> PlacedSnippet:
    """PUT /api/snippets/{id}?canvas_id=: content to the shared snippet, layout to the placement on
    ``canvas_id``. ``content_edits`` are the content fields the client sent (not derived values such
    as a re-syllabified grid). ``score_edit`` / ``score_view`` are the score fields the client sent;
    any text, grid or score change runs the save flow on the row locked FOR UPDATE.
    ``PlacementNotFoundError`` when not placed on the caller's canvas; ``InvalidListRankError``
    (before anything changes) for an invalid ``list_rank``; the flow's ``ScoreError``s (nothing
    changed)."""
    if kwargs.get("list_rank") is not None:
        validate_rank(kwargs["list_rank"])
    flow = runs_score_flow(kwargs, score_edit, score_view)
    placed = await placed_snippet(db, uuid.UUID(snippet_id), canvas_id, uuid.UUID(owner_id), lock=flow)
    if placed is None:
        raise PlacementNotFoundError(f"snippet {snippet_id} is not on canvas {canvas_id}")
    if kwargs.get("group_id") is not None:
        await ensure_group_on_canvas(db, kwargs["group_id"], canvas_id)
    scored: ScoredContent | None = None
    if flow:
        kwargs, scored = scored_changes(placed.snippet, content_edits, kwargs, score_edit, score_view)
    content_changed = apply_update(placed, content_edits, kwargs, scored)
    logger.debug(
        "snippet.update snippet_id=%s canvas_id=%s fields=%s score_edit=%s content_changed=%s",
        snippet_id, canvas_id, sorted(k for k, v in kwargs.items() if v is not None), type(score_edit).__name__,
        content_changed,
    )
    await db.commit()
    return await placed_by_placement_id(db, placed.placement_id)


async def batch_update_positions(
    db: AsyncSession, canvas_id: uuid.UUID, updates: Sequence[Mapping[str, object]], owner_id: str
) -> int:
    """Move or re-rank placements on ``canvas_id``; ids not placed there (or not the caller's) are
    skipped. Each update is ``{"id", "position_x"?, "position_y"?, "list_rank"?}``; every rank is
    validated before anything changes (``InvalidListRankError``). Returns how many were updated."""
    for update in updates:
        if update.get("list_rank") is not None:
            validate_rank(update["list_rank"])
    wanted = [uuid.UUID(str(u["id"])) for u in updates]
    found = {s.id: s for s in await placed_snippets(db, canvas_id, uuid.UUID(owner_id), wanted)}
    count = 0
    for update in updates:
        placed = found.get(uuid.UUID(str(update["id"])))
        if placed is None:
            continue
        for key in ("position_x", "position_y", "list_rank"):
            if update.get(key) is not None:
                setattr(placed.placement, key, update[key])
        count += 1
    await db.commit()
    return count


async def get_canvas_snippets(
    db: AsyncSession, canvas_id: uuid.UUID, owner_id: str, snippet_ids: Sequence[uuid.UUID]
) -> list[PlacedSnippet]:
    """The requested snippets placed on the caller's canvas, tags and canvases loaded, rows locked
    until the transaction ends (the merge reads ``canvases`` to decide what survives).
    Missing, foreign or other-canvas ids are simply absent from the result."""
    await lock_snippets(db, snippet_ids)
    return await placed_snippets(db, canvas_id, uuid.UUID(owner_id), snippet_ids)


# Moves children of the merge originals onto the merged snippet: (db, merged_id, original_ids).
Reparent = Callable[[AsyncSession, uuid.UUID, Sequence[uuid.UUID]], Awaitable[None]]


def placed_elsewhere(original: PlacedSnippet) -> bool:
    return any(ref.id != original.canvas_id for ref in original.canvases)


async def replace_with_merged(
    db: AsyncSession,
    canvas_id: uuid.UUID,
    fields: Mapping[str, object],
    tags: Sequence[Tag],
    originals: Sequence[PlacedSnippet],
    *,
    reparent: Reparent | None = None,
    merged_id: uuid.UUID | None = None,
) -> PlacedSnippet:
    """Create the merged snippet (content and layout from ``fields``) with ``tags`` and its
    placement on ``canvas_id``, and take ``originals`` off the canvas, in one transaction.

    An original also placed on other canvases loses only this canvas's placement and keeps its
    recordings. An original only on this canvas is deleted; ``reparent(db, merged_id, ids)`` runs
    for those first (after the merged snippet is inserted), so children that would cascade with
    them (the merge route passes ``recording_service.reassign_recordings``) move to the merged one.

    All or nothing: any database error rolls the whole transaction back and raises
    ``MergePersistError``; on success there is exactly one commit. ``merged_id`` is a client-chosen
    id for the merged snippet (app/services/sync/client_ids.py resolves retries).

    The merged placement keeps the list rank of the first original (merge order) unless ``fields``
    names one.
    """
    content, layout = split_fields(fields)
    if layout.get("list_rank") is None and originals and originals[0].placement.list_rank is not None:
        layout["list_rank"] = originals[0].placement.list_rank
    snippet = Snippet(id=merged_id or uuid.uuid4(), owner_id=originals[0].owner_id, **content)
    snippet.tags = list(tags)
    placement = SnippetPlacement(id=uuid.uuid4(), snippet_id=snippet.id, canvas_id=canvas_id, **layout)
    kept = [o for o in originals if placed_elsewhere(o)]
    deleted = [o for o in originals if not placed_elsewhere(o)]
    try:
        db.add(snippet)
        db.add(placement)
        await db.flush()
        if reparent is not None:
            await reparent(db, snippet.id, [o.id for o in deleted])
        for original in kept:
            await db.delete(original.placement)
        for original in deleted:
            await db.delete(original.snippet)
        await db.commit()
    except SQLAlchemyError as exc:
        await db.rollback()
        logger.error(
            "merge.persist.failed canvas_id=%s originals=%s error=%s",
            canvas_id, [str(o.id) for o in originals], exc,
        )
        raise MergePersistError("could not store the merge; nothing changed") from exc
    logger.info(
        "merge.persisted canvas_id=%s snippet_id=%s kept_elsewhere=%d deleted=%d",
        canvas_id, snippet.id, len(kept), len(deleted),
    )
    return await placed_by_placement_id(db, placement.id)
