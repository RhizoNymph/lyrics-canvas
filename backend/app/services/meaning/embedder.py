"""The embedding model behind meaning order, as an injectable ``Embedder``.

Production uses the process-wide sentence-transformers model (all-MiniLM-L6-v2, 384 dims) from
app/services/nlp/embedding_service.py, the same one the related-word index and t-SNE use. Tests
inject a deterministic fake, so no model is downloaded.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Protocol

import numpy as np

from app.services.meaning.errors import EmbedderUnavailableError, EmbeddingFailedError

ENCODE_BATCH = 64


class Embedder(Protocol):
    """Texts to embedding rows (any float dtype, not necessarily normalized). Blocking."""

    @property
    def model_name(self) -> str: ...

    def encode(self, texts: Sequence[str]) -> np.ndarray: ...  # raises EmbeddingFailedError


EmbedderFactory = Callable[[], Embedder]  # blocking; raises EmbedderUnavailableError


def normalized_rows(rows: np.ndarray, expected: int) -> np.ndarray:
    """``rows`` as L2-normalized float32, checked to be ``expected`` x d (zero rows stay zero)."""
    matrix = np.asarray(rows, dtype=np.float32)
    if matrix.ndim != 2 or matrix.shape[0] != expected:
        raise EmbeddingFailedError(f"the embedding model returned shape {matrix.shape} for {expected} texts")
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    return np.divide(matrix, norms, out=np.zeros_like(matrix), where=norms > 0)


class SentenceTransformerEmbedder:
    def __init__(self, model: object, model_name: str) -> None:
        self._model = model
        self.model_name = model_name

    def encode(self, texts: Sequence[str]) -> np.ndarray:
        try:
            return self._model.encode(list(texts), convert_to_numpy=True, batch_size=ENCODE_BATCH)  # type: ignore[attr-defined]
        except (RuntimeError, ValueError) as exc:
            raise EmbeddingFailedError(f"the embedding model failed: {exc}") from exc


def sentence_transformer_embedder() -> Embedder:
    """The shared sentence-transformers model (loads it on first use). Blocking."""
    from app.services.nlp.embedding_service import MODEL_NAME, get_model

    model = get_model()
    if model is None:
        raise EmbedderUnavailableError(f"the embedding model {MODEL_NAME} cannot be loaded")
    return SentenceTransformerEmbedder(model, MODEL_NAME)
