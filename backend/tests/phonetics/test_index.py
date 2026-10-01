import pytest

from app.services.phonetics import DEFAULT_DIALECT_MAP, RhymeIndex, pronounce, score
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.errors import RhymeIndexError, RhymeQueryError
from app.services.phonetics.types import Provenance
from app.services.phonetics.units import dict_unit_id, unit_from_text

WORDS = [
    "cat", "bat", "hat", "cap", "cash", "dog", "station", "nation", "motion", "ocean",
    "orange", "hinge", "singing", "sinning", "ring", "thin", "test", "mess", "letter",
    "better", "tomorrow", "sorrow", "tree", "blue",
]


def _unit(word: str, dialect: DialectMap = DEFAULT_DIALECT_MAP):
    return unit_from_text(word, dialect, id=dict_unit_id(word), provenance=Provenance.dictionary, ephemeral=False)


@pytest.fixture
def index() -> RhymeIndex:
    idx = RhymeIndex(DEFAULT_DIALECT_MAP)
    for w in WORDS:
        idx.insert(_unit(w))
    return idx


def _ids(hits) -> list[str]:
    return [h.unit_id for h in hits]


def test_len_and_insert_is_idempotent(index: RhymeIndex):
    assert len(index) == len(WORDS)
    index.insert(_unit("cat"))
    assert len(index) == len(WORDS)


def test_exact_rhymes_come_first(index: RhymeIndex):
    hits = index.query(pronounce("cat"), k=1, min_score=0.5, limit=10)
    ids = _ids(hits)
    assert ids[:3] == ["dict:bat", "dict:cat", "dict:hat"]
    assert all(h.score.score == 1.0 for h in hits[:3])
    assert "dict:cap" in ids and "dict:cash" in ids
    assert "dict:dog" not in ids


def test_results_sorted_by_score_depth_then_id(index: RhymeIndex):
    hits = index.query(pronounce("station"), k=2, min_score=0.3, limit=50)
    keys = [(-h.score.score, -h.score.match_depth, h.unit_id) for h in hits]
    assert keys == sorted(keys)
    assert _ids(hits)[:2] == ["dict:nation", "dict:station"]


def test_min_score_filters(index: RhymeIndex):
    loose = index.query(pronounce("cat"), k=1, min_score=0.5, limit=50)
    strict = index.query(pronounce("cat"), k=1, min_score=0.99, limit=50)
    assert len(strict) < len(loose)
    assert all(h.score.score >= 0.99 for h in strict)


def test_limit_caps_results(index: RhymeIndex):
    hits = index.query(pronounce("cat"), k=1, min_score=0.0, limit=2)
    assert len(hits) == 2


def test_exclude_removes_ids(index: RhymeIndex):
    hits = index.query(pronounce("cat"), k=1, min_score=0.5, limit=10, exclude=frozenset({"dict:cat", "dict:bat"}))
    ids = _ids(hits)
    assert "dict:cat" not in ids and "dict:bat" not in ids
    assert "dict:hat" in ids


def test_remove(index: RhymeIndex):
    index.remove("dict:hat")
    assert len(index) == len(WORDS) - 1
    assert "dict:hat" not in _ids(index.query(pronounce("cat"), k=1, min_score=0.5, limit=10))
    index.remove("dict:not-there")  # no-op


def test_dialect_cases_via_index(index: RhymeIndex):
    hits = index.query(pronounce("door hinge"), k=2, min_score=0.5, limit=10)
    ids = _ids(hits)
    assert "dict:orange" in ids
    assert "dict:hinge" in ids
    ring = index.query(pronounce("ring"), k=1, min_score=0.5, limit=10)
    perfect = {h.unit_id for h in ring if h.score.score == 1.0}
    assert {"dict:ring", "dict:thin", "dict:singing"} <= perfect
    assert "dict:sinning" in perfect
    assert ring[0].score.score == 1.0


def test_shorter_candidates_are_scored_at_their_own_depth(index: RhymeIndex):
    hits = index.query(pronounce("tomorrow"), k=3, min_score=0.5, limit=10)
    by_id = {h.unit_id: h for h in hits}
    assert "dict:sorrow" in by_id
    assert len(by_id["dict:sorrow"].score.alignment) == 2
    # candidate-side indexes refer to the candidate's own syllables
    assert [a.b_index for a in by_id["dict:sorrow"].score.alignment] == [1, 0]
    assert [a.a_index for a in by_id["dict:sorrow"].score.alignment] == [2, 1]


def test_query_rejects_bad_k(index: RhymeIndex):
    with pytest.raises(RhymeQueryError):
        index.query(pronounce("cat"), k=0, min_score=0.5, limit=10)
    with pytest.raises(RhymeQueryError):
        index.query(pronounce("cat"), k=1, min_score=0.5, limit=0)


def test_insert_rejects_unit_built_with_another_dialect(index: RhymeIndex):
    with pytest.raises(RhymeIndexError):
        index.insert(_unit("cat", DialectMap(rules=())))


def test_empty_tail_returns_nothing(index: RhymeIndex):
    assert index.query(pronounce(""), k=2, min_score=0.5, limit=10) == []


def test_query_scores_short_members_at_requested_depth():
    """A one-syllable member must take the same short-side discount from the index
    that score(anchor, candidate, k) applies directly, so k=2 ranks true
    two-syllable rhymes above monosyllabic perfect rhymes."""
    index = RhymeIndex(DEFAULT_DIALECT_MAP)
    for word in ("hinge", "syringe", "orange"):
        index.insert(_unit(word))
    hits = {h.unit_id: h.score for h in index.query(pronounce("door hinge"), k=2, min_score=0.3, limit=10)}
    direct = score(DEFAULT_DIALECT_MAP.apply(pronounce("door hinge")), DEFAULT_DIALECT_MAP.apply(pronounce("hinge")), 2)
    assert hits["dict:hinge"].score == pytest.approx(direct.score)
    assert hits["dict:hinge"].score < 0.6
    assert hits["dict:syringe"].score > hits["dict:hinge"].score
