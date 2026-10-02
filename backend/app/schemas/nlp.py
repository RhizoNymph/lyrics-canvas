from pydantic import BaseModel

from app.schemas.snippet import GridLineModel


class SyllabifyRequest(BaseModel):
    text: str


class SyllabifyResponse(BaseModel):
    lines: list[GridLineModel]


class SearchResult(BaseModel):
    snippet_id: str
    raw_text: str
    score: float
    snippet_type: str


class SimilarityResult(BaseModel):
    snippet_id: str
    raw_text: str
    score: float
    snippet_type: str


class RhymeResult(BaseModel):
    word: str
    rhyme_type: str  # "perfect" or "near"
    source: str  # "dictionary" or "snippet"
    snippet_id: str | None = None
    snippet_preview: str | None = None


class TSNERequest(BaseModel):
    n_components: int = 2
    perplexity: float = 30.0


class TSNEResult(BaseModel):
    positions: list[dict]  # [{"snippet_id": "...", "x": ..., "y": ..., "z": ...}]


class ExportRequest(BaseModel):
    format: str = "markdown"  # "text", "markdown", "pdf"
