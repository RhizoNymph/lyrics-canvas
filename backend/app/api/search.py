"""``GET /api/search``: the caller's canvases (by title) and snippets (by display text) matching a
query. Typed errors: 422 ``{"detail": {"error": "invalid_search_query", "message"}}``."""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import get_current_user
from app.api.tag_errors import owner_id
from app.db.database import get_db
from app.models.user import User
from app.schemas.search import (
    SearchCanvasHit,
    SearchErrorBody,
    SearchErrorResponse,
    SearchResponse,
    SearchSnippetHit,
)
from app.schemas.snippet import SnippetCanvasRef
from app.services.search.errors import InvalidSearchQueryError, SearchError
from app.services.search.query import MAX_QUERY_LENGTH, parse_search_query
from app.services.search.service import SearchResults, search_everything

DEFAULT_LIMIT = 50
MAX_LIMIT = 200

router = APIRouter(tags=["search"])


def search_http_error(exc: SearchError) -> HTTPException:
    body = SearchErrorBody(error=exc.code.value, message=exc.message)
    return HTTPException(status_code=exc.status, detail=body.model_dump())


def _response(results: SearchResults) -> SearchResponse:
    return SearchResponse(
        canvases=[SearchCanvasHit(id=str(c.id), title=c.title, updated_at=str(c.updated_at)) for c in results.canvases],
        snippets=[
            SearchSnippetHit(
                snippet_id=str(s.snippet_id),
                snippet_type=s.snippet_type,
                content_updated_at=str(s.content_updated_at),
                excerpt=s.excerpt,
                canvases=[SnippetCanvasRef(id=str(ref.id), title=ref.title) for ref in s.canvases],
            )
            for s in results.snippets
        ],
    )


@router.get("/api/search", response_model=SearchResponse, responses={422: {"model": SearchErrorResponse}})
async def search_all(
    q: str | None = Query(None, description=f"1-{MAX_QUERY_LENGTH} characters after trimming; whitespace separates terms"),
    limit: int = Query(DEFAULT_LIMIT, ge=1, le=MAX_LIMIT, description="maximum hits in each list"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SearchResponse:
    try:
        query = parse_search_query(q)
    except InvalidSearchQueryError as exc:
        raise search_http_error(exc) from exc
    return _response(await search_everything(db, owner_id(user), query, limit))
