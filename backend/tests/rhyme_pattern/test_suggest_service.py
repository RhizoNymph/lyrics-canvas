"""POST /api/nlp/rhyme-pattern/suggest at the service level (fake repository and dictionary)."""

import pytest

from app.schemas.rhyme_pattern import PatternSuggestRequest
from app.services import rhyme_pattern_service as svc
from app.services.rhyme_pattern.errors import (
    PatternErrorCode,
    PatternRangeInvalidError,
    PatternSlotOrderError,
    PatternSpanInvalidError,
)
from app.services.rhyme_suggest_service import CanvasNotFoundError, SnippetNotFoundError
from app.services.tag_expr import TagExpressionError

from .fakes import fake_dictionary_index, pattern_repo


def _span(line: int, start: int, end: int | None = None) -> dict:
    return {"line_index": line, "word_start": start, "word_end": start + 1 if end is None else end}


STEADY = {"id": "s0", "members": [_span(0, 3), _span(1, 0), _span(2, 0)]}
EH_ER = {"id": "s1", "members": [_span(0, 6), _span(1, 4), _span(2, 4)]}
END = {"id": "s2", "members": [_span(0, 7), _span(1, 5), _span(2, 5)]}


def _req(**patch) -> PatternSuggestRequest:
    base = {"canvas_id": "c1", "snippet_id": "s-verse", "line_start": 0, "line_end": 3, "slots": [STEADY, EH_ER, END], "limit": 100}
    return PatternSuggestRequest.model_validate({**base, **patch})


async def _run(req: PatternSuggestRequest, user_id: str = "u1"):
    return await svc.suggest(req, user_id, pattern_repo(), dictionary=fake_dictionary_index)


def _ids(result) -> list[str]:
    return [c.id for c in result.candidates]


async def test_one_result_per_slot_in_order_with_recomputed_slots():
    res = await _run(_req())
    assert res.elapsed_ms >= 0
    assert [r.slot.id for r in res.slots] == ["s0", "s1", "s2"]
    steady = res.slots[0].slot
    assert [m.text for m in steady.members] == ["steady", "already", "ready"]
    assert steady.coverage == 3 and steady.full and steady.default_on
    assert steady.k == 2 and steady.stress == "10" and steady.syllable_count == 2
    assert steady.cohesion == pytest.approx(1.0)


async def test_candidates_rhyme_with_every_member_and_score_is_the_minimum():
    res = await _run(_req())
    for result in res.slots:
        assert result.total == len(result.candidates)
        for c in result.candidates:
            assert len(c.member_scores) == len(result.slot.members)
            assert c.score == min(c.member_scores)
            assert all(s >= 0.5 for s in c.member_scores)
    steady = res.slots[0]
    assert "dict:heavy" in _ids(steady) and "dict:petty" in _ids(steady)
    assert "dict:long" not in _ids(steady)
    assert "dict:feather" in _ids(res.slots[1]) and "dict:strong" in _ids(res.slots[2])


async def test_member_texts_are_never_candidates():
    res = await _run(_req())
    for result in res.slots:
        members = {m.text.lower() for m in result.slot.members}
        assert all(c.text.lower() not in members for c in result.candidates)
    assert "dict:steady" not in _ids(res.slots[0]) and "dict:ready" not in _ids(res.slots[0])


async def test_best_anchor_is_the_weakest_member_span():
    res = await _run(_req())
    result = res.slots[0]
    heavy = next(c for c in result.candidates if c.id == "dict:heavy")
    weakest = min(range(len(heavy.member_scores)), key=lambda i: (heavy.member_scores[i], i))
    m = result.slot.members[weakest]
    assert heavy.best_anchor_id == f"pattern:s-verse:{m.line_index}:{m.word_start}-{m.word_end}"
    assert heavy.alignment and heavy.match_depth >= 1
    assert heavy.fits_slot is True  # "10" in a "10" slot


async def test_ranking_score_then_mean_then_depth_then_notes_first_then_id():
    res = await _run(_req())
    for result in res.slots:
        keys = [
            (-c.score, -sum(c.member_scores) / len(c.member_scores), -c.match_depth, 0 if c.source == "notes" else 1, c.id)
            for c in result.candidates
        ]
        assert keys == sorted(keys)


async def test_a_word_rhyming_with_one_member_only_is_absent():
    mixed = {"id": "mix", "members": [_span(0, 3), _span(1, 5)]}  # steady + wrong
    res = await _run(_req(slots=[mixed], min_score=0.75))
    ids = _ids(res.slots[0])
    assert "dict:ready" not in ids and "dict:long" not in ids and "dict:heavy" not in ids


async def test_single_member_slot_uses_its_stressed_tail():
    res = await _run(_req(slots=[{"members": [_span(0, 3)]}]))
    (result,) = res.slots
    assert result.slot.id == "u0"
    assert result.slot.k == 2 and result.slot.cohesion == 1.0 and result.slot.coverage == 1
    assert result.slot.full is False
    assert "dict:ready" in _ids(result)


async def test_missing_ids_are_numbered_and_given_ids_echoed():
    res = await _run(_req(slots=[{"members": [_span(0, 3), _span(1, 0)]}, {"id": "u0", "members": [_span(0, 6)]}, {"members": [_span(0, 7)]}]))
    assert [r.slot.id for r in res.slots] == ["u1", "u0", "u2"]


async def test_k_override_and_members_echoed_in_line_order():
    res = await _run(_req(slots=[{"id": "x", "k": 1, "members": [_span(2, 0), _span(0, 3)]}]))
    slot = res.slots[0].slot
    assert slot.k == 1
    assert [m.line_index for m in slot.members] == [0, 2]


async def test_note_spans_anywhere_in_a_line_are_candidates():
    res = await _run(_req())
    steady = res.slots[0]
    notes = [c for c in steady.candidates if c.source == "notes"]
    heavy = next(c for c in notes if c.snippet_id == "s-notes" and c.line_index == 0)
    assert heavy.id == "note:s-notes:0:0-1" and heavy.text == "heavy"
    assert heavy.word_offset == 0 and heavy.word_end == 1
    assert heavy.grid_line.raw_text == "heavy"
    assert heavy.tags == ["t-hook"]
    # one span per (snippet, line) per slot
    lines = [(c.snippet_id, c.line_index) for c in notes]
    assert len(lines) == len(set(lines))
    # the anchor snippet's other lines take part, its pattern lines do not
    eh_er = [c for c in res.slots[1].candidates if c.source == "notes"]
    assert any(c.id == "note:s-verse:3:3-4" for c in eh_er)  # "letter"
    assert all(not (c.snippet_id == "s-verse" and c.line_index in (0, 1, 2)) for r in res.slots for c in r.candidates)


async def test_note_text_equal_to_or_ending_with_a_member_is_dropped():
    res = await _run(_req())
    steady = res.slots[0]
    # "long ago so steady": neither "steady" nor "so steady" is a suggestion for the steady slot
    assert all(c.text not in ("steady", "so steady") for c in steady.candidates)
    assert all(not (c.snippet_id == "s-notes" and c.line_index == 1) for c in steady.candidates)


def test_repeats_member():
    from app.services.rhyme_pattern.suggest import repeats_member

    assert repeats_member("Steady", [("steady",)])
    assert repeats_member("so steady", [("steady",)])
    assert repeats_member("the pressure on", [("pressure", "on")])
    assert not repeats_member("unsteady", [("steady",)])
    assert not repeats_member("steady now", [("steady",)])


async def test_tag_filter_restricts_note_lines():
    hook = await _run(_req(tag_filter="hook"))
    snippets = {c.snippet_id for r in hook.slots for c in r.candidates if c.source == "notes"}
    assert "s-notes" in snippets and "s-draft" not in snippets and "s-verse" not in snippets
    draft = await _run(_req(tag_filter="draft"))
    snippets = {c.snippet_id for r in draft.slots for c in r.candidates if c.source == "notes"}
    assert snippets <= {"s-draft"} and "s-draft" in snippets
    assert all(m.unit.snippet_id == "s-draft" for m in draft.line_matches)
    with pytest.raises(TagExpressionError):
        await _run(_req(tag_filter="hook &"))


async def test_sources_select_dictionary_or_notes():
    dict_only = await _run(_req(sources=["dictionary"]))
    assert all(c.source == "dictionary" for r in dict_only.slots for c in r.candidates)
    notes_only = await _run(_req(sources=["notes"]))
    assert all(c.source == "notes" for r in notes_only.slots for c in r.candidates)


async def test_exclude_ids_apply_to_every_slot():
    res = await _run(_req(exclude_ids=["dict:heavy", "note:s-notes:0:0-1", "dict:strong"]))
    ids = [c.id for r in res.slots for c in r.candidates]
    assert "dict:heavy" not in ids and "note:s-notes:0:0-1" not in ids and "dict:strong" not in ids


async def test_offsets_page_one_slot():
    full = await _run(_req())
    paged = await _run(_req(limit=2, offsets={"s0": 1}))
    assert _ids(paged.slots[0]) == _ids(full.slots[0])[1:3]
    assert _ids(paged.slots[1]) == _ids(full.slots[1])[:2]
    assert [r.total for r in paged.slots] == [r.total for r in full.slots]
    beyond = await _run(_req(offsets={"s2": 1000}))
    assert beyond.slots[2].candidates == [] and beyond.slots[2].total == full.slots[2].total


async def test_line_matches_hit_two_or_more_slots_in_order():
    res = await _run(_req())
    by_id = {m.unit.id: m for m in res.line_matches}
    feather = by_id["note:s-notes:0:0"]
    assert feather.unit.text == "heavy is the feather song"
    assert feather.unit.grid_line.raw_text == "heavy is the feather song"
    assert [(h.slot_id, h.text) for h in feather.hits] == [("s0", "heavy"), ("s1", "feather"), ("s2", "song")]
    assert feather.slots_hit == 3
    assert feather.score == pytest.approx(sum(h.score for h in feather.hits) / 3)
    assert "note:s-notes:2:0" in by_id
    # "long ago so steady": the end-slot hit comes before the steady-slot hit, so it is no tuple
    assert "note:s-notes:1:0" not in by_id
    assert all(m.slots_hit >= 2 for m in res.line_matches)
    keys = [(-m.slots_hit, -m.score, m.unit.id) for m in res.line_matches]
    assert keys == sorted(keys)
    assert res.line_match_total == len(res.line_matches)
    for m in res.line_matches:
        starts = [h.word_start for h in m.hits]
        assert starts == sorted(starts)
        assert all(a.word_end <= b.word_start for a, b in zip(m.hits, m.hits[1:]))


async def test_line_matches_can_be_turned_off_and_paged():
    off = await _run(_req(line_matches=False))
    assert off.line_matches == [] and off.line_match_total == 0
    full = await _run(_req())
    page = await _run(_req(line_match_limit=1, line_match_offset=1))
    assert [m.unit.id for m in page.line_matches] == [m.unit.id for m in full.line_matches[1:2]]
    assert page.line_match_total == full.line_match_total


@pytest.mark.parametrize(
    "slots",
    [
        [{"members": [_span(3, 0)]}],  # line outside the range
        [{"members": [_span(0, 8, 9)]}],  # word range outside the line
        [{"members": [_span(0, 3, 3)]}],  # empty word range
        [{"members": [_span(0, 2, 1)]}],  # reversed word range
        [{"members": [_span(0, 3), _span(0, 6)]}],  # two members on one line
        [{"members": [_span(0, 3, 5)]}, {"members": [_span(0, 4)]}],  # overlapping members across slots
    ],
)
async def test_span_errors(slots):
    with pytest.raises(PatternSpanInvalidError) as err:
        await _run(_req(slots=slots))
    assert err.value.code is PatternErrorCode.span_invalid
    assert err.value.code.value == "pattern_span_invalid"


async def test_unpronounceable_member_is_a_span_error():
    with pytest.raises(PatternSpanInvalidError):
        await _run(_req(snippet_id="s-digits", line_start=0, line_end=2, slots=[{"members": [_span(0, 1)]}]))


async def test_slot_order_error():
    with pytest.raises(PatternSlotOrderError) as err:
        await _run(_req(slots=[END, STEADY]))
    assert err.value.code.value == "pattern_slot_order"
    # consistent on one line and inconsistent on another is still an error
    a = {"members": [_span(0, 3), _span(1, 5)]}
    b = {"members": [_span(0, 7), _span(1, 0)]}
    with pytest.raises(PatternSlotOrderError):
        await _run(_req(slots=[a, b]))


async def test_range_and_ownership_errors():
    with pytest.raises(PatternRangeInvalidError):
        await _run(_req(line_start=0, line_end=1))
    with pytest.raises(CanvasNotFoundError):
        await _run(_req(canvas_id="c-other"))
    with pytest.raises(SnippetNotFoundError):
        await _run(_req(snippet_id="s-elsewhere"))
