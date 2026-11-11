"""Per-canvas snippet search (``GET /api/canvases/{id}/search``), with the shared matching rule:
every query term is a substring of the snippet's display text (rest marks removed), NFC and
lowercased. See docs/features/global_search.md."""

import uuid

from sqlalchemy.ext.asyncio import AsyncSession

from app.services.search.matching import normalize_terms
from app.services.search.service import search_canvas


async def search_snippets(db: AsyncSession, canvas_id: str, query: str, owner_id: str, limit: int = 20) -> list[dict]:
    """Snippets on the caller's canvas matching ``query``; another user's canvas yields nothing."""
    try:
        cid, uid = uuid.UUID(canvas_id), uuid.UUID(owner_id)
    except ValueError:
        return []
    snippets = await search_canvas(db, cid, uid, normalize_terms(query), limit)
    return [
        {
            "snippet_id": str(snippet.id),
            "raw_text": snippet.raw_text,
            "score": 1.0,
            "snippet_type": snippet.snippet_type.value,
        }
        for snippet in snippets
    ]
