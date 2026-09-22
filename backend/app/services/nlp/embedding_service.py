import logging
from functools import lru_cache

import numpy as np

logger = logging.getLogger(__name__)

_model = None


def get_model():
    global _model
    if _model is None:
        try:
            from sentence_transformers import SentenceTransformer
            _model = SentenceTransformer("all-MiniLM-L6-v2")
            logger.info("Loaded sentence-transformers model: all-MiniLM-L6-v2")
        except Exception as e:
            logger.warning(f"Could not load sentence-transformers model: {e}")
            _model = None
    return _model


def compute_embedding(text: str) -> list[float] | None:
    model = get_model()
    if model is None:
        return None
    embedding = model.encode(text, convert_to_numpy=True)
    return embedding.tolist()


def compute_embeddings_batch(texts: list[str]) -> list[list[float]] | None:
    model = get_model()
    if model is None:
        return None
    embeddings = model.encode(texts, convert_to_numpy=True, batch_size=32)
    return embeddings.tolist()
