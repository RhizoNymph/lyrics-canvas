"""Related words by meaning (``GET /api/word-bank/related``; docs/features/word_bank.md#related-words).

The vocabulary is the plain common words of the rhyme dictionary (``dictionary_entries(include_rare=False)``),
sorted. Each word is embedded with the sentence-transformers model (all-MiniLM-L6-v2, through
app/services/nlp/embedding_service.py) and L2-normalized into a float32 matrix; a query is
embedded the same way and ranked by cosine (a dot product), ties by word.

``RelatedWords`` holds the process-wide index. The build runs once per process in a worker thread
(``asyncio.to_thread``), on the first search or at startup (``RELATED_WORDS_WARMUP``). Until it
finishes, searches raise RelatedWordsWarmingError (503 + Retry-After); when the model cannot be
loaded they raise RelatedWordsUnavailableError (503), for the rest of the process. The state is
a closed set of frozen dataclasses changed only on the event loop, with the start guarded by an
``asyncio.Lock`` and the end signalled by an ``asyncio.Event``.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from collections.abc import Callable, Mapping, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal, Protocol

import numpy as np

from app.services.word_bank.errors import RelatedWordsUnavailableError, RelatedWordsWarmingError
from app.services.word_bank.related_cache import cache_path, load_matrix, save_matrix

logger = logging.getLogger(__name__)

ENCODE_BATCH = 256


class Encoder(Protocol):
    """Texts to embedding rows (any float dtype, not necessarily normalized)."""

    @property
    def model_name(self) -> str: ...

    def encode(self, texts: Sequence[str]) -> np.ndarray: ...


EncoderFactory = Callable[[], Encoder]  # raises RelatedWordsUnavailableError


@dataclass(frozen=True, slots=True)
class RelatedHit:
    text: str
    similarity: float


def _normalized(rows: np.ndarray, expected: int) -> np.ndarray:
    matrix = np.asarray(rows, dtype=np.float32)
    if matrix.ndim != 2 or matrix.shape[0] != expected:
        raise RelatedWordsUnavailableError(f"the embedding model returned shape {matrix.shape} for {expected} texts")
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    return np.divide(matrix, norms, out=np.zeros_like(matrix), where=norms > 0)


@dataclass(frozen=True, slots=True)
class VocabularyIndex:
    """Sorted vocabulary words and their L2-normalized float32 embeddings (one row per word)."""

    words: tuple[str, ...]
    matrix: np.ndarray
    encoder: Encoder
    _positions: dict[str, int] = field(default_factory=dict, compare=False, repr=False)

    def __post_init__(self) -> None:
        self._positions.update({w: i for i, w in enumerate(self.words)})

    def search(self, query: str, limit: int) -> list[RelatedHit]:
        """The ``limit`` words most similar to ``query`` (excluding the query itself, ignoring
        case), by cosine desc then word. CPU-bound: call it from a worker thread."""
        vector = _normalized(self.encoder.encode([query]), 1)[0]
        sims = self.matrix @ vector
        excluded = self._positions.get(query.lower())
        order = np.argsort(-sims, kind="stable")  # stable: ties keep the sorted word order
        hits: list[RelatedHit] = []
        for position in order.tolist():
            if position == excluded:
                continue
            hits.append(RelatedHit(text=self.words[position], similarity=float(sims[position])))
            if len(hits) == limit:
                break
        return hits


CacheOutcome = Literal["hit", "miss"]


@dataclass(frozen=True, slots=True)
class BuiltIndex:
    index: VocabularyIndex
    cache: CacheOutcome
    seconds: float


def build_vocabulary_index(encoder_factory: EncoderFactory, words: Sequence[str], cache_dir: Path) -> BuiltIndex:
    """Load the model, then the matrix from the disk cache or by encoding every word (saved for
    next time). Blocking; raises RelatedWordsUnavailableError."""
    started = time.perf_counter()
    encoder = encoder_factory()
    vocabulary = tuple(words)
    path = cache_path(cache_dir, encoder.model_name, vocabulary)
    matrix = load_matrix(path, rows=len(vocabulary))
    outcome: CacheOutcome = "hit"
    if matrix is None:
        outcome = "miss"
        matrix = _normalized(encoder.encode(list(vocabulary)), len(vocabulary))
        save_matrix(path, matrix)
    seconds = time.perf_counter() - started
    logger.info(
        "related_words_index_built words=%d seconds=%.2f cache=%s model=%s", len(vocabulary), seconds, outcome, encoder.model_name
    )
    return BuiltIndex(index=VocabularyIndex(vocabulary, matrix, encoder), cache=outcome, seconds=seconds)


# ---- the process-wide holder ----


@dataclass(frozen=True, slots=True)
class _Idle:
    pass


@dataclass(frozen=True, slots=True)
class _Building:
    task: asyncio.Task[None]


@dataclass(frozen=True, slots=True)
class _Ready:
    index: VocabularyIndex


@dataclass(frozen=True, slots=True)
class _Unavailable:
    reason: str


_State = _Idle | _Building | _Ready | _Unavailable

IndexBuilder = Callable[[], VocabularyIndex]  # blocking; raises RelatedWordsUnavailableError


class RelatedWords:
    def __init__(self, build: IndexBuilder) -> None:
        self._build = build
        self._state: _State = _Idle()
        self._start_lock = asyncio.Lock()
        self._finished = asyncio.Event()

    @property
    def ready(self) -> bool:
        return isinstance(self._state, _Ready)

    async def start(self) -> None:
        """Start the build if nothing has started it yet (one build per process)."""
        async with self._start_lock:
            if isinstance(self._state, _Idle):
                self._state = _Building(asyncio.get_running_loop().create_task(self._run()))
                logger.info("related_words_index_building")

    async def _run(self) -> None:
        try:
            index = await asyncio.to_thread(self._build)
        except RelatedWordsUnavailableError as exc:
            self._state = _Unavailable(exc.message)
            logger.error("related_words_unavailable reason=%s", exc.message)
        else:
            self._state = _Ready(index)
        finally:
            self._finished.set()

    async def wait(self) -> None:
        """Wait for a started build to end (a no-op when none was started)."""
        if not isinstance(self._state, _Idle):
            await self._finished.wait()

    def _settle(self) -> _State:
        """The state, with a build task that died of an unexpected error turned into _Unavailable."""
        state = self._state
        if isinstance(state, _Building) and state.task.done():
            error = state.task.exception()
            reason = f"the related-word index build failed: {type(error).__name__}"
            logger.error("related_words_unavailable reason=%s error=%s", reason, error)
            self._state = state = _Unavailable(reason)
        return state

    async def search(self, query: str, limit: int) -> list[RelatedHit]:
        await self.start()
        match self._settle():
            case _Ready(index=index):
                return await asyncio.to_thread(index.search, query, limit)
            case _Unavailable(reason=reason):
                raise RelatedWordsUnavailableError(f"related words are unavailable: {reason}")
            case _:
                raise RelatedWordsWarmingError("the related-word index is being built; retry shortly")


# ---- production wiring ----


def resolve_cache_dir(setting: str, environ: Mapping[str, str]) -> Path:
    """``RELATED_WORDS_CACHE_DIR``, else ``$HF_HOME/lyrics-canvas``, else ``~/.cache/lyrics-canvas``."""
    if setting:
        return Path(setting)
    hf_home = environ.get("HF_HOME")
    if hf_home:
        return Path(hf_home) / "lyrics-canvas"
    return Path.home() / ".cache" / "lyrics-canvas"


def default_vocabulary() -> list[str]:
    from app.services.phonetics.sources.dictionary import dictionary_entries

    return sorted({word for word, _ in dictionary_entries(include_rare=False)})


class _SentenceTransformerEncoder:
    def __init__(self, model: object, model_name: str) -> None:
        self._model = model
        self.model_name = model_name

    def encode(self, texts: Sequence[str]) -> np.ndarray:
        try:
            return self._model.encode(list(texts), convert_to_numpy=True, batch_size=ENCODE_BATCH)  # type: ignore[attr-defined]
        except (RuntimeError, ValueError) as exc:
            raise RelatedWordsUnavailableError(f"the embedding model failed: {exc}") from exc


def default_encoder() -> Encoder:
    from app.services.nlp.embedding_service import MODEL_NAME, get_model

    model = get_model()
    if model is None:
        raise RelatedWordsUnavailableError(f"the embedding model {MODEL_NAME} cannot be loaded")
    return _SentenceTransformerEncoder(model, MODEL_NAME)


def default_builder(cache_setting: str) -> IndexBuilder:
    def build() -> VocabularyIndex:
        cache_dir = resolve_cache_dir(cache_setting, os.environ)
        return build_vocabulary_index(default_encoder, default_vocabulary(), cache_dir).index

    return build
