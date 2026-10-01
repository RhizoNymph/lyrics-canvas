import itertools

import pytest

from app.services.phonetics.distance import CONSONANTS, VOWELS, is_vowel, phone_distance, vowel_neighbours


def test_every_phone_is_classified():
    assert "AH" in VOWELS and "K" in CONSONANTS
    assert is_vowel("AH") and not is_vowel("K")
    assert len(VOWELS) == 15
    assert len(CONSONANTS) == 24


@pytest.mark.parametrize("a,b", list(itertools.product(sorted(VOWELS), sorted(VOWELS)))[:60])
def test_vowel_distance_is_metric_like(a, b):
    d = phone_distance(a, b)
    assert 0.0 <= d <= 1.0
    assert d == phone_distance(b, a)
    if a == b:
        assert d == 0.0
    else:
        assert d > 0.0


def test_vowel_vs_consonant_is_maximal():
    assert phone_distance("AH", "K") == 1.0
    assert phone_distance("T", "IY") == 1.0


def test_consonant_distance_ordering():
    assert phone_distance("T", "T") == 0.0
    assert phone_distance("T", "D") < phone_distance("T", "K")
    assert phone_distance("S", "Z") < phone_distance("S", "T")
    assert phone_distance("M", "N") < phone_distance("M", "S")
    assert phone_distance("P", "B") < phone_distance("P", "L")


def test_vowel_distance_ordering_follows_articulation():
    assert phone_distance("IH", "IY") < phone_distance("IH", "AA")
    assert phone_distance("EH", "AE") < phone_distance("EH", "UW")
    assert phone_distance("AA", "AO") < phone_distance("AA", "IY")
    assert phone_distance("AH", "ER") <= 0.25
    assert phone_distance("IH", "IY") <= 0.3
    assert phone_distance("AH", "IH") <= 0.35


def test_vowel_neighbours_include_self_and_are_within_threshold():
    for v in VOWELS:
        neighbours = vowel_neighbours(v)
        assert v in neighbours
        for n in neighbours:
            assert phone_distance(v, n) <= 0.35
    assert "IY" in vowel_neighbours("IH")
    assert "AA" in vowel_neighbours("AO")
    assert "IY" not in vowel_neighbours("AA")
