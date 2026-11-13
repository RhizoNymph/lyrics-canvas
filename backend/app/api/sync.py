"""Offline sync routes (docs/features/sync_versions.md#snapshot)."""

from __future__ import annotations

from fastapi import APIRouter, Depends
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.canvases import _canvas_response
from app.api.deps import get_current_user
from app.api.tag_errors import owner_id
from app.api.tag_presets import _preset_response
from app.api.tags import _tag_response
from app.api.word_bank import word_response
from app.db.database import get_db
from app.models.placement import SnippetPlacement
from app.models.user import User
from app.schemas.phonetics import DerivedFromModel, SnippetPhonetics
from app.schemas.snippet import GridDataModel
from app.schemas.sync import SyncPlacement, SyncSnapshotResponse, SyncSnippet
from app.services.sync.snapshot import SnapshotSnippet, load_snapshot

router = APIRouter(prefix="/api/sync", tags=["sync"])


def _placement(p: SnippetPlacement) -> SyncPlacement:
    return SyncPlacement(
        snippet_id=str(p.snippet_id),
        canvas_id=str(p.canvas_id),
        placement_id=str(p.id),
        position_x=p.position_x,
        position_y=p.position_y,
        width=p.width,
        height=p.height,
        z_index=p.z_index,
        group_id=str(p.group_id) if p.group_id else None,
    )


def _snippet(item: SnapshotSnippet) -> SyncSnippet:
    s = item.snippet
    return SyncSnippet(
        id=str(s.id),
        snippet_type=s.snippet_type.value,
        grid_mode=s.grid_mode.value,
        beats_per_line=s.beats_per_line,
        grid_data=GridDataModel.model_validate(s.grid_data) if s.grid_data is not None else None,
        raw_text=s.raw_text,
        phonetics=SnippetPhonetics.model_validate(s.phonetics) if s.phonetics is not None else None,
        derived_from=DerivedFromModel.model_validate(s.derived_from) if s.derived_from is not None else None,
        created_at=str(s.created_at),
        updated_at=str(s.updated_at),
        content_updated_at=str(s.content_updated_at),
        version=s.version,
        tag_ids=[str(t) for t in item.tag_ids],
    )


@router.get("/snapshot", response_model=SyncSnapshotResponse)
async def snapshot(user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    """Everything of the caller's the offline client keeps, as of ``server_time``: canvases, each
    snippet once (content, version, tag ids), the placements that put snippets on canvases, tags,
    tag presets and the word bank."""
    snap = await load_snapshot(db, owner_id(user))
    return SyncSnapshotResponse(
        server_time=str(snap.server_time),
        canvases=[_canvas_response(c) for c in snap.canvases],
        placements=[_placement(p) for p in snap.placements],
        snippets=[_snippet(s) for s in snap.snippets],
        tags=[_tag_response(t) for t in snap.tags],
        tag_presets=[_preset_response(p) for p in snap.presets],
        word_bank=[word_response(w) for w in snap.words],
    )
