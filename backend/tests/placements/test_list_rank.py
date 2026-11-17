"""The fractional-index port (app/services/placements/list_rank.py) against values worked out by
hand from the fractional-indexing 3.2.0 algorithm, independent of spec/list_rank_vectors.json."""

from __future__ import annotations

import pytest

from app.services.placements.errors import InvalidListRankError, PlacementErrorCode
from app.services.placements.list_rank import (
    DIGITS,
    MAX_RANK_LENGTH,
    RankGenerationError,
    key_between,
    n_keys_between,
    validate_rank,
)

SMALLEST = "A" + "0" * 26
LARGEST = "z" * 27


@pytest.mark.parametrize(
    ("a", "b", "expected"),
    [
        (None, None, "a0"),
        (None, "a0", "Zz"),
        ("a0", None, "a1"),
        ("a0", "a1", "a0V"),
        ("a1", "a2", "a1V"),
        ("a0V", "a1", "a0l"),
        ("Zz", "a0", "ZzV"),
        ("Zz", "a1", "a0"),
        (None, "Y00", "Xzzz"),
        ("bzz", None, "c000"),
        ("a0", "a0V", "a0G"),
        ("a0", "a0G", "a08"),
        # integer part shorter than b: b's integer part is the answer
        (None, "a0V", "a0"),
        (None, "Zz5", "Zz"),
        # rollovers of the integer part
        ("Zz", None, "a0"),
        ("az", None, "b00"),
        (None, "b00", "az"),
        ("Yzz", None, "Z0"),
        ("azz", "b00", "azzV"),
        # fractions with zeros inside
        ("a0", "a01", "a00V"),
        ("a0", "a001", "a000V"),
        ("a5V", "a5W", "a5VV"),
        # the ends of the integer range
        (LARGEST, None, LARGEST + "V"),
        (None, SMALLEST[:-1] + "V", SMALLEST[:-1] + "U"),
        (None, SMALLEST + "1", SMALLEST + "0V"),
    ],
)
def test_key_between_by_hand(a, b, expected):
    assert key_between(a, b) == expected
    if a is not None:
        assert a < expected
    if b is not None:
        assert expected < b


@pytest.mark.parametrize(
    ("a", "b", "n", "expected"),
    [
        (None, None, 0, []),
        (None, None, 1, ["a0"]),
        (None, None, 5, ["a0", "a1", "a2", "a3", "a4"]),
        (None, "a0", 3, ["Zx", "Zy", "Zz"]),
        ("a0", None, 3, ["a1", "a2", "a3"]),
        ("a0", "a1", 5, ["a08", "a0G", "a0V", "a0d", "a0l"]),
        ("Zx", "a2", 4, ["ZxG", "ZxV", "Zy", "Zz"]),
    ],
)
def test_n_keys_between_by_hand(a, b, n, expected):
    assert n_keys_between(a, b, n) == expected


def test_n_keys_between_crosses_integer_rollover():
    keys = n_keys_between(None, None, 70)
    assert keys[:2] == ["a0", "a1"]
    assert keys[61] == "az" and keys[62] == "b00"
    assert keys == sorted(keys) and len(set(keys)) == 70


def test_negative_n_is_rejected():
    with pytest.raises(ValueError):
        n_keys_between(None, None, -1)


@pytest.mark.parametrize(
    ("a", "b"),
    [("a1", "a0"), ("a0", "a0"), ("a0V", "a0V")],
)
def test_out_of_order_bounds_are_rejected(a, b):
    with pytest.raises(RankGenerationError):
        key_between(a, b)


def test_cannot_go_below_the_smallest_integer():
    with pytest.raises(InvalidListRankError):
        key_between(None, SMALLEST)


@pytest.mark.parametrize(
    "key",
    ["", "a", "b0", "a00", "a0V0", "0a", "!a0", "a0 ", "a0!", "a0é", SMALLEST, "a0" + "V" * 63],
)
def test_invalid_keys(key):
    with pytest.raises(InvalidListRankError) as raised:
        validate_rank(key)
    assert raised.value.code is PlacementErrorCode.invalid_list_rank
    assert raised.value.status == 422


@pytest.mark.parametrize("key", ["a0", "Zz", "a0V", "Xzzz", "c000", SMALLEST + "V", LARGEST, "a0" + "V" * 62])
def test_valid_keys(key):
    assert validate_rank(key) == key
    assert len(key) <= MAX_RANK_LENGTH


def test_invalid_bounds_are_rejected_before_generation():
    with pytest.raises(InvalidListRankError):
        key_between("a00", None)
    with pytest.raises(InvalidListRankError):
        key_between(None, "b0")


def test_keys_sort_by_code_point_not_locale():
    # Upper case sorts before lower case: "Zz" < "a0" is the whole point of the encoding.
    keys = n_keys_between(None, "a0", 3) + ["a0"] + n_keys_between("a0", None, 3)
    assert keys == sorted(keys)
    assert DIGITS == "".join(sorted(DIGITS))


def test_repeated_prepend_and_bisect_stay_valid_and_ordered():
    top = "a0"
    for _ in range(200):
        new = key_between(None, top)
        assert validate_rank(new) == new and new < top
        top = new
    low, high = "a0", "a1"
    for step in range(60):
        mid = key_between(low, high)
        assert low < mid < high
        validate_rank(mid)
        if step % 2:
            low = mid
        else:
            high = mid
