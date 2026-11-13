"""POST /api/nlp/rhyme-pattern/detect at the service level (fake repository)."""

import pytest

from app.schemas.rhyme_pattern import PatternDetectRequest
from app.services import rhyme_pattern_service as svc
from app.services.phonetics.errors import DialectRuleError
from app.services.rhyme_pattern.errors import PatternErrorCode, PatternRangeInvalidError
from app.services.rhyme_suggest_service import CanvasNotFoundError, SnippetNotFoundError

from .fakes import pattern_repo
from .helpers import EXAMPLE


def _req(**patch) -> PatternDetectRequest:
    base = {"canvas_id": "c1", "snippet_id": "s-verse", "line_start": 0, "line_end": 3}
    return PatternDetectRequest.model_validate({**base, **patch})


async def _detect(req: PatternDetectRequest, user_id: str = "u1"):
    return await svc.detect(req, user_id, pattern_repo())


async def test_detect_returns_lines_and_ordered_slots():
    res = await _detect(_req())
    assert res.elapsed_ms >= 0
    assert [l.line_index for l in res.lines] == [0, 1, 2]
    assert [l.text for l in res.lines] == list(EXAMPLE)
    first = res.lines[0]
    assert [w.text for w in first.words] == EXAMPLE[0].split()
    assert [w.word_index for w in first.words] == list(range(8))
    steady = first.words[3]
    assert steady.syllable_count == 2 and steady.stress == "10"
    assert first.grid_line.raw_text == EXAMPLE[0]
    assert first.grid_line.syllable_count == sum(w.syllable_count for w in first.words)

    assert [s.id for s in res.slots] == ["s0", "s1", "s2"]
    assert [[m.text for m in s.members] for s in res.slots] == [
        ["steady", "already", "ready"],
        ["pressure", "weather", "better"],
        ["on", "wrong", "long"],
    ]
    end = res.slots[2]
    assert [(m.line_index, m.word_start, m.word_end) for m in end.members] == [(0, 7, 8), (1, 5, 6), (2, 5, 6)]
    assert end.coverage == 3 and end.full and end.default_on
    assert end.k == 1 and end.stress == "1" and end.syllable_count == 1
    assert 0.75 <= end.cohesion <= 1.0
    member = res.slots[0].members[0]
    assert member.syllable_count == 2 and member.stress == "10"
    assert member.rhyme_key == ["EH", "D", "IY"]
    assert [s.nucleus for s in member.syllables] == ["EH", "IY"]


async def test_detect_is_deterministic():
    first = await _detect(_req())
    again = await _detect(_req())
    assert [s.model_dump() for s in first.slots] == [s.model_dump() for s in again.slots]


async def test_display_text_keeps_case_and_punctuation():
    res = await _detect(_req(snippet_id="s-punct", line_start=0, line_end=2))
    assert res.lines[0].text == "Keep it Steady!"
    assert res.lines[0].words[2].text == "Steady!"
    (slot,) = res.slots
    assert [m.text for m in slot.members] == ["Steady!", "READY"]


async def test_lines_of_the_range_without_rhymes_still_come_back():
    res = await _detect(_req(line_start=1, line_end=4))
    assert [l.line_index for l in res.lines] == [1, 2, 3]
    assert all(m.line_index in (1, 2, 3) for s in res.slots for m in s.members)


async def test_threshold_is_applied():
    res = await _detect(_req(min_score=1.0))
    assert [[m.text for m in s.members] for s in res.slots] == [["steady", "already", "ready"], ["wrong", "long"]]
    assert res.slots[1].full is False and res.slots[1].default_on is False


@pytest.mark.parametrize(
    "patch",
    [
        {"line_start": 2, "line_end": 2},  # empty range
        {"line_start": 3, "line_end": 1},  # reversed
        {"line_start": 3, "line_end": 4},  # one line
        {"line_start": 2, "line_end": 9},  # past the snippet's lines
        {"snippet_id": "s-short", "line_start": 0, "line_end": 2},  # one line with words
        {"snippet_id": "s-long", "line_start": 0, "line_end": 17},  # more than 16 lines
    ],
)
async def test_range_errors(patch):
    with pytest.raises(PatternRangeInvalidError) as err:
        await _detect(_req(**patch))
    assert err.value.code is PatternErrorCode.range_invalid
    assert err.value.code.value == "pattern_range_invalid"
    assert err.value.message


async def test_sixteen_lines_are_allowed():
    res = await _detect(_req(snippet_id="s-long", line_start=1, line_end=17))
    assert len(res.lines) == 16
    assert res.slots and len(res.slots) <= 8


async def test_not_found_errors():
    with pytest.raises(CanvasNotFoundError):
        await _detect(_req(canvas_id="c-other"))
    with pytest.raises(SnippetNotFoundError):
        await _detect(_req(snippet_id="s-foreign"))
    with pytest.raises(SnippetNotFoundError):
        await _detect(_req(snippet_id="s-elsewhere"))  # the user's, but not on this canvas
    with pytest.raises(SnippetNotFoundError):
        await _detect(_req(snippet_id="missing"))


async def test_bad_dialect_rule():
    with pytest.raises(DialectRuleError):
        await _detect(_req(dialect={"rules": [{"kind": "merge", "source": ["XX"], "target": ["AA"]}]}))
