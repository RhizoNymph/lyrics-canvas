from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.api.canvases import _tag_response
from app.models.user import User
from app.schemas.tag import TagCreate, TagUpdate
from app.services import tag_service

router = APIRouter(tags=["tags"])


@router.post("/api/canvases/{canvas_id}/tags", status_code=201)
async def create_tag(
    canvas_id: str,
    req: TagCreate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tag = await tag_service.create_tag(db, canvas_id, str(user.id), req.name, req.color)
    if not tag:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return _tag_response(tag)


@router.put("/api/tags/{tag_id}")
async def update_tag(
    tag_id: str,
    req: TagUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    tag = await tag_service.update_tag(db, tag_id, str(user.id), req.name, req.color)
    if not tag:
        raise HTTPException(status_code=404, detail="Tag not found")
    return _tag_response(tag)


@router.delete("/api/tags/{tag_id}", status_code=204)
async def delete_tag(
    tag_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if not await tag_service.delete_tag(db, tag_id, str(user.id)):
        raise HTTPException(status_code=404, detail="Tag not found")


@router.post("/api/snippets/{snippet_id}/tags")
async def add_tag(
    snippet_id: str,
    tag_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if not await tag_service.add_tag_to_snippet(db, snippet_id, tag_id, str(user.id)):
        raise HTTPException(status_code=404, detail="Not found")
    return {"ok": True}


@router.delete("/api/snippets/{snippet_id}/tags/{tag_id}", status_code=204)
async def remove_tag(
    snippet_id: str,
    tag_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    await tag_service.remove_tag_from_snippet(db, snippet_id, tag_id, str(user.id))
