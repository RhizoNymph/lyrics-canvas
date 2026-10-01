import pytest

from app.services.phonetics import DEFAULT_DIALECT_MAP, pronounce, score
from app.services.phonetics.dialect import DialectMap, DialectRule, RuleKind
from app.services.phonetics.errors import RhymeQueryError
from app.services.phonetics.score import DEPTH_WEIGHTS, MATCH_DEPTH_THRESHOLD, ScoreWeights
from app.services.phonetics.types import Pronunciation

from .pairs import PAIRS, Pair


def _s(a: str, b: str, k: int, dialect: DialectMap = DEFAULT_DIALECT_MAP):
    return score(dialect.apply(pronounce(a)), dialect.apply(pronounce(b)), k)


@pytest.mark.parametrize("pair", PAIRS, ids=[f"{p.a}/{p.b}@{p.k}:{p.label}" for p in PAIRS])
def test_pair_scores_in_expected_range(pair: Pair):
    result = _s(pair.a, pair.b, pair.k)
    assert pair.low <= result.score <= pair.high, (pair, result)


@pytest.mark.parametrize("pair", PAIRS, ids=[f"{p.a}/{p.b}@{p.k}" for p in PAIRS])
def test_score_is_symmetric_and_bounded(pair: Pair):
    ab = _s(pair.a, pair.b, pair.k)
    ba = _s(pair.b, pair.a, pair.k)
    assert ab.score == pytest.approx(ba.score)
    assert ab.match_depth == ba.match_depth
    assert 0.0 <= ab.score <= 1.0
    assert len(ab.alignment) == len(ba.alignment)
    for x, y in zip(ab.alignment, ba.alignment):
        assert x.a_index == y.b_index and x.b_index == y.a_index
        assert x.total == pytest.approx(y.total)


def test_perfect_rhyme_is_exactly_one_and_full_depth():
    r = _s("station", "nation", 2)
    assert r.score == 1.0
    assert r.match_depth == 2
    assert [a.total for a in r.alignment] == [1.0, 1.0]


def test_equal_rhyme_keys_score_one_even_with_different_onsets():
    # the onset of the deepest aligned syllable is not part of the rhyme
    assert _s("cat", "bat", 1).score == 1.0
    assert _s("banana", "cabana", 2).score == 1.0


def test_alignment_is_right_to_left_with_pronunciation_indexes():
    a = DEFAULT_DIALECT_MAP.apply(pronounce("tomorrow"))
    b = DEFAULT_DIALECT_MAP.apply(pronounce("sorrow"))
    r = score(a, b, 2)
    assert [(x.a_index, x.b_index) for x in r.alignment] == [(2, 1), (1, 0)]


def test_depth_is_bounded_by_shorter_side():
    r = _s("orange", "hinge", 2)
    assert len(r.alignment) == 1
    assert r.alignment[0].a_index == 1 and r.alignment[0].b_index == 0


def test_missing_syllables_discount_short_sides_symmetrically():
    short = _s("door hinge", "hinge", 2)
    assert short.score == pytest.approx(DEPTH_WEIGHTS[0] / (DEPTH_WEIGHTS[0] + DEPTH_WEIGHTS[1]))
    assert short.score == pytest.approx(_s("hinge", "door hinge", 2).score)
    assert short.match_depth == 1
    assert _s("orange", "door hinge", 2).score > short.score
    # nothing to pad when both sides are shorter than k
    assert _s("cat", "bat", 2).score == 1.0
    assert _s("cat", "combat", 2).score == pytest.approx(short.score)


def test_zero_depth_scores_zero():
    empty = Pronunciation(words=())
    r = score(empty, DEFAULT_DIALECT_MAP.apply(pronounce("cat")), 2)
    assert r.score == 0.0 and r.match_depth == 0 and r.alignment == ()


def test_match_depth_counts_contiguous_syllables_from_the_end():
    r = _s("laughter", "daughter", 2)
    assert r.alignment[0].total >= MATCH_DEPTH_THRESHOLD
    assert r.alignment[1].total < MATCH_DEPTH_THRESHOLD
    assert r.match_depth == 1
    r2 = _s("telephone", "saxophone", 3)
    assert r2.match_depth == 2
    assert r2.score < 1.0


def test_depth_weights_favor_final_syllable():
    assert DEPTH_WEIGHTS == (1.0, 0.7, 0.5, 0.35)
    r = _s("laughter", "daughter", 2)
    w = DEPTH_WEIGHTS
    expected = (w[0] * r.alignment[0].total + w[1] * r.alignment[1].total) / (w[0] + w[1])
    assert r.score == pytest.approx(expected)


def test_weights_change_scores():
    default = _s("day", "date", 1)
    coda_heavy = score(
        DEFAULT_DIALECT_MAP.apply(pronounce("day")),
        DEFAULT_DIALECT_MAP.apply(pronounce("date")),
        1,
        ScoreWeights(vowel=1.0, coda=2.0, onset=0.25),
    )
    assert coda_heavy.score < default.score


def test_invalid_k_raises():
    a = DEFAULT_DIALECT_MAP.apply(pronounce("cat"))
    with pytest.raises(RhymeQueryError):
        score(a, a, 0)
    with pytest.raises(RhymeQueryError):
        score(a, a, 5)


def test_dialect_rules_change_outcomes():
    plain = DialectMap(rules=())
    assert _s("ring", "thin", 1, plain).score < 1.0
    assert _s("dawn", "don", 1, plain).score < 1.0
    assert _s("dawn", "don", 1).score == 1.0
    assert _s("test", "mess", 1, plain).score < _s("test", "mess", 1).score
    only_ing = DialectMap(rules=(DialectRule(RuleKind.final_merge, ("IH", "NG"), ("IH", "N")),))
    assert _s("ring", "thin", 1, only_ing).score == 1.0


def test_deletable_phone_costs_less_than_a_full_mismatch():
    with_rule = _s("hand", "man", 1)
    without = _s("hand", "man", 1, DialectMap(rules=()))
    assert with_rule.alignment[0].coda > without.alignment[0].coda
    assert with_rule.alignment[0].coda < 1.0
