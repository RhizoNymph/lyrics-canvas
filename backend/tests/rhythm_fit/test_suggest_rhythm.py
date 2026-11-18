"""POST /api/nlp/rhyme-suggest with rhythm_fit, at the service level (fake repo, dictionary, embedder)."""

from __future__ import annotations

import logging
from dataclasses import replace

import pytest

from app.schemas.rhyme import RhymeSuggestRequest
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.score import ScoreWeights
from app.services.rhyme_order import BAND_RANK, band_of
from app.services.rhyme_suggest_service import suggest
from app.services.rhythm_fit import SlotBeat, SlotRhythm, candidate_stresses, rhythm_fit
from tests.fakes import FakeDictionary, default_repo, make_record
from tests.meaning.fakes import meaning_service
from tests.score.helpers import score

WORDS = (
    "delight", "despite", "polite", "midnight", "daylight", "highlight", "moonlight", "alright", "ignite",
    "tonight", "overnight", "night", "light", "kite", "bright", "cat", "letter",
)

# "we dance tonight": we (s0) on beat 3, dance (s1) on beat 4 (accent), "ton" (s2) on the "and" of
# beat 4 (weight 1), "ight" (s3) on the next downbeat (weight 3, accent): an iambic slot for "tonight"
TONIGHT = score([[(0, 96, 24, 0), (1, 144, 24, 1), (2, 168, 24, 0), (3, 192, 48, 1)]])
TONIGHT_SLOT = SlotRhythm((SlotBeat(168, 1, 0), SlotBeat(192, 3, 1)))
LINE_SLOT = SlotRhythm((SlotBeat(96, 2, 0), SlotBeat(144, 2, 1), SlotBeat(168, 1, 0), SlotBeat(192, 3, 1)))
RANGE = {"kind": "snippet", "snippet_id": "s-score", "line_index": 0, "word_start": 2, "word_end": 3}
LINE = {"kind": "snippet", "snippet_id": "s-score", "line_index": 0}


def dictionary(dialect: DialectMap, weights: ScoreWeights | None, include_rare: bool) -> FakeDictionary:
    return FakeDictionary(dialect, weights, include_rare, words=WORDS)


def repo(anchor_score=TONIGHT, extra=()):
    r = default_repo()
    r.snippets["s-score"] = replace(make_record("s-score", "we dance tonight"), score=anchor_score)
    r.snippets["s-sing"] = make_record("s-sing", "we sing at night")
    for record in extra:
        r.snippets[record.id] = record
    return r


def req(**patch) -> RhymeSuggestRequest:
    base = {"canvas_id": "c1", "anchors": [RANGE], "k": 1, "limit": 100, "min_score": 0.5, "rhythm_fit": True}
    return RhymeSuggestRequest.model_validate({**base, **patch})


async def run(request, r=None, meaning=None):
    return await suggest(request, "u1", r or repo(), dictionary=dictionary, meaning=meaning)


def assert_band_then_fit(candidates):
    keys = [(BAND_RANK[c.band], -c.rhythm_fit) for c in candidates]
    assert keys == sorted(keys)


# --- applied ---


async def test_range_anchor_filters_to_the_slot_count_and_scores_every_candidate():
    res = await run(req(sources=["dictionary"]))
    assert res.rhythm_applied is True
    assert res.rhythm_unavailable_reason is None
    ids = [c.id for c in res.candidates]
    assert "dict:delight" in ids and "dict:midnight" in ids
    assert not {"dict:night", "dict:overnight", "dict:kite"} & set(ids)
    for c in res.candidates:
        assert c.syllable_count == 2
        assert c.rhythm_fit == rhythm_fit(TONIGHT_SLOT, candidate_stresses(c.stress))
        assert c.band == band_of(c.score)
    assert_band_then_fit(res.candidates)


async def test_iambs_fit_the_iambic_slot_best():
    res = await run(req(sources=["dictionary"]))
    by_id = {c.id: c for c in res.candidates}
    assert by_id["dict:delight"].rhythm_fit == 1.0  # 01 on targets 0, 1
    assert by_id["dict:midnight"].rhythm_fit == 0.25  # 12: primary on the weak "and", secondary on the downbeat
    assert by_id["dict:alright"].rhythm_fit == 0.75  # 21
    perfect = [c for c in res.candidates if c.band == "perfect"]
    fits = [c.rhythm_fit for c in perfect]
    assert fits == sorted(fits, reverse=True)
    assert perfect[0].stress == "01"


async def test_filter_applies_before_paging_and_total():
    off = await run(req(sources=["dictionary"], rhythm_fit=False))
    on = await run(req(sources=["dictionary"]))
    two = [c for c in off.candidates if c.syllable_count == 2]
    assert on.total == len(two) < off.total
    assert sorted(c.id for c in on.candidates) == sorted(c.id for c in two)
    pages = [await run(req(sources=["dictionary"], limit=2, offset=o)) for o in range(0, on.total + 2, 2)]
    assert all(p.total == on.total for p in pages)
    assert [c.id for p in pages for c in p.candidates] == [c.id for c in on.candidates]


async def test_equal_fit_keeps_the_rhyme_order_inside_a_band():
    off = await run(req(sources=["dictionary"], rhythm_fit=False))
    on = await run(req(sources=["dictionary"]))
    position = {c.id: i for i, c in enumerate(off.candidates)}
    for a, b in zip(on.candidates, on.candidates[1:]):
        if a.band == b.band and a.rhythm_fit == b.rhythm_fit:
            assert position[a.id] < position[b.id]


async def test_a_note_line_contributes_its_suffix_of_the_slot_length():
    # without rhythm the line's best suffix is the whole line; with rhythm the two-syllable "at night"
    off = await run(req(sources=["notes"], rhythm_fit=False))
    on = await run(req(sources=["notes"]))
    sing_off = [c for c in off.candidates if c.snippet_id == "s-sing"]
    sing_on = [c for c in on.candidates if c.snippet_id == "s-sing"]
    assert len(sing_off) == 1 and sing_off[0].syllable_count != 2
    assert [c.text for c in sing_on] == ["at night"]
    assert sing_on[0].rhythm_fit == rhythm_fit(TONIGHT_SLOT, (1, 1)) == 0.5


async def test_rests_in_a_note_candidate_do_not_count_as_syllables():
    rested = make_record("s-rest", "oh _ de _ light")
    res = await run(req(sources=["notes"]), r=repo(extra=[rested]))
    found = [c for c in res.candidates if c.snippet_id == "s-rest"]
    assert found, [c.text for c in res.candidates]
    assert all(c.syllable_count == 2 for c in found)
    assert any(any(cell.rest for cell in c.grid_line.cells) for c in found)


async def test_whole_line_anchor_uses_every_syllable_of_the_line():
    res = await run(req(anchors=[LINE], sources=["notes"]))
    assert res.rhythm_applied is True
    assert all(c.syllable_count == 4 for c in res.candidates)
    sing = next(c for c in res.candidates if c.snippet_id == "s-sing")
    assert sing.text == "we sing at night"
    assert sing.rhythm_fit == rhythm_fit(LINE_SLOT, candidate_stresses(sing.stress))


async def test_rhythm_with_meaning_orders_band_fit_then_similarity():
    service, _ = meaning_service()
    res = await run(req(sources=["dictionary"], order="meaning"), meaning=service)
    assert res.order_applied == "meaning" and res.rhythm_applied is True
    assert all(c.similarity is not None and c.rhythm_fit is not None for c in res.candidates)
    keys = [(BAND_RANK[c.band], -c.rhythm_fit, -c.similarity) for c in res.candidates]
    assert keys == sorted(keys)


async def test_rhythm_with_meaning_unavailable_falls_back_to_band_fit_score():
    res = await run(req(sources=["dictionary"], order="meaning"))  # no meaning service at all
    assert res.order_applied == "rhyme" and res.rhythm_applied is True
    assert all(c.similarity is None for c in res.candidates)
    keys = [(BAND_RANK[c.band], -c.rhythm_fit) for c in res.candidates]
    assert keys == sorted(keys)


async def test_accents_in_the_score_change_the_slot():
    # "ton" accented strongly (2): levels 3, 3 -> a flat strong slot, spondees fit best
    accented = score([[(0, 96, 24, 0), (1, 144, 24, 1), (2, 168, 24, 2), (3, 192, 48, 1)]])
    res = await run(req(sources=["dictionary"]), r=repo(anchor_score=accented))
    by_id = {c.id: c for c in res.candidates}
    assert by_id["dict:delight"].rhythm_fit == 0.5
    assert by_id["dict:midnight"].rhythm_fit == 0.75


# --- not applied ---


async def _same_as_off(request, r=None):
    on = await run(request, r)
    off = await run(request.model_copy(update={"rhythm_fit": False}), r)
    assert [c.id for c in on.candidates] == [c.id for c in off.candidates]
    assert on.total == off.total
    assert all(c.rhythm_fit is None for c in on.candidates)
    assert on.rhythm_applied is False
    return on


async def test_text_anchor_has_no_score():
    res = await _same_as_off(req(anchors=[{"kind": "text", "text": "tonight"}]))
    assert res.rhythm_unavailable_reason == "no_score"


async def test_snippet_without_a_score_has_no_score():
    res = await _same_as_off(req(), r=repo(anchor_score=None))
    assert res.rhythm_unavailable_reason == "no_score"


async def test_an_unplaced_slot_syllable_is_slot_unplaced():
    partial = score([[(0, 96, 24, 0), (1, 144, 24, 1), (2, 168, 24, 0)]])
    res = await _same_as_off(req(), r=repo(anchor_score=partial))
    assert res.rhythm_unavailable_reason == "slot_unplaced"
    # the placed range "we dance" still applies
    ok = await run(req(anchors=[{**RANGE, "word_start": 0, "word_end": 2}]), r=repo(anchor_score=partial))
    assert ok.rhythm_applied is True


async def test_an_invalid_stored_score_is_no_score_and_logged(caplog):
    broken = {**TONIGHT, "lines": []}
    with caplog.at_level(logging.WARNING):
        res = await _same_as_off(req(), r=repo(anchor_score=broken))
    assert res.rhythm_unavailable_reason == "no_score"
    assert "rhythm_fit.stored_score_invalid snippet_id=s-score" in caplog.text


async def test_only_the_first_anchor_gives_the_slot():
    res = await _same_as_off(req(anchors=[{"kind": "text", "text": "tonight"}, RANGE]))
    assert res.rhythm_unavailable_reason == "no_score"
    applied = await run(req(anchors=[RANGE, {"kind": "text", "text": "light"}]))
    assert applied.rhythm_applied is True


async def test_rhythm_off_is_the_plain_path():
    request = req(rhythm_fit=False)
    res = await run(request, r=repo())
    assert res.rhythm_applied is False
    assert res.rhythm_unavailable_reason is None
    assert all(c.rhythm_fit is None for c in res.candidates)
    default = RhymeSuggestRequest.model_validate({"canvas_id": "c1", "anchors": [RANGE]})
    assert default.rhythm_fit is False


@pytest.mark.parametrize("value", ["maybe", 2, None])
def test_rhythm_fit_must_be_a_boolean(value):
    with pytest.raises(ValueError):
        RhymeSuggestRequest.model_validate({"canvas_id": "c1", "anchors": [RANGE], "rhythm_fit": value})
