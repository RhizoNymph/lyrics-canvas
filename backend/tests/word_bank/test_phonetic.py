"""Bank-word units and the memoized pair scorer (docs/features/word_bank.md#scoring)."""

from __future__ import annotations

import pytest

from app.services.phonetics import DEFAULT_DIALECT_MAP, RhymeIndex, score, unit_from_text
from app.services.phonetics.distance import _TABLE
from app.services.word_bank.phonetic import PairScorer, PhoneticWord, phonetic_words

WORDS = ["night", "light", "fight", "delight", "tonight", "money", "honey", "sunny", "heart", "apart", "cat", "a"]


def units():
    return {w: unit_from_text(w, DEFAULT_DIALECT_MAP) for w in WORDS}


def test_phonetic_words_builds_one_unit_per_word_in_order():
    built = phonetic_words([("1", "Night"), ("2", "broken heart"), ("3", "夜")])
    assert [w.id for w in built] == ["1", "2", "3"]
    assert [w.text for w in built] == ["Night", "broken heart", "夜"]
    assert [w.sort_key for w in built] == ["night", "broken heart", "夜"]
    assert built[0].unit is not None and built[0].unit.rhyme_key
    assert built[1].unit is not None and built[1].unit.syllable_count == 3


def test_unpronounceable_word_has_no_unit():
    (word,) = phonetic_words([("x", "夜")])
    assert isinstance(word, PhoneticWord)
    assert word.unit is None


@pytest.mark.parametrize("k", [1, 2, 3, 4])
def test_directed_matches_rhyme_suggest_index(k):
    """The scorer gives what the rhyme-suggest RhymeIndex gives a candidate for an anchor."""
    u = units()
    index = RhymeIndex(DEFAULT_DIALECT_MAP)
    for w, unit in u.items():
        index.insert(unit)
    scorer = PairScorer(k)
    checked = 0
    for anchor in u.values():
        for hit in index.query(anchor.pronunciation, k=k, min_score=0.3, limit=1000):
            cand = index.get(hit.unit_id)
            got = scorer.directed(anchor, cand)
            assert got.score == pytest.approx(hit.score.score, abs=1e-12)
            assert got.match_depth == hit.score.match_depth
            checked += 1
    assert checked > len(u)


def test_directed_is_score_at_anchor_depth():
    u = units()
    scorer = PairScorer(2)
    # a one-syllable anchor scores at depth 1: delight's extra syllable is not a penalty
    assert scorer.directed(u["night"], u["delight"]).score == score(
        u["night"].effective_pronunciation, u["delight"].effective_pronunciation, 1
    ).score == 1.0
    # a two-syllable anchor against a one-syllable candidate takes the short-side discount
    assert scorer.directed(u["delight"], u["night"]).score == pytest.approx(1 / 1.7)


def test_similarity_is_the_max_of_both_directions():
    u = units()
    scorer = PairScorer(2)
    a, b = u["night"], u["delight"]
    assert scorer.similarity(a, b) == scorer.similarity(b, a) == 1.0
    assert scorer.similarity(u["money"], u["honey"]) == 1.0
    assert scorer.similarity(u["night"], u["money"]) < 0.5


def test_phone_distance_table_is_symmetric():
    # the scorer reuses one direction's score for equal-length tails; that needs this
    assert all(v == _TABLE[(b, a)] for (a, b), v in _TABLE.items())


@pytest.mark.parametrize("k", [1, 2, 3])
def test_equal_length_tails_score_symmetrically(k):
    u = list(units().values())
    for a in u:
        for b in u:
            if min(k, a.syllable_count) == min(k, b.syllable_count):
                fwd = score(a.effective_pronunciation, b.effective_pronunciation, min(k, a.syllable_count))
                back = score(b.effective_pronunciation, a.effective_pronunciation, min(k, b.syllable_count))
                assert (fwd.score, fwd.match_depth) == (back.score, back.match_depth)


def test_scorer_memoizes_syllable_pairs():
    u = units()
    scorer = PairScorer(2)
    scorer.directed(u["night"], u["money"])
    assert scorer.computed == 1  # depth 1: one syllable pair, onset not read
    scorer.directed(u["money"], u["night"])
    assert scorer.computed == 1  # the same pair in the other order
    scorer.directed(u["night"], u["light"])
    assert scorer.computed == 2
    scorer.directed(u["fight"], u["night"])
    assert scorer.computed == 2  # deepest onset not read: fight/night is night/light's AY T pair
    scorer.directed(u["money"], u["honey"])
    assert scorer.computed == 4  # two depths
    scorer.directed(u["honey"], u["money"])
    assert scorer.computed == 4


@pytest.mark.parametrize("k", [1, 2, 3, 4])
def test_directed_equals_engine_score_exactly_on_many_words(k):
    import random

    from app.services.phonetics.sources.dictionary import common_words

    rng = random.Random(k)
    sample = rng.sample(sorted(common_words()), 120) + ["broken heart", "fall apart", "a", "door hinge", "hinge"]
    built = [w.unit for w in phonetic_words([(str(i), t) for i, t in enumerate(sample)]) if w.unit is not None]
    scorer = PairScorer(k)
    for a in built:
        for b in built:
            want = score(a.effective_pronunciation, b.effective_pronunciation, min(k, a.syllable_count))
            got = scorer.directed(a, b)
            assert (got.score, got.match_depth) == (want.score, want.match_depth), (a.text, b.text)
            back = score(b.effective_pronunciation, a.effective_pronunciation, min(k, b.syllable_count))
            assert scorer.similarity(a, b) == max(want.score, back.score)


def test_scorer_rejects_bad_k():
    with pytest.raises(ValueError):
        PairScorer(0)
    with pytest.raises(ValueError):
        PairScorer(5)
