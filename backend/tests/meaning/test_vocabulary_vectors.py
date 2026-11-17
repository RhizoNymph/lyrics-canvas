"""RelatedWords.vocabulary_vectors: dictionary-word rows for meaning order, once the index is built."""

from collections.abc import Sequence

import numpy as np
import pytest

from app.services.word_bank.related import RelatedWords, VocabularyIndex, build_vocabulary_index

VOCAB = ("cat", "hat", "night")


class Encoder:
    model_name = "fake/encoder-v1"

    def encode(self, texts: Sequence[str]) -> np.ndarray:
        return np.array([[float(len(t)), 1.0, 0.0] for t in texts], dtype=np.float32)


def _builder(tmp_path):
    def build() -> VocabularyIndex:
        return build_vocabulary_index(Encoder, list(VOCAB), tmp_path).index

    return build


def test_index_vectors_returns_rows_of_known_words_only(tmp_path):
    index = _builder(tmp_path)()
    rows = index.vectors(["hat", "dog", "night"])
    assert set(rows) == {"hat", "night"}
    assert np.array_equal(rows["night"], index.matrix[2])
    assert float(np.linalg.norm(rows["hat"])) == pytest.approx(1.0)


async def test_nothing_before_the_index_is_ready(tmp_path):
    related = RelatedWords(_builder(tmp_path))
    assert related.vocabulary_vectors(["cat"], "fake/encoder-v1") == {}


async def test_rows_once_ready_for_the_same_model_only(tmp_path):
    related = RelatedWords(_builder(tmp_path))
    await related.start()
    await related.wait()
    assert set(related.vocabulary_vectors(["cat", "zebra"], "fake/encoder-v1")) == {"cat"}
    assert related.vocabulary_vectors(["cat"], "another-model") == {}
