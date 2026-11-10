import uuid

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet


async def search_snippets(
    db: AsyncSession,
    canvas_id: str,
    query: str,
    owner_id: str | None = None,
    limit: int = 20,
) -> list[dict]:
    """Case-insensitive substring search over the snippets placed on a canvas.

    With ``owner_id`` only that user's canvas is searched (another user's canvas yields nothing).
    """
    q = (
        select(Snippet)
        .join(SnippetPlacement, SnippetPlacement.snippet_id == Snippet.id)
        .where(SnippetPlacement.canvas_id == uuid.UUID(canvas_id))
        .where(Snippet.raw_text.ilike(f"%{query}%"))
        .order_by(SnippetPlacement.created_at, SnippetPlacement.id)
        .limit(limit)
    )
    if owner_id:
        q = q.join(Canvas, Canvas.id == SnippetPlacement.canvas_id).where(Canvas.owner_id == uuid.UUID(owner_id))
    result = await db.execute(q)
    return [
        {
            "snippet_id": str(snippet.id),
            "raw_text": snippet.raw_text,
            "score": 1.0,
            "snippet_type": snippet.snippet_type.value,
        }
        for snippet in result.scalars().all()
    ]
