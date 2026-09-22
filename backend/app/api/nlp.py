import uuid

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.api.deps import get_current_user
from app.models.user import User
from app.models.snippet import Snippet
from app.models.canvas import Canvas
from app.models.embedding import SnippetEmbedding
from app.schemas.nlp import SyllabifyRequest, SyllabifyResponse, TSNERequest
from app.services.nlp.syllabifier import syllabify_text
from app.services.nlp.similarity_service import compute_tfidf_similarity
from app.services.nlp.tsne_service import compute_tsne
from app.services.nlp.rhyme_service import find_rhymes, find_rhymes_in_texts
from app.services.nlp.search_service import search_snippets
from app.services.nlp.embedding_service import compute_embedding

router = APIRouter(tags=["nlp"])


@router.post("/api/nlp/syllabify")
async def syllabify(req: SyllabifyRequest):
    result = syllabify_text(req.text)
    return result


@router.get("/api/canvases/{canvas_id}/search")
async def search(
    canvas_id: str,
    q: str = Query(..., min_length=1),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    results = await search_snippets(db, canvas_id, q, str(user.id))
    return results


@router.get("/api/snippets/{snippet_id}/similar")
async def find_similar(
    snippet_id: str,
    top_k: int = Query(10, ge=1, le=50),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    # Get the target snippet
    result = await db.execute(
        select(Snippet).join(Canvas)
        .where(Snippet.id == uuid.UUID(snippet_id), Canvas.owner_id == user.id)
    )
    target = result.scalar_one_or_none()
    if not target:
        raise HTTPException(status_code=404, detail="Snippet not found")

    # Get all other snippets in the same canvas
    result = await db.execute(
        select(Snippet).where(
            Snippet.canvas_id == target.canvas_id,
            Snippet.id != target.id,
            Snippet.raw_text != "",
        )
    )
    others = result.scalars().all()
    if not others:
        return []

    corpus_texts = [s.raw_text for s in others]
    corpus_ids = [str(s.id) for s in others]

    results = compute_tfidf_similarity(target.raw_text, corpus_texts, corpus_ids, top_k)
    for r in results:
        snippet = next((s for s in others if str(s.id) == r["snippet_id"]), None)
        if snippet:
            r["snippet_type"] = snippet.snippet_type.value
    return results


@router.post("/api/canvases/{canvas_id}/tsne")
async def tsne_layout(
    canvas_id: str,
    req: TSNERequest,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    # Get all snippets with embeddings
    result = await db.execute(
        select(Snippet).join(Canvas)
        .where(Canvas.id == uuid.UUID(canvas_id), Canvas.owner_id == user.id, Snippet.raw_text != "")
    )
    snippets = result.scalars().all()
    if not snippets:
        return {"positions": []}

    # Compute embeddings on-the-fly for t-SNE
    embeddings = []
    snippet_ids = []
    for s in snippets:
        emb = compute_embedding(s.raw_text)
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
    result = await db.execute(
        select(Snippet).join(Canvas)
        .where(Canvas.owner_id == user.id, Snippet.raw_text != "")
    )
    snippets = result.scalars().all()
    texts = {str(s.id): s.raw_text for s in snippets}
    snippet_rhymes = find_rhymes_in_texts(word, texts)

    return {
        "dictionary": dict_rhymes,
        "snippets": snippet_rhymes,
    }
