"""Slot candidates from an index: rhyme with every member, score = the weakest member score."""

from app.services.phonetics import DEFAULT_DIALECT_MAP
from app.services.phonetics.pattern.candidates import slot_candidates
from app.services.phonetics.score import score

from .fakes import fake_dictionary_index
from .helpers import line_words
from app.services.phonetics.pattern.spans import build_span


def _member(text: str):
    (line,) = line_words([text])
    span = build_span("s", line, 0, len(line.words), DEFAULT_DIALECT_MAP)
    assert span is not None
    return span.unit


def test_candidates_rhyme_with_every_member():
    index = fake_dictionary_index(DEFAULT_DIALECT_MAP, False)
    members = [_member("steady"), _member("ready")]
    found = {c.unit.id: c for c in slot_candidates(index, members, k=2, min_score=0.75)}
    assert "dict:heavy" in found and "dict:petty" in found
    assert "dict:long" not in found
    heavy = found["dict:heavy"]
    assert len(heavy.scores) == 2
    assert heavy.score == min(s.score for s in heavy.scores)
    for member, s in zip(members, heavy.scores):
        assert s.score == score(member.effective_pronunciation, heavy.unit.effective_pronunciation, 2).score


def test_a_unit_rhyming_with_one_member_only_is_absent():
    index = fake_dictionary_index(DEFAULT_DIALECT_MAP, False)
    found = {c.unit.id for c in slot_candidates(index, [_member("steady"), _member("wrong")], k=1, min_score=0.75)}
    assert "dict:ready" not in found and "dict:long" not in found


def test_excluded_units_are_dropped():
    index = fake_dictionary_index(DEFAULT_DIALECT_MAP, False)
    found = {c.unit.id for c in slot_candidates(index, [_member("steady")], k=2, min_score=0.75, exclude=frozenset({"dict:heavy"}))}
    assert "dict:heavy" not in found and "dict:ready" in found


def test_weakest_member_and_mean():
    index = fake_dictionary_index(DEFAULT_DIALECT_MAP, False)
    (heavy,) = [c for c in slot_candidates(index, [_member("ready"), _member("heavy")], k=2, min_score=0.5) if c.unit.id == "dict:steady"]
    assert heavy.scores[0].score == 1.0 and heavy.scores[1].score < 1.0
    assert heavy.weakest == 1
    assert heavy.mean == (heavy.scores[0].score + heavy.scores[1].score) / 2
