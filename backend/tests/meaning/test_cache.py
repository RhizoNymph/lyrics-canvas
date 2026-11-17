import numpy as np
import pytest

from app.services.meaning.cache import CACHE_ENTRIES, VectorLRU


def _row(value: float) -> np.ndarray:
    return np.full(4, value, dtype=np.float32)


def test_default_size():
    assert VectorLRU().max_entries == CACHE_ENTRIES == 20_000


def test_get_many_returns_only_hits():
    cache = VectorLRU(3)
    cache.put_many({"a": _row(1), "b": _row(2)})
    found = cache.get_many(["a", "x", "b"])
    assert set(found) == {"a", "b"}
    assert found["b"][0] == 2


def test_least_recently_used_is_evicted_first():
    cache = VectorLRU(2)
    cache.put_many({"a": _row(1), "b": _row(2)})
    cache.get_many(["a"])  # a is now the most recent
    cache.put_many({"c": _row(3)})
    assert "a" in cache and "c" in cache and "b" not in cache
    assert len(cache) == 2


def test_one_put_larger_than_the_cache_keeps_the_last_ones():
    cache = VectorLRU(2)
    cache.put_many({str(i): _row(i) for i in range(5)})
    assert len(cache) == 2 and "3" in cache and "4" in cache


def test_rows_are_copied_and_read_only():
    cache = VectorLRU(2)
    source = _row(1)
    cache.put_many({"a": source})
    source[0] = 9
    stored = cache.get_many(["a"])["a"]
    assert stored[0] == 1
    with pytest.raises(ValueError):
        stored[0] = 5


def test_size_must_be_positive():
    with pytest.raises(ValueError):
        VectorLRU(0)
