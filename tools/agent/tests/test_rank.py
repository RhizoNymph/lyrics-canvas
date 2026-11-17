"""The fractional-indexing port: known values, ordering, validation, and the shared vectors if present."""

from __future__ import annotations

import json
import random
from pathlib import Path

import pytest

from lcxlib.rank import RankError, key_between, n_keys_between, validate_rank

VECTORS = Path(__file__).resolve().parents[3] / "spec" / "list_rank_vectors.json"


@pytest.mark.parametrize(
    ("a", "b", "key"),
    [
        (None, None, "a0"),
        (None, "a0", "Zz"),
        ("a0", None, "a1"),
        ("a0", "a1", "a0V"),
        ("a0V", "a1", "a0l"),
        ("Zz", "a0", "ZzV"),
        ("Zz", "a1", "a0"),
        (None, "Y00", "Xzzz"),
        ("bzz", None, "c000"),
        ("a0", "a0V", "a0G"),
        ("a0", "a0G", "a08"),
        ("b125", "b129", "b127"),
        ("a0", "a1V", "a1"),
        ("Zz", "a01", "a0"),
        (None, "a0V", "a0"),
        (None, "b999", "b99"),
        (None, "A00000000000000000000000000", None),
        (None, "A000000000000000000000000001", "A000000000000000000000000000V"),
        ("zzzzzzzzzzzzzzzzzzzzzzzzzzy", None, "zzzzzzzzzzzzzzzzzzzzzzzzzzz"),
        ("zzzzzzzzzzzzzzzzzzzzzzzzzzz", None, "zzzzzzzzzzzzzzzzzzzzzzzzzzzV"),
        ("a00", None, None),
        ("a00", "a1", None),
        ("0", "1", None),
        ("a1", "a0", None),
    ],
)
def test_key_between_matches_fractional_indexing(a: str | None, b: str | None, key: str | None) -> None:
    if key is None:
        with pytest.raises(RankError):
            key_between(a, b)
    else:
        assert key_between(a, b) == key


@pytest.mark.parametrize(
    ("a", "b", "n", "keys"),
    [
        (None, None, 5, ["a0", "a1", "a2", "a3", "a4"]),
        ("a4", None, 10, ["a5", "a6", "a7", "a8", "a9", "aA", "aB", "aC", "aD", "aE"]),
        (None, "a0", 5, ["Zv", "Zw", "Zx", "Zy", "Zz"]),
        ("a0", "a2", 20, ["a04", "a08", "a0G", "a0K", "a0O", "a0V", "a0Z", "a0d", "a0l", "a0t", "a1", "a14",
                          "a18", "a1G", "a1O", "a1V", "a1Z", "a1d", "a1l", "a1t"]),
        (None, None, 0, []),
    ],
)
def test_n_keys_between(a: str | None, b: str | None, n: int, keys: list[str]) -> None:
    assert n_keys_between(a, b, n) == keys


def test_n_keys_are_strictly_ascending_by_byte_order() -> None:
    keys = n_keys_between(None, None, 500)
    assert keys == sorted(keys) and len(set(keys)) == 500
    assert all(validate_rank(k) == k for k in keys)


def test_random_insertions_stay_ordered() -> None:
    rng = random.Random(7)
    keys = [key_between(None, None)]
    for _ in range(400):
        i = rng.randint(0, len(keys))
        a = keys[i - 1] if i > 0 else None
        b = keys[i] if i < len(keys) else None
        keys.insert(i, key_between(a, b))
    assert keys == sorted(keys) and len(set(keys)) == len(keys)


@pytest.mark.parametrize("bad", ["", "a", "a00", "A00000000000000000000000000", "a0!", "b1", " a0", "a" + "1" * 64])
def test_validate_rank_rejects(bad: str) -> None:
    with pytest.raises(RankError):
        validate_rank(bad)


@pytest.mark.parametrize("good", ["a0", "Zz", "a0V", "b00", "zzzzzzzzzzzzzzzzzzzzzzzzzzz", "a0" + "V" * 62])
def test_validate_rank_accepts(good: str) -> None:
    assert validate_rank(good) == good


@pytest.mark.skipif(not VECTORS.exists(), reason="spec/list_rank_vectors.json comes with the list-order backend")
def test_shared_vectors() -> None:
    vectors = json.loads(VECTORS.read_text())
    for v in vectors["between"]:
        assert key_between(v["a"], v["b"]) == v["key"], v
    for v in vectors["n_between"]:
        assert n_keys_between(v["a"], v["b"], v["n"]) == v["keys"], v
    for bad in vectors["invalid"]:
        with pytest.raises(RankError):
            validate_rank(bad)
