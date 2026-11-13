import uuid

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.models.user import User
from app.models.snippet import Snippet
from app.models.canvas import Canvas
from app.models.placement import SnippetPlacement
from app.schemas.nlp import SyllabifyRequest, SyllabifyResponse, TSNERequest
from app.schemas.rhyme import RhymeSuggestRequest, RhymeSuggestResponse
from app.schemas.rhyme_pattern import (
    PatternDetectRequest,
    PatternDetectResponse,
    PatternErrorBody,
    PatternSuggestRequest,
    PatternSuggestResponse,
)
from app.services.nlp.syllabifier import syllabify_text
from app.services.nlp.similarity_service import compute_tfidf_similarity
from app.services.nlp.tsne_service import compute_tsne
from app.services.nlp.rhyme_service import find_rhymes, find_rhymes_in_texts
from app.services.nlp.search_service import search_snippets
from app.services.nlp.embedding_service import compute_embedding
from app.services.rest_marks import display_text
from app.services import recording_service, rhyme_pattern_service, rhyme_suggest_service, snippet_service
from app.services.rhyme_pattern.errors import PatternError
from app.services.placements.errors import PlacementNotFoundError
from app.services.phonetics.errors import DialectRuleError, PronunciationError, RhymeQueryError
from app.services.rhyme_suggest_service import CanvasNotFoundError, SnippetNotFoundError
from app.services.tag_expr import TagExpressionError
from app.api.placement_errors import placement_errors, placement_http_error
from app.api.tag_errors import expression_http_error, owner_id

router = APIRouter(tags=["nlp"])


@router.post("/api/nlp/syllabify", response_model=SyllabifyResponse)
async def syllabify(req: SyllabifyRequest):
    return SyllabifyResponse.model_validate(syllabify_text(req.text))


@router.get("/api/canvases/{canvas_id}/search")
async def search(
    canvas_id: str,
    q: str = Query(..., min_length=1),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    results = await search_snippets(db, canvas_id, q, str(user.id))
    return results


@router.get("/api/snippets/{snippet_id}/similar", responses=placement_errors(404))
async def find_similar(
    snippet_id: uuid.UUID,
    top_k: int = Query(10, ge=1, le=50),
    canvas_id: uuid.UUID | None = Query(None, description="compare with the snippets placed on this canvas; default: every canvas holding the snippet"),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    target = await snippet_service.get_owned_snippet(db, str(snippet_id), str(user.id))
    if not target:
        raise HTTPException(status_code=404, detail="Snippet not found")
    if canvas_id is None:
        canvas_ids = select(SnippetPlacement.canvas_id).where(SnippetPlacement.snippet_id == target.id)
    else:
        if not await recording_service.snippet_in_canvas(db, target.id, canvas_id, owner_id(user)):
            raise placement_http_error(PlacementNotFoundError(f"snippet {snippet_id} is not on canvas {canvas_id}"))
        canvas_ids = select(SnippetPlacement.canvas_id).where(SnippetPlacement.canvas_id == canvas_id)

    # The other snippets placed on those canvases, each once
    result = await db.execute(
        select(Snippet)
        .where(
            Snippet.id.in_(select(SnippetPlacement.snippet_id).where(SnippetPlacement.canvas_id.in_(canvas_ids))),
            Snippet.id != target.id,
            Snippet.raw_text != "",
        )
        .order_by(Snippet.created_at, Snippet.id)
    )
    # compared on display text (rest marks removed); snippets with no words take no part
    target_text = display_text(target.raw_text)
    others = [s for s in result.scalars().all() if display_text(s.raw_text).strip()]
    if not others or not target_text.strip():
        return []

    by_id = {str(s.id): s for s in others}
    corpus_texts = [display_text(s.raw_text) for s in others]
    corpus_ids = list(by_id)

    results = compute_tfidf_similarity(target_text, corpus_texts, corpus_ids, top_k)
    for r in results:
        snippet = by_id[r["snippet_id"]]
        r["raw_text"] = snippet.raw_text
        r["snippet_type"] = snippet.snippet_type.value
    return results


@router.post("/api/canvases/{canvas_id}/tsne")
async def tsne_layout(
    canvas_id: str,
    req: TSNERequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """t-SNE positions for the snippets placed on the canvas. A preview: the clients show them
    and restore the saved layout on exit, so nothing is written (layout saves go through PUT and
    batch-position with this canvas_id)."""
    result = await db.execute(
        select(Snippet)
        .join(SnippetPlacement, SnippetPlacement.snippet_id == Snippet.id)
        .join(Canvas, Canvas.id == SnippetPlacement.canvas_id)
        .where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == user.id, Snippet.raw_text != "")
        .order_by(SnippetPlacement.created_at, SnippetPlacement.id)
    )
    snippets = result.scalars().all()
    if not snippets:
        return {"positions": []}

    # Compute embeddings on-the-fly for t-SNE
    embeddings = []
    snippet_ids = []
    for s in snippets:
        text = display_text(s.raw_text)  # rest marks removed; a snippet of only rests has no embedding
        if not text.strip():
            continue
        emb = compute_embedding(text)
        if emb:
            embeddings.append(emb)
            snippet_ids.append(str(s.id))

    if len(embeddings) < 2:
        return {"positions": [{"snippet_id": sid, "x": 0, "y": 0} for sid in snippet_ids]}

    positions = compute_tsne(embeddings, snippet_ids, req.n_components, req.perplexity)
    return {"positions": positions}


@router.get("/api/nlp/rhymes")
async def get_rhymes(
    word: str = Query(..., min_length=1),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    # Dictionary rhymes
    dict_rhymes = find_rhymes(word)

    # Search user's snippets for rhymes
    # every snippet the user owns, once (not per placement)
    result = await db.execute(
        select(Snippet).where(Snippet.owner_id == user.id, Snippet.raw_text != "").order_by(Snippet.created_at, Snippet.id)
    )
    snippets = result.scalars().all()
    texts = {str(s.id): s.raw_text for s in snippets}
    snippet_rhymes = find_rhymes_in_texts(word, texts)

    return {
        "dictionary": dict_rhymes,
        "snippets": snippet_rhymes,
    }


@router.post("/api/nlp/rhyme-suggest", response_model=RhymeSuggestResponse)
async def rhyme_suggest(
    req: RhymeSuggestRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    repo = rhyme_suggest_service.DbSnippetRepository(db)
    try:
        return await rhyme_suggest_service.suggest(req, str(user.id), repo)
    except (CanvasNotFoundError, SnippetNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except TagExpressionError as exc:
        # {"error": "invalid_tag_expression", "message", "expression_error": {code, position, message}}
        raise expression_http_error(exc) from exc
    except (RhymeQueryError, DialectRuleError, PronunciationError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


def pattern_http_error(exc: PatternError) -> HTTPException:
    """``detail = {"error": "pattern_range_invalid" | "pattern_span_invalid" | "pattern_slot_order", "message"}``."""
    return HTTPException(status_code=exc.status, detail=PatternErrorBody(error=exc.code.value, message=exc.message).model_dump())


@router.post("/api/nlp/rhyme-pattern/detect", response_model=PatternDetectResponse)
async def rhyme_pattern_detect(
    req: PatternDetectRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """The rhyme slots shared by a range of 2..16 lines of one snippet (docs/features/rhyme_patterns.md)."""
    repo = rhyme_pattern_service.DbPatternRepository(db)
    try:
        return await rhyme_pattern_service.detect(req, str(user.id), repo)
    except (CanvasNotFoundError, SnippetNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except PatternError as exc:
        raise pattern_http_error(exc) from exc
    except (RhymeQueryError, DialectRuleError, PronunciationError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@router.post("/api/nlp/rhyme-pattern/suggest", response_model=PatternSuggestResponse)
async def rhyme_pattern_suggest(
    req: PatternSuggestRequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """Per-slot candidates that rhyme with every slot member, and note lines that already hit 2+ slots."""
    repo = rhyme_pattern_service.DbPatternRepository(db)
    try:
        return await rhyme_pattern_service.suggest(req, str(user.id), repo)
    except (CanvasNotFoundError, SnippetNotFoundError) as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc
    except TagExpressionError as exc:
        raise expression_http_error(exc) from exc
    except PatternError as exc:
        raise pattern_http_error(exc) from exc
    except (RhymeQueryError, DialectRuleError, PronunciationError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
