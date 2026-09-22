import uuid

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.db.database import get_db
from app.api.deps import get_current_user
from app.models.user import User
from app.models.snippet import Snippet
from app.models.canvas import Canvas
from app.schemas.nlp import ExportRequest
from app.services.export_service import export_as_text, export_as_markdown, export_as_pdf

router = APIRouter(tags=["export"])


@router.post("/api/canvases/{canvas_id}/export")
async def export_canvas(
    canvas_id: str,
    req: ExportRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    result = await db.execute(
        select(Canvas).options(selectinload(Canvas.snippets).selectinload(Snippet.tags))
        .where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == user.id)
    )
    canvas = result.scalar_one_or_none()
    if not canvas:
        raise HTTPException(status_code=404, detail="Canvas not found")

    snippets = sorted(canvas.snippets, key=lambda s: (s.position_y, s.position_x))

    if req.format == "text":
        content = export_as_text(snippets)
        return Response(content=content, media_type="text/plain",
                       headers={"Content-Disposition": f'attachment; filename="{canvas.title}.txt"'})
    elif req.format == "pdf":
        content = export_as_pdf(snippets)
        return Response(content=content, media_type="application/pdf",
                       headers={"Content-Disposition": f'attachment; filename="{canvas.title}.pdf"'})
    else:
        content = export_as_markdown(snippets)
        return Response(content=content, media_type="text/markdown",
                       headers={"Content-Disposition": f'attachment; filename="{canvas.title}.md"'})
