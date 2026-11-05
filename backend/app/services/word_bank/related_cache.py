"""Disk cache of the related-word vocabulary matrix (float32 ``.npy``).

The file name carries the model name and the sha256 of the vocabulary, so a new model or a changed
dictionary never reads a stale matrix. Writes go to a temp file and are renamed into place. A
missing, unreadable or mis-shaped file is a miss; an unwritable directory only costs the cache.
"""

from __future__ import annotations

import hashlib
import logging
import os
import re
import tempfile
from collections.abc import Sequence
from pathlib import Path

import numpy as np

logger = logging.getLogger(__name__)

_UNSAFE = re.compile(r"[^A-Za-z0-9._-]+")


def vocabulary_digest(words: Sequence[str]) -> str:
    """sha256 (hex) of the words joined by newlines (words never contain one)."""
    return hashlib.sha256("\n".join(words).encode("utf-8")).hexdigest()


def cache_path(cache_dir: Path, model_name: str, words: Sequence[str]) -> Path:
    model = _UNSAFE.sub("_", model_name)
    return cache_dir / f"related-words-{model}-{vocabulary_digest(words)}.npy"


def load_matrix(path: Path, rows: int) -> np.ndarray | None:
    """The cached matrix when it exists and is a 2-d float32 array of ``rows`` rows, else None."""
    if not path.is_file():
        return None
    try:
        matrix = np.load(path, allow_pickle=False)
    except (OSError, ValueError, EOFError) as exc:
        logger.warning("related_words_cache_unreadable path=%s error=%s", path, exc)
        return None
    if matrix.ndim != 2 or matrix.shape[0] != rows or matrix.dtype != np.float32:
        logger.warning("related_words_cache_mismatch path=%s shape=%s dtype=%s rows=%d", path, matrix.shape, matrix.dtype, rows)
        return None
    return matrix


def save_matrix(path: Path, matrix: np.ndarray) -> None:
    """Write atomically; failures are logged (the in-memory index is still used)."""
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=path.name, suffix=".tmp")
        try:
            with os.fdopen(fd, "wb") as handle:
                np.save(handle, matrix, allow_pickle=False)
            os.replace(tmp, path)
        finally:
            if os.path.exists(tmp):
                os.unlink(tmp)
    except OSError as exc:
        logger.warning("related_words_cache_unwritable path=%s error=%s", path, exc)
