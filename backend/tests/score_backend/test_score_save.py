"""The pure save flow (app/services/score_save.py): what an edit of a snippet stores for its text,
grid and score (docs/features/score_backend.md#save-flow)."""

from __future__ import annotations

import copy

import pytest

from app.services.nlp.syllabifier import syllabify_text
from app.services.score import (
    ScoreInvalid,
    ScoreReason,
    apply_rest_link,
    check_score,
    draft_from_grid,
    score_to_json,
)
from app.services.score_save import (
    KeepScore,
    RemoveScore,
    ScoredContent,
    SetScore,
    StoredScoreInvalid,
    new_content,
    saved_content,
    score_edit,
)

FOUR_FOUR = {"num": 4, "den": 4}


def grid(text: str) -> dict:
    return syllabify_text(text)


def draft(g: dict) -> dict:
    return score_to_json(draft_from_grid(g, FOUR_FOUR, 90, "1/8"))


def stored(text: str, *, score: bool = True, view: bool = False) -> ScoredContent:
    g = grid(text)
    return ScoredContent(raw_text=text, grid=g, score=draft(g) if score else None, score_view=view and score)


def notes(content: ScoredContent, line: int = 0) -> list[tuple[int, int, int]]:
    assert content.score is not None
    return [(n["s"], n["t"], n["d"]) for n in content.score["lines"][line]["notes"]]


def score_with(g: dict, *lines: list[tuple[int, int, int]]) -> dict:
    return {
        "v": 1, "ppq": 48, "pickup": 0, "meters": [{"bar": 0, "num": 4, "den": 4}],
        "tempos": [{"bar": 0, "bpm": 90}], "snap": "1/16",
        "lines": [{"notes": [{"s": s, "t": t, "d": d, "a": 0} for s, t, d in line]} for line in lines],
    }


def rests(g: dict, line: int = 0) -> list[bool]:
    return [c.get("rest") is True for c in g["lines"][line]["cells"]]


def assert_invariants(content: ScoredContent) -> None:
    """What every stored content satisfies."""
    if content.score is None:
        assert content.score_view is False
        return
    score = check_score(content.score, content.grid)
    assert score_to_json(score) == content.score
    assert apply_rest_link(score, content.grid) == content.grid


# ---- score_edit ----


def test_score_edit_absent_is_keep():
    assert score_edit(set(), None) == KeepScore()
    assert score_edit({"raw_text"}, {"v": 1}) == KeepScore()


def test_score_edit_explicit_null_is_remove():
    assert score_edit({"score"}, None) == RemoveScore()


def test_score_edit_value_is_set_even_when_not_an_object():
    assert score_edit({"score"}, {"v": 1}) == SetScore({"v": 1})
    assert score_edit({"score"}, []) == SetScore([])
    assert score_edit({"score"}, 0) == SetScore(0)


# ---- no score anywhere ----


def test_no_score_stays_none_and_view_is_forced_false():
    s = stored("I go now", score=False)
    out = saved_content(s, raw_text="I go", grid=grid("I go"), edit=KeepScore(), view=True)
    assert out.score is None and out.score_view is False
    assert out.raw_text == "I go" and out.grid == grid("I go")


def test_remove_without_score_is_harmless():
    s = stored("I go now", score=False)
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=RemoveScore(), view=None)
    assert out == s


# ---- unchanged ----


def test_unchanged_content_is_identity():
    s = stored("I go now\nhello world", view=True)
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=KeepScore(), view=None)
    assert out == s
    assert_invariants(out)


def test_view_only_toggle_keeps_everything_else():
    s = stored("I go now")
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=KeepScore(), view=True)
    assert out.score_view is True and out.score == s.score and out.grid == s.grid and out.raw_text == s.raw_text
    back = saved_content(out, raw_text=out.raw_text, grid=out.grid, edit=KeepScore(), view=False)
    assert back.score_view is False and back.score == s.score


def test_view_not_sent_keeps_stored_view():
    s = stored("I go now", view=True)
    out = saved_content(s, raw_text="I go now", grid=s.grid, edit=KeepScore(), view=None)
    assert out.score_view is True


# ---- remove ----


def test_remove_drops_score_and_view_and_keeps_grid_and_text():
    g = grid("I _ go now")
    s = ScoredContent("I _ go now", g, draft(g), True)
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=RemoveScore(), view=None)
    assert out.score is None and out.score_view is False
    assert out.grid == g and out.raw_text == "I _ go now"


def test_remove_with_view_true_still_forces_false():
    s = stored("I go now", view=True)
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=RemoveScore(), view=True)
    assert out.score is None and out.score_view is False


# ---- set ----


def test_set_score_is_validated_and_canonicalized():
    s = stored("I go now", score=False)
    raw = {"lines": [{"notes": [{"a": 1, "d": 24.0, "t": 0, "s": 0}]}], "snap": "1/8", "tempos": [{"bpm": 120, "bar": 0}],
           "meters": [{"den": 4, "num": 4, "bar": 0}], "pickup": 0, "ppq": 48, "v": 1}
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=SetScore(raw), view=None)
    assert out.score is not None
    assert list(out.score) == ["v", "ppq", "pickup", "meters", "tempos", "snap", "lines"]
    assert out.score["lines"][0]["notes"] == [{"s": 0, "t": 0, "d": 24, "a": 1}]
    assert isinstance(out.score["lines"][0]["notes"][0]["d"], int)
    assert out.score_view is False
    assert_invariants(out)


def test_set_score_keeps_extra_note_keys():
    s = stored("I go now", score=False)
    raw = score_with(s.grid, [(0, 0, 24)])
    raw["lines"][0]["notes"][0]["pitch"] = {"midi": 60}
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=SetScore(raw), view=True)
    assert out.score["lines"][0]["notes"][0]["pitch"] == {"midi": 60}
    assert out.score_view is True


@pytest.mark.parametrize(
    ("mutate", "reason"),
    [
        (lambda sc: sc.update(v=2), ScoreReason.unsupported_version),
        (lambda sc: sc.update(lines=[]), ScoreReason.line_count_mismatch),
        (lambda sc: sc.update(snap="1/4"), ScoreReason.snap_invalid),
        (lambda sc: sc["lines"][0]["notes"].append({"s": 9, "t": 500, "d": 1, "a": 0}), ScoreReason.syllable_out_of_range),
        (lambda sc: sc.pop("snap"), ScoreReason.malformed),
    ],
)
def test_set_invalid_score_raises_with_reason(mutate, reason):
    s = stored("I go now", score=False)
    raw = score_with(s.grid, [(0, 0, 24)])
    mutate(raw)
    with pytest.raises(ScoreInvalid) as info:
        saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=SetScore(raw), view=None)
    assert info.value.reason == reason


def test_set_non_object_is_malformed():
    s = stored("I go now", score=False)
    with pytest.raises(ScoreInvalid) as info:
        saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=SetScore([1, 2]), view=None)
    assert info.value.reason == ScoreReason.malformed


def test_set_score_overlap_reports_line_and_note():
    s = stored("I go now", score=False)
    raw = score_with(s.grid, [(0, 0, 24), (1, 12, 12)])
    with pytest.raises(ScoreInvalid) as info:
        saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=SetScore(raw), view=None)
    assert info.value.as_dict() == {"error": "invalid_score", "reason": "notes_overlap", "line": 0, "note": 1}


def test_set_score_writes_gaps_into_grid_and_text():
    s = stored("I go now", score=False)
    raw = score_with(s.grid, [(0, 0, 24), (1, 72, 24), (2, 96, 24)])
    out = saved_content(s, raw_text=s.raw_text, grid=s.grid, edit=SetScore(raw), view=None)
    assert rests(out.grid) == [False, True, False, False]
    # the rebuilt line is spelled from the cells (the syllabifier keeps no surface word)
    assert out.raw_text == "i _ go now"
    assert_invariants(out)


def test_set_score_removes_rests_it_closes():
    g = grid("I _ _ go now")
    s = ScoredContent("I _ _ go now", g, None, False)
    raw = score_with(g, [(0, 0, 24), (1, 24, 24), (2, 48, 24)])
    out = saved_content(s, raw_text=s.raw_text, grid=g, edit=SetScore(raw), view=None)
    assert rests(out.grid) == [False, False, False]
    assert out.raw_text == "i go now"
    assert_invariants(out)


def test_set_score_only_rewrites_changed_lines_and_keeps_blank_lines():
    text = "Hello World\n\n  I go now  \nLast LINE"
    g = grid(text)
    s = ScoredContent(text, g, None, False)
    raw = score_with(g, [], [(0, 200, 24), (1, 272, 24)], [])
    out = saved_content(s, raw_text=text, grid=g, edit=SetScore(raw), view=None)
    assert out.raw_text == "Hello World\n\ni _ go now\nLast LINE"
    assert out.grid["lines"][0] is g["lines"][0] or out.grid["lines"][0] == g["lines"][0]
    assert_invariants(out)


def test_set_score_with_unplaced_neighbours_leaves_rests_alone():
    g = grid("I _ go now")
    s = ScoredContent("I _ go now", g, None, False)
    raw = score_with(g, [(1, 0, 24), (2, 24, 24)])  # "I" unplaced
    out = saved_content(s, raw_text=s.raw_text, grid=g, edit=SetScore(raw), view=None)
    assert out.grid == g and out.raw_text == "I _ go now"


def test_sent_score_is_validated_against_the_sent_grid_not_the_stored_one():
    s = stored("I go now")  # 3 syllables
    new_grid = grid("I go")
    raw = score_with(new_grid, [(0, 0, 24), (1, 24, 24)])
    out = saved_content(s, raw_text="I go", grid=new_grid, edit=SetScore(raw), view=None)
    assert notes(out) == [(0, 0, 24), (1, 24, 24)]
    stale = score_with(new_grid, [(0, 0, 24), (2, 48, 24)])
    with pytest.raises(ScoreInvalid) as info:
        saved_content(s, raw_text="I go", grid=new_grid, edit=SetScore(stale), view=None)
    assert info.value.reason == ScoreReason.syllable_out_of_range


def test_sent_score_wins_over_a_rest_only_grid_edit():
    s = stored("I go now")  # draft: I 0, go 24, now 48 (eighths, no rests)
    new_grid = grid("I _ go now")
    raw = score_with(new_grid, [(0, 0, 24), (1, 24, 24), (2, 48, 24)])
    out = saved_content(s, raw_text="I _ go now", grid=new_grid, edit=SetScore(raw), view=None)
    # the score says no gap after "I", so the rest the grid added is linked away
    assert notes(out) == [(0, 0, 24), (1, 24, 24), (2, 48, 24)]
    assert rests(out.grid) == [False, False, False]
    assert out.raw_text == "i go now"


# ---- keep: rest-only grid edits move the notes ----


def test_rest_inserted_in_grid_moves_later_notes_by_a_beat():
    s = stored("I go now")  # I 0-24, go 24-48, now 48-72
    new_grid = grid("I go _ _ now")
    out = saved_content(s, raw_text="I go _ _ now", grid=new_grid, edit=KeepScore(), view=None)
    assert notes(out) == [(0, 0, 24), (1, 24, 24), (2, 144, 24)]
    assert out.grid == new_grid and out.raw_text == "I go _ _ now"
    assert_invariants(out)


def test_rest_removed_in_grid_is_clamped_to_closing_the_gap():
    g = grid("I _ _ go now")
    sc = score_with(g, [(0, 0, 24), (1, 48, 24), (2, 72, 24)])
    s = ScoredContent("I _ _ go now", g, sc, True)
    new_grid = grid("I go now")
    out = saved_content(s, raw_text="I go now", grid=new_grid, edit=KeepScore(), view=None)
    assert notes(out) == [(0, 0, 24), (1, 24, 24), (2, 48, 24)]
    assert out.grid == new_grid and out.raw_text == "I go now" and out.score_view is True


def test_rest_only_edit_ripples_into_the_next_line():
    s = stored("I go now\nhello world")  # line 1 starts at bar 1 = 192
    new_grid = grid("I go _ _ _ _ _ now\nhello world")
    out = saved_content(s, raw_text="I go _ _ _ _ _ now\nhello world", grid=new_grid, edit=KeepScore(), view=None)
    assert notes(out, 0) == [(0, 0, 24), (1, 24, 24), (2, 288, 24)]
    assert notes(out, 1)[0][1] == 312  # pushed right just enough
    assert_invariants(out)


def test_rest_only_edit_next_to_an_unplaced_syllable_moves_nothing_and_stays():
    g = grid("I go now")
    sc = score_with(g, [(0, 0, 24), (2, 48, 24)])  # "go" unplaced
    s = ScoredContent("I go now", g, sc, False)
    new_grid = grid("I _ go now")
    out = saved_content(s, raw_text="I _ go now", grid=new_grid, edit=KeepScore(), view=None)
    assert notes(out) == [(0, 0, 24), (2, 48, 24)]
    assert out.grid == new_grid and out.raw_text == "I _ go now"


def test_rest_only_edit_past_max_ticks_is_refused():
    g = grid("I go")
    last = 393216 - 24
    sc = score_with(g, [(0, last - 24, 24), (1, last, 24)])
    s = ScoredContent("I go", g, sc, False)
    with pytest.raises(ScoreInvalid) as info:
        saved_content(s, raw_text="I _ go", grid=grid("I _ go"), edit=KeepScore(), view=None)
    assert info.value.reason == ScoreReason.exceeds_max_ticks


def test_rest_only_edit_with_leading_and_trailing_rests_keeps_them():
    s = stored("I go now")
    new_grid = grid("_ I go now _")
    out = saved_content(s, raw_text="_ I go now _", grid=new_grid, edit=KeepScore(), view=None)
    assert notes(out) == notes(s)
    assert out.grid == new_grid and out.raw_text == "_ I go now _"


# ---- keep: other grid changes reconcile ----


def test_word_inserted_keeps_matched_notes_and_unplaces_the_new_word():
    s = stored("hello world")  # hel 0, lo 24, world 48
    new_grid = grid("hello big world")
    out = saved_content(s, raw_text="hello big world", grid=new_grid, edit=KeepScore(), view=None)
    assert notes(out) == [(0, 0, 24), (1, 24, 24), (3, 48, 24)]
    # big is unplaced: the rests between placed "lo" and unplaced "big" are not linked
    assert out.grid == new_grid and out.raw_text == "hello big world"
    assert_invariants(out)


def test_changed_word_loses_its_notes():
    s = stored("hello world")
    out = saved_content(s, raw_text="hellos world", grid=grid("hellos world"), edit=KeepScore(), view=None)
    assert [n[0] for n in notes(out)] == [2]


def test_line_added_gets_no_notes_and_line_count_follows():
    s = stored("I go now")
    text = "I go now\nhello world"
    out = saved_content(s, raw_text=text, grid=grid(text), edit=KeepScore(), view=None)
    assert len(out.score["lines"]) == 2 and out.score["lines"][1]["notes"] == []
    assert notes(out, 0) == notes(s)
    assert_invariants(out)


def test_line_removed_drops_its_notes():
    s = stored("I go now\nhello world")
    out = saved_content(s, raw_text="hello world", grid=grid("hello world"), edit=KeepScore(), view=None)
    assert len(out.score["lines"]) == 1
    assert [n[1] for n in notes(out)] == [192, 216, 240]  # times kept
    assert_invariants(out)


def test_everything_deleted_keeps_an_empty_score():
    s = stored("I go now", view=True)
    out = saved_content(s, raw_text="", grid={"lines": []}, edit=KeepScore(), view=None)
    assert out.score is not None and out.score["lines"] == [] and out.score_view is True


def test_text_edit_with_rest_gap_in_score_writes_rest_back():
    g = grid("I _ go now")
    s = ScoredContent("I _ go now", g, draft(g), False)  # I 0, rest to 72, go 72, now 96
    out = saved_content(s, raw_text="I go now there", grid=grid("I go now there"), edit=KeepScore(), view=None)
    # not rest-only (a word was added): reconcile keeps I/go/now, then the link puts the rest back
    assert rests(out.grid) == [False, True, False, False, False]
    assert out.raw_text == "i _ go now there"
    assert_invariants(out)


# ---- stored score broken ----


def test_stored_score_that_does_not_fit_the_grid_is_an_internal_error():
    g = grid("I go now")
    bad = ScoredContent("I go now", g, score_with(g, [(0, 0, 24)], []), False)  # two lines for one
    with pytest.raises(StoredScoreInvalid):
        saved_content(bad, raw_text="I go now", grid=g, edit=KeepScore(), view=None)


def test_stored_score_is_not_needed_when_removing_or_replacing():
    g = grid("I go now")
    bad = ScoredContent("I go now", g, {"v": 99}, False)
    assert saved_content(bad, raw_text="I go now", grid=g, edit=RemoveScore(), view=None).score is None
    raw = score_with(g, [(0, 0, 24)])
    assert saved_content(bad, raw_text="I go now", grid=g, edit=SetScore(raw), view=None).score is not None


# ---- text mismatch ----


def test_grid_line_without_text_line_keeps_the_sent_text_and_links_the_grid(caplog):
    g = grid("I go now\nhello world")
    raw = score_with(g, [], [(1, 0, 24), (2, 96, 24)])  # a rest between "lo" and "world"
    with caplog.at_level("WARNING", logger="app.services.score_save"):
        out = saved_content(ScoredContent("I go now", g, None, False), raw_text="I go now", grid=g, edit=SetScore(raw), view=None)
    assert out.raw_text == "I go now"
    assert rests(out.grid, 1) == [False, False, True, False]
    assert "score.text_mismatch" in caplog.text and "action=keep_text" in caplog.text


# ---- inputs are not mutated ----


def test_inputs_are_not_mutated():
    s = stored("I go now")
    before = copy.deepcopy(s)
    new_grid = grid("I go _ now")
    sent = copy.deepcopy(new_grid)
    saved_content(s, raw_text="I go _ now", grid=new_grid, edit=KeepScore(), view=None)
    assert s == before and new_grid == sent


# ---- new_content ----


def test_new_content_without_score():
    g = grid("I go now")
    out = new_content(raw_text="I go now", grid=g, raw_score=None, view=True)
    assert out == ScoredContent("I go now", g, None, False)


def test_new_content_with_score_links_rests():
    g = grid("I go now")
    raw = score_with(g, [(0, 0, 24), (1, 72, 24), (2, 96, 24)])
    out = new_content(raw_text="I go now", grid=g, raw_score=raw, view=True)
    assert out.score_view is True and rests(out.grid) == [False, True, False, False]
    assert out.raw_text == "i _ go now"
    assert_invariants(out)


def test_new_content_invalid_score_raises():
    with pytest.raises(ScoreInvalid) as info:
        new_content(raw_text="I go", grid=grid("I go"), raw_score={"v": 1}, view=False)
    assert info.value.reason == ScoreReason.malformed


def test_new_content_draft_round_trips_unchanged():
    g = grid("I _ go now\n_ hello _ world")
    text = "I _ go now\n_ hello _ world"
    out = new_content(raw_text=text, grid=g, raw_score=draft(g), view=False)
    assert out.grid == g and out.raw_text == text and out.score == draft(g)
