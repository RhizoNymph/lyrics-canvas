import time

import pytest

from app.services.phonetics import pronounce
from app.services.phonetics.sources.dictionary import DictionarySource

BUDGET_MS = 200.0


@pytest.fixture(scope="module")
def dictionary() -> DictionarySource:
    src = DictionarySource()
    src.index(include_rare=False)  # warm
    return src


@pytest.mark.timing
@pytest.mark.parametrize("word,k", [("orange", 2), ("nation", 2), ("cat", 1), ("letter", 2), ("attention", 3), ("light", 1)])
def test_warm_dictionary_query_within_budget(dictionary: DictionarySource, word: str, k: int):
    idx = dictionary.index(include_rare=False)
    idx.query(pronounce(word), k=k, min_score=0.5, limit=100)  # warm caches for this tail
    timings = []
    for _ in range(3):
        start = time.perf_counter()
        hits = idx.query(pronounce(word), k=k, min_score=0.5, limit=100)
        timings.append((time.perf_counter() - start) * 1000)
    assert hits
    assert min(timings) < BUDGET_MS, f"{word}@{k}: {timings} ms"


def test_dictionary_index_excludes_variants_and_rare_words(dictionary: DictionarySource):
    idx = dictionary.index(include_rare=False)
    assert "dict:orange" in idx
    assert "dict:orange(2)" not in idx
    assert "dict:abattoir" not in idx
    assert len(idx) > 10_000
