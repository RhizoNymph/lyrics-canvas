from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.api.canvases import _group_response
from app.models.user import User
from app.schemas.group import GroupCreate, GroupResponse, GroupUpdate
from app.services import group_service

router = APIRouter(tags=["groups"])


@router.post("/api/canvases/{canvas_id}/groups", response_model=GroupResponse, status_code=201)
async def create_group(
    canvas_id: str,
    req: GroupCreate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    group = await group_service.create_group(db, canvas_id, str(user.id), **req.model_dump())
    if not group:
        raise HTTPException(status_code=404, detail="Canvas not found")
    return _group_response(group)


@router.put("/api/groups/{group_id}", response_model=GroupResponse)
async def update_group(
    group_id: str,
    req: GroupUpdate,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    group = await group_service.update_group(db, group_id, str(user.id), **req.model_dump(exclude_none=True))
    if not group:
        raise HTTPException(status_code=404, detail="Group not found")
    return _group_response(group)


@router.delete("/api/groups/{group_id}", status_code=204)
async def delete_group(
    group_id: str,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    if not await group_service.delete_group(db, group_id, str(user.id)):
        raise HTTPException(status_code=404, detail="Group not found")
