"""A deterministic fake embedder: no model download in unit tests.

Each known word adds 1.0 on its concept axis; any other word adds a small fixed vector derived
from its sha256, so unrelated texts are nearly orthogonal to the concepts and the result never
depends on the run.
"""

from __future__ import annotations

import hashlib
from collections.abc import Mapping, Sequence

import numpy as np

from app.services.meaning.errors import EmbedderUnavailableError, EmbeddingFailedError
from app.services.meaning.service import MeaningService

DIM = 16
ANIMAL, CLOTHES, NIGHT, NATION, WATER = 0, 1, 2, 3, 4
AXES: dict[str, int] = {
    **dict.fromkeys(("cat", "cats", "bat", "dog", "rat", "kitten", "animals", "black", "acrobat", "combat"), ANIMAL),
    **dict.fromkeys(("hat", "cap", "coat", "wearing", "mat"), CLOTHES),
    **dict.fromkeys(("night", "light", "tonight", "bright", "dark", "moon", "sleeping"), NIGHT),
    **dict.fromkeys(("nation", "station", "country", "across"), NATION),
    **dict.fromkeys(("rain", "sea", "river", "weather", "steady"), WATER),
}
NOISE = 0.05


def _noise(word: str) -> np.ndarray:
    digest = hashlib.sha256(word.encode("utf-8")).digest()
    return (np.frombuffer(digest[:DIM], dtype=np.uint8).astype(np.float32) / 255.0 - 0.5) * NOISE


def fake_vector(text: str, axes: Mapping[str, int] = AXES) -> np.ndarray:
    row = np.zeros(DIM, dtype=np.float32)
    for raw in text.lower().split():
        word = "".join(ch for ch in raw if ch.isalpha())
        if not word:
            continue
        axis = axes.get(word)
        if axis is not None:
            row[axis] += 1.0
        row += _noise(word)
    return row


class FakeEmbedder:
    def __init__(self, model_name: str = "fake-minilm", fail: bool = False, bad_shape: bool = False) -> None:
        self.model_name = model_name
        self.calls: list[list[str]] = []
        self.fail = fail
        self.bad_shape = bad_shape

    @property
    def encoded(self) -> list[str]:
        return [t for call in self.calls for t in call]

    def encode(self, texts: Sequence[str]) -> np.ndarray:
        self.calls.append(list(texts))
        if self.fail:
            raise EmbeddingFailedError("the fake model failed")
        rows = np.stack([fake_vector(t) for t in texts]) if texts else np.zeros((0, DIM), dtype=np.float32)
        return rows[:-1] if self.bad_shape else rows


class FakeFactory:
    """Counts loads; ``unavailable`` makes every load fail like a missing model."""

    def __init__(self, embedder: FakeEmbedder | None = None, unavailable: bool = False) -> None:
        self.embedder = embedder or FakeEmbedder()
        self.unavailable = unavailable
        self.loads = 0

    def __call__(self) -> FakeEmbedder:
        self.loads += 1
        if self.unavailable:
            raise EmbedderUnavailableError("the fake model cannot be loaded")
        return self.embedder


class FakeVocabulary:
    def __init__(self, words: Sequence[str], model_name: str = "fake-minilm") -> None:
        self.rows = {w: _unit(fake_vector(w)) for w in words}
        self.model_name = model_name
        self.asked: list[list[str]] = []

    def vocabulary_vectors(self, words: Sequence[str], model_name: str) -> dict[str, np.ndarray]:
        self.asked.append(list(words))
        if model_name != self.model_name:
            return {}
        return {w: self.rows[w] for w in words if w in self.rows}


def _unit(row: np.ndarray) -> np.ndarray:
    norm = float(np.linalg.norm(row))
    return row / norm if norm else row


def cosine(a: str, b: str) -> float:
    return float(_unit(fake_vector(a)) @ _unit(fake_vector(b)))


def meaning_service(**kwargs) -> tuple[MeaningService, FakeFactory]:
    factory = FakeFactory(**kwargs)
    return MeaningService(factory), factory


class FakeStore:
    def __init__(self, rows: Mapping[str, np.ndarray] | None = None) -> None:
        self.rows = dict(rows or {})
        self.asked: list[list[str]] = []

    async def current(self, snippet_ids: Sequence[str]) -> dict[str, np.ndarray]:
        self.asked.append(list(snippet_ids))
        return {s: self.rows[s] for s in snippet_ids if s in self.rows}
