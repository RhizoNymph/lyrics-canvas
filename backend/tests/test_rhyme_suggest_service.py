import pytest

from app.schemas.rhyme import RhymeSuggestRequest, Slot
from app.services.phonetics.errors import RhymeQueryError
from app.services.rhyme_suggest_service import (
    CanvasNotFoundError,
    SnippetNotFoundError,
    fits_slot,
    suggest,
)

from .fakes import FakeDictionary, default_repo


def _req(**patch) -> RhymeSuggestRequest:
    base = {"canvas_id": "c1", "anchors": [{"kind": "text", "text": "cat"}], "k": 1}
    return RhymeSuggestRequest.model_validate({**base, **patch})


async def _run(req: RhymeSuggestRequest, user_id: str = "u1"):
    return await suggest(req, user_id, default_repo(), dictionary=FakeDictionary)


async def test_basic_query_returns_ranked_scored_candidates():
    res = await _run(_req())
    assert res.total >= 3
    assert res.elapsed_ms >= 0
    ids = [c.id for c in res.candidates]
    assert "dict:bat" in ids and "dict:hat" in ids
    assert "dict:dog" not in ids
    scores = [c.score for c in res.candidates]
    assert scores == sorted(scores, reverse=True)
    for c in res.candidates:
        assert c.score >= 0.5
        assert c.best_anchor_id == res.anchors[0].id
        assert c.source in ("notes", "dictionary")
        assert c.grid_line.cells and c.syllables and c.rhyme_key
        assert len(c.alignment) >= 1


async def test_anchor_text_itself_is_not_a_candidate():
    res = await _run(_req())
    assert "dict:cat" not in [c.id for c in res.candidates]
    assert all(c.text != "cat" for c in res.candidates)


async def test_notes_rank_before_dictionary_at_equal_score():
    res = await _run(_req())
    perfect = [c for c in res.candidates if c.score == 1.0]
    sources = [c.source for c in perfect]
    assert "notes" in sources and "dictionary" in sources
    assert sources.index("dictionary") > sources.index("notes")
    first_dict = sources.index("dictionary")
    assert all(s == "notes" for s in sources[:first_dict])


async def test_note_candidates_carry_snippet_metadata_and_are_deduped_per_line():
    res = await _run(_req(limit=100))
    notes = [c for c in res.candidates if c.source == "notes"]
    assert notes
    lines = [(c.snippet_id, c.line_index) for c in notes]
    assert len(lines) == len(set(lines))
    hat = next(c for c in notes if c.snippet_id == "s-hat")
    assert hat.provenance == "note" and hat.ephemeral is False
    assert hat.word_offset == 0 and hat.line_index == 0
    assert hat.tags == ["t-clothes", "t-animals"]
    assert hat.text == "wearing my hat"
    assert hat.grid_line.raw_text == "wearing my hat"
    # snippet without stored phonetics is computed on the fly
    assert any(c.snippet_id == "s-raw" for c in notes)
    # other users' snippets never appear
    assert all(c.snippet_id != "s-other" for c in notes)


async def test_shared_tags_with_context_break_ties():
    ctx = [{"kind": "snippet", "snippet_id": "s-cat", "line_index": 0}]
    res = await _run(_req(context=ctx, limit=100))
    perfect_notes = [c for c in res.candidates if c.source == "notes" and c.score == 1.0]
    # s-hat shares t-animals with the context snippet; s-cat line 1 ("mat") shares too; s-raw shares none
    tagged = [c.snippet_id for c in perfect_notes]
    assert tagged.index("s-raw") > tagged.index("s-hat")


async def test_slot_fit_rules():
    assert fits_slot("10", Slot(syllable_count=2, stress="10")) is True
    assert fits_slot("10", Slot(syllable_count=2, stress="00")) is False  # stressed on a slot 0
    assert fits_slot("00", Slot(syllable_count=2, stress="10")) is True  # unstressed may land anywhere
    assert fits_slot("01", Slot(syllable_count=2, stress="10")) is False
    assert fits_slot("20", Slot(syllable_count=2, stress="01")) is False
    assert fits_slot("1", Slot(syllable_count=2, stress="10")) is False  # length must match
    assert fits_slot("1", None) is True


async def test_non_fitting_candidates_are_returned_but_flagged():
    res = await _run(_req(slot={"syllable_count": 1, "stress": "1"}, limit=100))
    fits = {c.id: c.fits_slot for c in res.candidates}
    assert fits["dict:bat"] is True
    assert fits["dict:combat"] is False
    assert fits["dict:acrobat"] is False


async def test_slot_picks_fitting_note_suffix():
    res = await _run(_req(slot={"syllable_count": 1, "stress": "1"}, limit=100))
    hat = next(c for c in res.candidates if c.snippet_id == "s-hat")
    assert hat.fits_slot is True
    assert hat.text == "hat" and hat.word_offset == 2
    assert hat.id == "note:s-hat:0:2"


async def test_exclude_ids():
    res = await _run(_req(exclude_ids=["dict:bat", "note:s-hat:0:0"], limit=100))
    ids = [c.id for c in res.candidates]
    assert "dict:bat" not in ids
    assert "note:s-hat:0:0" not in ids
    # the excluded whole-line unit is replaced by a suffix of the same line
    assert any(c.snippet_id == "s-hat" for c in res.candidates)


async def test_paging_and_total():
    full = await _run(_req(limit=100))
    page1 = await _run(_req(limit=2, offset=0))
    page2 = await _run(_req(limit=2, offset=2))
    assert page1.total == page2.total == full.total
    assert [c.id for c in page1.candidates] == [c.id for c in full.candidates[:2]]
    assert [c.id for c in page2.candidates] == [c.id for c in full.candidates[2:4]]
    beyond = await _run(_req(limit=10, offset=1000))
    assert beyond.candidates == [] and beyond.total == full.total


async def test_anchors_are_echoed_with_phonetics():
    res = await _run(_req(anchors=[{"kind": "text", "text": "door hinge"}, {"kind": "snippet", "snippet_id": "s-cat"}], k=2))
    text_anchor, snippet_anchor = res.anchors
    assert text_anchor.provenance == "generated" and text_anchor.ephemeral is True
    assert text_anchor.id.startswith("text:")
    assert text_anchor.rhyme_key == ["IH", "N", "JH"]
    assert text_anchor.stress == "11" and text_anchor.syllable_count == 2
    assert [s.nucleus for s in text_anchor.syllables] == ["AA", "IH"]
    assert text_anchor.grid_line.syllable_count == 2
    # snippet anchor with line_index null = last non-empty line
    assert snippet_anchor.id == "note:s-cat:1:0"
    assert snippet_anchor.text == "sleeping on the mat"
    assert snippet_anchor.line_index == 1
    assert snippet_anchor.tags == ["t-animals"]
    assert snippet_anchor.grid_line.raw_text == "sleeping on the mat"
    ids = [c.id for c in res.candidates]
    assert "dict:orange" in ids and "dict:syringe" in ids
    assert "note:s-cat:1:0" not in ids  # anchors are not candidates


async def test_explicit_line_index():
    res = await _run(_req(anchors=[{"kind": "snippet", "snippet_id": "s-cat", "line_index": 0}]))
    assert res.anchors[0].id == "note:s-cat:0:0"
    assert "dict:bat" in [c.id for c in res.candidates]


async def test_sources_filter():
    only_dict = await _run(_req(sources=["dictionary"], limit=100))
    assert all(c.source == "dictionary" for c in only_dict.candidates)
    only_notes = await _run(_req(sources=["notes"], limit=100))
    assert only_notes.candidates and all(c.source == "notes" for c in only_notes.candidates)


async def test_min_score_and_weights_and_dialect_are_applied():
    strict = await _run(_req(min_score=0.99, limit=100))
    assert all(c.score >= 0.99 for c in strict.candidates)
    plain = {"rules": []}
    res_plain = await _run(_req(anchors=[{"kind": "text", "text": "ring"}], dialect=plain, limit=100))
    res_default = await _run(_req(anchors=[{"kind": "text", "text": "ring"}], limit=100))
    thin_plain = next(c for c in res_plain.candidates if c.id == "dict:thin")
    thin_default = next(c for c in res_default.candidates if c.id == "dict:thin")
    assert thin_default.score == 1.0 and thin_plain.score < 1.0
    heavy = await _run(_req(anchors=[{"kind": "text", "text": "cat"}], weights={"vowel": 1.0, "coda": 3.0, "onset": 0.25}, limit=100))
    cap_default = next(c for c in (await _run(_req(limit=100))).candidates if c.id == "dict:cap")
    cap_heavy = next(c for c in heavy.candidates if c.id == "dict:cap")
    assert cap_heavy.score < cap_default.score


async def test_errors_are_typed():
    with pytest.raises(CanvasNotFoundError):
        await _run(_req(canvas_id="c-other"))
    with pytest.raises(CanvasNotFoundError):
        await _run(_req(canvas_id="missing"))
    with pytest.raises(SnippetNotFoundError):
        await _run(_req(anchors=[{"kind": "snippet", "snippet_id": "s-other"}]))
    with pytest.raises(SnippetNotFoundError):
        await _run(_req(anchors=[{"kind": "snippet", "snippet_id": "nope"}]))
    with pytest.raises(RhymeQueryError):
        await _run(_req(anchors=[{"kind": "snippet", "snippet_id": "s-cat", "line_index": 7}]))
    with pytest.raises(RhymeQueryError):
        await _run(_req(anchors=[{"kind": "snippet", "snippet_id": "s-empty"}]))
    with pytest.raises(RhymeQueryError):
        await _run(_req(anchors=[{"kind": "text", "text": "42 ---"}]))


async def test_missing_context_entries_are_ignored():
    res = await _run(_req(context=[{"kind": "snippet", "snippet_id": "nope"}, {"kind": "text", "text": "x"}]))
    assert res.candidates


# --- word-range anchors ---


def test_snippet_anchor_range_schema_validation():
    from pydantic import ValidationError

    from app.schemas.rhyme import SnippetAnchor

    ok = SnippetAnchor(kind="snippet", snippet_id="s", line_index=0, word_start=1, word_end=3)
    assert (ok.word_start, ok.word_end) == (1, 3)
    assert SnippetAnchor(kind="snippet", snippet_id="s").word_start is None
    assert SnippetAnchor(kind="snippet", snippet_id="s", word_start=2).word_end is None
    for bad in ({"word_start": -1}, {"word_end": 0}, {"word_start": 2, "word_end": 2}, {"word_start": 3, "word_end": 1}):
        with pytest.raises(ValidationError):
            SnippetAnchor(kind="snippet", snippet_id="s", **bad)


async def test_range_anchor_resolves_to_the_word_slice():
    anchor = {"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, "word_start": 3, "word_end": 5}
    res = await _run(_req(anchors=[anchor], limit=100))
    unit = res.anchors[0]
    assert unit.id == "note:s-cat:0:3-5"
    assert unit.text == "black cat"
    assert unit.syllable_count == 2 and unit.stress == "11"
    assert unit.rhyme_key == ["AE", "T"]
    assert unit.snippet_id == "s-cat" and unit.line_index == 0
    assert unit.word_offset == 3 and unit.word_end == 5
    assert unit.tags == ["t-animals"]
    # echoed grid line is the matching slice of the snippet grid, re-based to the range
    assert unit.grid_line.raw_text == "black cat"
    assert [c.syllable for c in unit.grid_line.cells] == ["black", "cat"]
    assert [c.word_index for c in unit.grid_line.cells] == [0, 1]
    assert unit.grid_line.syllable_count == 2
    ids = [c.id for c in res.candidates]
    assert "dict:bat" in ids and "dict:hat" in ids
    # the anchor's own line is still excluded from note candidates
    assert all(not (c.snippet_id == "s-cat" and c.line_index == 0) for c in res.candidates)
    assert "note:s-cat:0:3-5" not in ids


async def test_range_anchor_open_ended_and_prefix():
    tail = {"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, "word_start": 4}
    res = await _run(_req(anchors=[tail]))
    assert res.anchors[0].id == "note:s-cat:0:4-5"
    assert res.anchors[0].text == "cat"
    assert res.anchors[0].word_offset == 4 and res.anchors[0].word_end == 5
    assert "dict:bat" in [c.id for c in res.candidates]

    head = {"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, "word_end": 2}
    res = await _run(_req(anchors=[head]))
    assert res.anchors[0].id == "note:s-cat:0:0-2"
    assert res.anchors[0].text == "i saw"  # unit text is the cleaned form; raw_text keeps the case
    assert res.anchors[0].word_offset == 0 and res.anchors[0].word_end == 2
    assert res.anchors[0].rhyme_key == ["AA"]
    assert res.anchors[0].grid_line.raw_text == "I saw"
    assert "dict:bat" not in [c.id for c in res.candidates]


async def test_range_anchor_defaults_to_last_line():
    anchor = {"kind": "snippet", "snippet_id": "s-cat", "word_start": 3}
    res = await _run(_req(anchors=[anchor]))
    assert res.anchors[0].id == "note:s-cat:1:3-4"
    assert res.anchors[0].text == "mat" and res.anchors[0].line_index == 1


async def test_whole_line_anchor_has_no_word_end():
    res = await _run(_req(anchors=[{"kind": "snippet", "snippet_id": "s-cat", "line_index": 0}]))
    assert res.anchors[0].id == "note:s-cat:0:0"
    assert res.anchors[0].word_offset == 0 and res.anchors[0].word_end is None
    text = await _run(_req())
    assert text.anchors[0].word_end is None


async def test_range_anchor_out_of_range_is_a_query_error():
    for patch in ({"word_start": 5}, {"word_end": 6}, {"word_start": 2, "word_end": 9}):
        anchor = {"kind": "snippet", "snippet_id": "s-cat", "line_index": 0, **patch}
        with pytest.raises(RhymeQueryError):
            await _run(_req(anchors=[anchor]))


async def test_range_anchor_without_pronounceable_words_is_a_query_error():
    from .fakes import make_record

    repo = default_repo()
    repo.snippets["s-num"] = make_record("s-num", "walking 42 ---")
    anchor = {"kind": "snippet", "snippet_id": "s-num", "line_index": 0, "word_start": 1}
    with pytest.raises(RhymeQueryError):
        await suggest(_req(anchors=[anchor]), "u1", repo, dictionary=FakeDictionary)
    ok = await suggest(_req(anchors=[{**anchor, "word_start": 0, "word_end": 1}]), "u1", repo, dictionary=FakeDictionary)
    assert ok.anchors[0].text == "walking"
