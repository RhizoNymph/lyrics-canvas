"""The related-word index with a fake encoder (the real model is never loaded here)."""

from __future__ import annotations

import asyncio
import logging
import threading
from collections.abc import Sequence
from pathlib import Path

import numpy as np
import pytest

from app.services.word_bank.errors import RelatedWordsUnavailableError, RelatedWordsWarmingError
from app.services.word_bank.related import (
    RelatedWords,
    VocabularyIndex,
    build_vocabulary_index,
    resolve_cache_dir,
)
from app.services.word_bank.related_cache import cache_path, load_matrix, save_matrix, vocabulary_digest

VOCAB = ("bright", "dark", "day", "light", "moon", "night", "sun")
# 3-d toy space: axis 0 "night-ish", axis 1 "day-ish", axis 2 "other"
VECTORS = {
    "night": [1.0, 0.0, 0.0],
    "dark": [0.9, 0.1, 0.0],
    "moon": [0.8, 0.0, 0.2],
    "day": [0.0, 1.0, 0.0],
    "sun": [0.1, 0.9, 0.0],
    "light": [0.2, 0.8, 0.0],
    "bright": [0.2, 0.8, 0.0],  # ties with light
    "evening": [0.95, 0.05, 0.0],
    "Night": [1.0, 0.0, 0.0],
    "zero": [0.0, 0.0, 0.0],
}


class FakeEncoder:
    model_name = "fake/encoder-v1"

    def __init__(self) -> None:
        self.calls: list[int] = []

    def encode(self, texts: Sequence[str]) -> np.ndarray:
        self.calls.append(len(texts))
        # unnormalized on purpose: the index must L2-normalize
        return np.array([[3.0 * x for x in VECTORS.get(t, [0.0, 0.0, 1.0])] for t in texts], dtype=np.float32)


def build(tmp_path: Path, encoder: FakeEncoder | None = None, vocab=VOCAB):
    enc = encoder or FakeEncoder()
    return build_vocabulary_index(lambda: enc, list(vocab), tmp_path), enc


# ---- the index ----


def test_matrix_is_float32_and_normalized(tmp_path):
    built, _ = build(tmp_path)
    m = built.index.matrix
    assert m.dtype == np.float32
    assert m.shape == (len(VOCAB), 3)
    assert np.allclose(np.linalg.norm(m, axis=1), 1.0, atol=1e-6)
    assert built.index.words == VOCAB


def test_search_sorts_by_cosine_and_excludes_the_query(tmp_path):
    built, _ = build(tmp_path)
    hits = built.index.search("night", 10)
    assert [h.text for h in hits][:2] == ["dark", "moon"]
    assert "night" not in [h.text for h in hits]
    sims = [h.similarity for h in hits]
    assert sims == sorted(sims, reverse=True)
    assert hits[0].similarity == pytest.approx(0.9 / np.linalg.norm([0.9, 0.1, 0.0]), abs=1e-6)


def test_query_exclusion_ignores_case(tmp_path):
    built, _ = build(tmp_path)
    assert "night" not in [h.text for h in built.index.search("Night", 10)]


def test_query_not_in_vocabulary(tmp_path):
    built, _ = build(tmp_path)
    hits = built.index.search("evening", 3)
    assert [h.text for h in hits] == ["night", "dark", "moon"]


def test_limit_and_ties_break_by_word(tmp_path):
    built, _ = build(tmp_path)
    hits = built.index.search("day", 3)
    assert [h.text for h in hits] == ["sun", "bright", "light"]
    assert hits[1].similarity == hits[2].similarity
    assert len(built.index.search("day", 100)) == len(VOCAB) - 1


def test_zero_vector_does_not_divide_by_zero(tmp_path):
    built, _ = build(tmp_path, vocab=("night", "zero"))
    hits = built.index.search("night", 5)
    assert [h.text for h in hits] == ["zero"]
    assert hits[0].similarity == 0.0


# ---- disk cache ----


def test_second_build_hits_the_disk_cache(tmp_path):
    first, enc1 = build(tmp_path)
    assert first.cache == "miss"
    assert enc1.calls == [len(VOCAB)]
    second, enc2 = build(tmp_path)
    assert second.cache == "hit"
    assert enc2.calls == []  # no vocabulary encode
    assert np.array_equal(first.index.matrix, second.index.matrix)


def test_cache_key_is_model_plus_vocabulary_digest(tmp_path):
    a = cache_path(tmp_path, "fake/encoder-v1", ["a", "b"])
    assert a.parent == tmp_path
    assert vocabulary_digest(["a", "b"]) in a.name
    assert "/" not in a.name.removeprefix(str(tmp_path))
    assert a != cache_path(tmp_path, "fake/encoder-v1", ["a", "c"])
    assert a != cache_path(tmp_path, "other-model", ["a", "b"])
    assert vocabulary_digest(["a", "b"]) != vocabulary_digest(["ab"])
    assert len(vocabulary_digest(["a"])) == 64


def test_vocabulary_change_misses(tmp_path):
    build(tmp_path)
    again, enc = build(tmp_path, vocab=VOCAB + ("zero",))
    assert again.cache == "miss"
    assert enc.calls == [len(VOCAB) + 1]


def test_corrupt_cache_is_rebuilt(tmp_path, caplog):
    first, _ = build(tmp_path)
    path = cache_path(tmp_path, FakeEncoder.model_name, list(VOCAB))
    path.write_bytes(b"not a numpy file")
    with caplog.at_level(logging.WARNING):
        again, enc = build(tmp_path)
    assert again.cache == "miss"
    assert enc.calls == [len(VOCAB)]
    assert np.array_equal(again.index.matrix, first.index.matrix)
    assert "related_words_cache_unreadable" in caplog.text


def test_wrong_shape_cache_is_rebuilt(tmp_path):
    path = cache_path(tmp_path, FakeEncoder.model_name, list(VOCAB))
    save_matrix(path, np.zeros((2, 3), dtype=np.float32))
    again, _ = build(tmp_path)
    assert again.cache == "miss"


def test_load_matrix_missing_and_roundtrip(tmp_path):
    path = tmp_path / "x.npy"
    assert load_matrix(path, rows=2) is None
    m = np.eye(2, dtype=np.float32)
    save_matrix(path, m)
    assert np.array_equal(load_matrix(path, rows=2), m)
    assert load_matrix(path, rows=3) is None
    assert not list(tmp_path.glob("*.tmp*"))


def test_unwritable_cache_dir_still_builds(tmp_path, caplog):
    blocker = tmp_path / "file"
    blocker.write_text("x")
    with caplog.at_level(logging.WARNING):
        built, _ = build(blocker / "sub")  # cannot mkdir under a file
    assert built.cache == "miss"
    assert "related_words_cache_unwritable" in caplog.text


def test_build_logs_structured_event(tmp_path, caplog):
    with caplog.at_level(logging.INFO):
        build(tmp_path)
        build(tmp_path)
    lines = [r.getMessage() for r in caplog.records if "related_words_index_built" in r.getMessage()]
    assert len(lines) == 2
    assert f"words={len(VOCAB)}" in lines[0] and "cache=miss" in lines[0] and "seconds=" in lines[0]
    assert "cache=hit" in lines[1]


def test_unavailable_encoder_raises_typed_error(tmp_path):
    def broken():
        raise RelatedWordsUnavailableError("no model")

    with pytest.raises(RelatedWordsUnavailableError):
        build_vocabulary_index(broken, list(VOCAB), tmp_path)


def test_wrong_encoder_output_shape_is_unavailable(tmp_path):
    class Bad(FakeEncoder):
        def encode(self, texts):
            return np.zeros((len(texts) + 1, 3), dtype=np.float32)

    with pytest.raises(RelatedWordsUnavailableError):
        build(tmp_path, Bad())


# ---- cache dir resolution ----


def test_resolve_cache_dir():
    assert resolve_cache_dir("/data/x", {"HF_HOME": "/hf"}) == Path("/data/x")
    assert resolve_cache_dir("", {"HF_HOME": "/hf"}) == Path("/hf/lyrics-canvas")
    assert resolve_cache_dir("", {}) == Path.home() / ".cache" / "lyrics-canvas"


# ---- the process-wide holder ----


class GatedBuilder:
    """A builder that blocks in its worker thread until released; counts builds."""

    def __init__(self, tmp_path: Path, fail: bool = False) -> None:
        self.tmp_path = tmp_path
        self.release = threading.Event()
        self.builds = 0
        self.threads: list[str] = []
        self.fail = fail

    def __call__(self) -> VocabularyIndex:
        self.builds += 1
        self.threads.append(threading.current_thread().name)
        self.release.wait(5)
        if self.fail:
            raise RelatedWordsUnavailableError("model cannot be loaded")
        return build_vocabulary_index(FakeEncoder, list(VOCAB), self.tmp_path).index


async def test_first_search_starts_the_build_and_warms(tmp_path):
    builder = GatedBuilder(tmp_path)
    related = RelatedWords(builder)
    with pytest.raises(RelatedWordsWarmingError) as info:
        await related.search("night", 5)
    assert info.value.retry_after == 10
    with pytest.raises(RelatedWordsWarmingError):
        await related.search("night", 5)
    builder.release.set()
    await related.wait()
    hits = await related.search("night", 2)
    assert [h.text for h in hits] == ["dark", "moon"]
    assert builder.builds == 1
    assert builder.threads[0] != threading.main_thread().name  # built off the event loop


async def test_concurrent_requests_build_once(tmp_path):
    builder = GatedBuilder(tmp_path)
    related = RelatedWords(builder)
    results = await asyncio.gather(*(related.search("night", 5) for _ in range(20)), return_exceptions=True)
    assert all(isinstance(r, RelatedWordsWarmingError) for r in results)
    await asyncio.gather(related.start(), related.start())
    builder.release.set()
    await related.wait()
    assert builder.builds == 1
    assert related.ready


async def test_start_warms_without_a_request(tmp_path):
    builder = GatedBuilder(tmp_path)
    builder.release.set()
    related = RelatedWords(builder)
    assert not related.ready
    await related.start()
    await related.wait()
    assert related.ready
    assert [h.text for h in await related.search("day", 1)] == ["sun"]


async def test_model_failure_is_unavailable_and_sticky(tmp_path):
    builder = GatedBuilder(tmp_path, fail=True)
    builder.release.set()
    related = RelatedWords(builder)
    await related.start()
    await related.wait()
    for _ in range(2):
        with pytest.raises(RelatedWordsUnavailableError):
            await related.search("night", 5)
    assert builder.builds == 1


async def test_unexpected_build_crash_is_unavailable(tmp_path):
    def crash() -> VocabularyIndex:
        raise MemoryError("boom")

    related = RelatedWords(crash)
    await related.start()
    await related.wait()
    with pytest.raises(RelatedWordsUnavailableError):
        await related.search("night", 5)


async def test_wait_before_start_is_a_noop(tmp_path):
    related = RelatedWords(GatedBuilder(tmp_path))
    await related.wait()
    assert not related.ready
