import uuid
from sqlalchemy import select, func, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.snippet import Snippet
from app.models.canvas import Canvas


async def search_snippets(
    db: AsyncSession,
    canvas_id: str,
    query: str,
    owner_id: str | None = None,
    limit: int = 20,
) -> list[dict]:
    """Full-text search within a canvas using PostgreSQL ts_rank.
    
    Falls back to ILIKE if tsvector column isn't available.
    """
    results = []

    # Use ILIKE-based search (simpler, works without tsvector setup)
    q = (
        select(Snippet)
        .where(Snippet.canvas_id == uuid.UUID(canvas_id))
        .where(Snippet.raw_text.ilike(f"%{query}%"))
        .limit(limit)
    )
    result = await db.execute(q)
    snippets = result.scalars().all()

    for snippet in snippets:
        results.append({
            "snippet_id": str(snippet.id),
            "raw_text": snippet.raw_text,
            "score": 1.0,
            "snippet_type": snippet.snippet_type.value,
        })

    return results
