"""Ranking bank words against one anchor (docs/features/word_bank.md#rhymes)."""

from __future__ import annotations

import pytest

from app.services.phonetics import DEFAULT_DIALECT_MAP, unit_from_text
from app.services.word_bank.phonetic import PairScorer, phonetic_words
from app.services.word_bank.rhymes import rank_rhymes


def bank(*texts: str):
    return phonetic_words([(f"id-{i}", t) for i, t in enumerate(texts)])


def anchor(text: str):
    return unit_from_text(text, DEFAULT_DIALECT_MAP)


def texts(matches):
    return [m.word.text for m in matches]


def test_ranks_by_score_then_depth_then_text():
    words = bank("money", "light", "Fight", "delight", "bite", "tonight", "night")
    got = rank_rhymes(anchor("night"), words, PairScorer(2), min_score=0.5, limit=100)
    # perfect one-syllable rhymes tie at 1.0 / depth 1 and sort by lower(text); night itself is
    # a candidate here because nothing excludes it
    perfect = [m for m in got if m.score == 1.0]
    assert texts(perfect) == sorted(texts(perfect), key=str.lower)
    assert "money" not in texts(got)
    scores = [(m.score, m.match_depth) for m in got]
    assert scores == sorted(scores, key=lambda s: (-s[0], -s[1]))


def test_ties_break_on_lower_text_not_input_order():
    words = bank("light", "Bite", "fight")
    got = rank_rhymes(anchor("night"), words, PairScorer(2), min_score=0.5, limit=100)
    assert texts(got) == ["Bite", "fight", "light"]


def test_depth_breaks_score_ties():
    # two-syllable anchor: a two-syllable perfect rhyme beats anything with a lower depth
    words = bank("honey", "sunny", "money")
    got = rank_rhymes(anchor("funny"), words, PairScorer(2), min_score=0.0, limit=100)
    assert got[0].match_depth >= got[-1].match_depth


def test_min_score_drops_and_limit_applies_last():
    words = bank("light", "fight", "bite", "money")
    all_ = rank_rhymes(anchor("night"), words, PairScorer(2), min_score=0.0, limit=100)
    assert "money" in texts(all_)
    strict = rank_rhymes(anchor("night"), words, PairScorer(2), min_score=0.99, limit=100)
    assert texts(strict) == ["bite", "fight", "light"]
    limited = rank_rhymes(anchor("night"), words, PairScorer(2), min_score=0.99, limit=2)
    assert texts(limited) == ["bite", "fight"]
    assert all(m.score >= 0.99 for m in strict)


def test_excludes_anchor_word_and_equal_text():
    words = bank("night", "Light", "fight")
    got = rank_rhymes(anchor("night"), words, PairScorer(2), min_score=0.5, limit=100, exclude_id="id-0")
    assert texts(got) == ["fight", "Light"]
    got = rank_rhymes(anchor("LIGHT"), words, PairScorer(2), min_score=0.5, limit=100, exclude_text="LIGHT")
    assert texts(got) == ["fight", "night"]


def test_unpronounceable_candidates_are_skipped():
    words = bank("夜", "light")
    got = rank_rhymes(anchor("night"), words, PairScorer(2), min_score=0.0, limit=100)
    assert texts(got) == ["light"]


def test_phrases_rhyme_on_their_last_words():
    words = bank("broken heart", "fall apart", "money")
    got = rank_rhymes(anchor("start"), words, PairScorer(2), min_score=0.8, limit=100)
    assert set(texts(got)) == {"broken heart", "fall apart"}


def test_match_fields():
    (m,) = rank_rhymes(anchor("night"), bank("light"), PairScorer(2), min_score=0.0, limit=100)
    assert m.word.id == "id-0"
    assert m.score == 1.0
    assert m.match_depth == 1


@pytest.mark.parametrize("limit", [0, -1])
def test_rejects_bad_limit(limit):
    with pytest.raises(ValueError):
        rank_rhymes(anchor("night"), bank("light"), PairScorer(2), min_score=0.0, limit=limit)
