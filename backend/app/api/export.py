import uuid

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.models.user import User
from app.models.canvas import Canvas
from app.schemas.nlp import ExportRequest
from app.services.placements.queries import placed_on_canvas
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
        select(Canvas).where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == user.id)
    )
    canvas = result.scalar_one_or_none()
    if not canvas:
        raise HTTPException(status_code=404, detail="Canvas not found")

    # the snippets placed on this canvas, in reading order of their placements here
    placed = await placed_on_canvas(db, canvas.id)
    snippets = [p.snippet for p in sorted(placed, key=lambda p: (p.position_y, p.position_x))]

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
