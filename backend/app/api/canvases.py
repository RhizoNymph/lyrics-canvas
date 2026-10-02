from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.models.user import User
from app.schemas.canvas import CanvasCreate, CanvasUpdate, CanvasResponse, CanvasDetailResponse, ShareResponse
from app.schemas.phonetics import DerivedFromModel, SnippetPhonetics
from app.schemas.snippet import GridDataModel, SnippetResponse
from app.schemas.group import GroupResponse
from app.schemas.tag import TagResponse
from app.services import canvas_service

router = APIRouter(prefix="/api/canvases", tags=["canvases"])


def _canvas_response(canvas) -> CanvasResponse:
    return CanvasResponse(
        id=str(canvas.id),
        owner_id=str(canvas.owner_id),
        title=canvas.title,
        is_public=canvas.is_public,
        share_token=canvas.share_token,
        viewport_state=canvas.viewport_state,
        created_at=str(canvas.created_at),
        updated_at=str(canvas.updated_at),
    )


def _tag_response(t) -> TagResponse:
    return TagResponse(id=str(t.id), canvas_id=str(t.canvas_id), name=t.name, color=t.color)


def _snippet_response(s) -> SnippetResponse:
    # Stored JSONB (grid_data, phonetics, derived_from) is validated as-is; the grid models
    # emit only the keys that were stored, so the wire shape is the stored shape.
    return SnippetResponse(
        id=str(s.id),
        canvas_id=str(s.canvas_id),
        snippet_type=s.snippet_type.value,
        grid_mode=s.grid_mode.value,
        beats_per_line=s.beats_per_line,
        grid_data=GridDataModel.model_validate(s.grid_data) if s.grid_data is not None else None,
        raw_text=s.raw_text,
        position_x=s.position_x,
        position_y=s.position_y,
        width=s.width,
        height=s.height,
        z_index=s.z_index,
        group_id=str(s.group_id) if s.group_id else None,
        tags=[_tag_response(t) for t in (s.tags or [])],
        phonetics=SnippetPhonetics.model_validate(s.phonetics) if s.phonetics is not None else None,
        derived_from=DerivedFromModel.model_validate(s.derived_from) if s.derived_from is not None else None,
        created_at=str(s.created_at),
        updated_at=str(s.updated_at),
    )


def _group_response(g) -> GroupResponse:
    return GroupResponse(
        id=str(g.id),
        canvas_id=str(g.canvas_id),
        name=g.name,
        position_x=g.position_x,
        position_y=g.position_y,
        snippet_ids=[str(s.id) for s in (g.snippets or [])],
        created_at=str(g.created_at),
    )


def _canvas_detail_response(canvas) -> CanvasDetailResponse:
    return CanvasDetailResponse(
        **_canvas_response(canvas).model_dump(),
        snippets=[_snippet_response(s) for s in canvas.snippets],
        groups=[_group_response(g) for g in canvas.groups],
        tags=[_tag_response(t) for t in canvas.tags],
    )


@router.get("", response_model=list[CanvasResponse])
async def list_canvases(user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    canvases = await canvas_service.list_canvases(db, str(user.id))
    return [_canvas_response(c) for c in canvases]


@router.post("", response_model=CanvasResponse, status_code=201)
async def create_canvas(req: CanvasCreate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    canvas = await canvas_service.create_canvas(db, str(user.id), req.title)
    return _canvas_response(canvas)


@router.get("/shared/{token}", response_model=CanvasDetailResponse)
async def get_shared_canvas(token: str, db: AsyncSession = Depends(get_db)):
    canvas = await canvas_service.get_canvas_by_share_token(db, token)
    if not canvas:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return _canvas_detail_response(canvas)


@router.get("/{canvas_id}", response_model=CanvasDetailResponse)
async def get_canvas(canvas_id: str, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    canvas = await canvas_service.get_canvas(db, canvas_id, str(user.id))
    if not canvas:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return _canvas_detail_response(canvas)


@router.put("/{canvas_id}", response_model=CanvasResponse)
async def update_canvas(canvas_id: str, req: CanvasUpdate, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    canvas = await canvas_service.update_canvas(db, canvas_id, str(user.id), **req.model_dump(exclude_none=True))
    if not canvas:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return _canvas_response(canvas)


@router.delete("/{canvas_id}", status_code=204)
async def delete_canvas(canvas_id: str, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    if not await canvas_service.delete_canvas(db, canvas_id, str(user.id)):
        raise HTTPException(status_code=404, detail="Canvas not found")


@router.post("/{canvas_id}/share", response_model=ShareResponse)
async def share_canvas(canvas_id: str, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    token = await canvas_service.generate_share_token(db, canvas_id, str(user.id))
    if not token:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return ShareResponse(share_token=token)


@router.post("/{canvas_id}/fork", response_model=CanvasResponse)
async def fork_canvas(canvas_id: str, user: User = Depends(get_current_user), db: AsyncSession = Depends(get_db)):
    canvas = await canvas_service.fork_canvas(db, canvas_id, str(user.id))
    if not canvas:
        raise HTTPException(status_code=404, detail="Canvas not found or not public")
    return _canvas_response(canvas)
