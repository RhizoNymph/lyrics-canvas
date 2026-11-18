"""draft_from_recording with synthetic takes: steady, triplet flow, pickups, rubato, missing and
extra words, appended takes, interleaved words. Every result is valid against the grid and the rest
link turns its gaps into rest cells."""

from __future__ import annotations

import pytest

from app.services.score import ScoreInvalid, apply_rest_link, check_score, score_to_json
from app.services.score.grid_view import grid_lines, line_cells, rests_between
from app.services.score_tools.draft import draft_from_recording
from app.services.score_tools.errors import DraftEmptyError
from app.services.voice.line_assembly import TimedWord
from scripts.score_vector_random import Lcg, random_grid
from tests.score.helpers import grid, line_from_cells
from tests.score_tools.takes import sine_tempo, steady, timings, warped

FOUR_FOUR = {"num": 4, "den": 4}


def notes(result, line: int = 0) -> list[tuple[int, int, int, int]]:
    return [(n.s, n.t, n.d, n.a) for n in result.score.lines[line].notes]


def assert_sound(result, g) -> None:
    """Valid against the grid, and the rest link is idempotent on the draft."""
    as_json = score_to_json(result.score)
    check_score(as_json, g)
    linked = apply_rest_link(result.score, g)
    assert apply_rest_link(result.score, linked) == linked
    check_score(as_json, linked)


# ---- steady 90 bpm ----

STEADY_TEXT = "^I ^got ^the ^beat ^and ^I ^won't ^stop"
STEADY_WORDS = STEADY_TEXT.replace("^", "").split()
STEADY_ONSETS = [0, 2, 3, 4, 8, 10, 11, 12]  # sixteenths
STEADY_LENGTHS = [2, 1, 1, 3, 2, 1, 1, 4]


@pytest.mark.parametrize("bpm", [90, None])
@pytest.mark.parametrize("jitter", [0, 15, 30])
def test_steady_take_at_90(bpm, jitter):
    g = grid(STEADY_TEXT)
    take = timings(steady(STEADY_WORDS, STEADY_ONSETS, STEADY_LENGTHS, jitter_ms=jitter))
    result = draft_from_recording(g, take, FOUR_FOUR, bpm)
    assert [(t, d) for _, t, d, _ in notes(result)] == [(12 * o, 12 * n) for o, n in zip(STEADY_ONSETS, STEADY_LENGTHS)]
    assert [s for s, *_ in notes(result)] == list(range(8))
    assert all(a == 1 for *_, a in notes(result))  # stress 1 -> accent 1
    assert result.score.pickup == 0
    assert result.score.snap.value == "1/16"
    assert result.unplaced == 0 and result.unmatched_words == 0
    if bpm is None:
        assert result.bpm_estimated is not None and abs(result.bpm_estimated - 90) <= 1
        assert result.score.tempos[0].bpm == result.bpm_estimated
    else:
        assert result.bpm_estimated is None and result.score.tempos[0].bpm == 90
    assert_sound(result, g)


def test_steady_take_gap_becomes_rest_cells():
    # "beat" ends at 7, "and" starts at 8: gap 12 ticks < beat unit 48 -> 0 rests.
    # Make a one-beat pause between "beat" and "and": onsets shift by 4 sixteenths.
    g = grid(STEADY_TEXT)
    onsets = [0, 2, 3, 4, 12, 14, 15, 16]
    lengths = [2, 1, 1, 4, 2, 1, 1, 4]
    result = draft_from_recording(g, timings(steady(STEADY_WORDS, onsets, lengths)), FOUR_FOUR, 90)
    linked = apply_rest_link(result.score, g)
    # beat ends at 8 sixteenths (96 ticks), "and" starts at 144: gap 48 -> one rest
    assert rests_between(line_cells(grid_lines(linked)[0])) == [0, 0, 0, 1, 0, 0, 0]
    assert grid_lines(linked)[0]["raw_text"] == "I got the beat _ and I won't stop"


def test_draft_matching_existing_rests_leaves_the_grid_unchanged():
    g = grid("^I ^got _ ^this")
    take = timings(steady(["I", "got", "this"], [0, 4, 12], [4, 4, 4]))  # got ends at 8, this at 12: one beat gap
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert apply_rest_link(result.score, g) == g


def test_existing_rests_follow_the_take_when_the_gap_differs():
    g = grid("^I ^got _ _ ^this")
    take = timings(steady(["I", "got", "this"], [0, 4, 12], [4, 4, 4]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    linked = apply_rest_link(result.score, g)
    assert rests_between(line_cells(grid_lines(linked)[0])) == [0, 1]


# ---- triplet flow ----

TRIPLET_TEXT = " ".join(["^la", "la", "la"] * 4)
TRIPLET_WORDS = ["la"] * 12


def test_triplet_flow_with_the_tempo_given():
    g = grid(TRIPLET_TEXT)
    take = timings(steady(TRIPLET_WORDS, list(range(12)), [1] * 12, per_beat=6))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    # sixteenth triplets: 8 ticks apart
    assert [t for _, t, _, _ in notes(result)] == [8 * i for i in range(12)]
    assert result.score.snap.value == "1/16t"
    assert_sound(result, g)


def test_eighth_triplet_flow_with_jitter():
    g = grid(TRIPLET_TEXT)
    take = timings(steady(TRIPLET_WORDS, [2 * i for i in range(12)], [2] * 12, per_beat=6, jitter_ms=10))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert [t for _, t, _, _ in notes(result)] == [16 * i for i in range(12)]
    assert [d for _, _, d, _ in notes(result)] == [16] * 11 + [16]
    assert result.score.snap.value == "1/16t"


def test_triplet_flow_without_a_tempo_is_consistent():
    # sixteenth triplets at 90 are straight sixteenths at 135: either reading keeps equal spacing
    g = grid(TRIPLET_TEXT)
    take = timings(steady(TRIPLET_WORDS, list(range(12)), [1] * 12, per_beat=6))
    result = draft_from_recording(g, take, FOUR_FOUR, None)
    starts = [t for _, t, _, _ in notes(result)]
    spacing = starts[1] - starts[0]
    assert spacing in (8, 12)
    assert starts == [spacing * i for i in range(12)]
    assert result.bpm_estimated == (135 if spacing == 12 else 90)


def test_straight_then_triplet_bars():
    g = grid(" ".join(["^da"] * 4 + ["^la"] * 6))
    # one beat of four sixteenths (at 90: 166.7 ms), then two beats of sixteenth triplets
    straight = steady(["da"] * 4, [0, 1, 2, 3], [1] * 4)
    triplets = steady(["la"] * 6, [6 + i for i in range(6)], [1] * 6, per_beat=6)
    result = draft_from_recording(g, timings(straight + triplets), FOUR_FOUR, 90)
    assert [t for _, t, _, _ in notes(result)] == [0, 12, 24, 36, 48, 56, 64, 72, 80, 88]


# ---- pickups ----

def test_unstressed_first_word_is_a_pickup():
    g = grid("the ^night is ^young")
    take = timings(steady(["the", "night", "is", "young"], [0, 1, 3, 4], [1, 2, 1, 4]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert result.score.pickup == 12
    assert [t for _, t, _, _ in notes(result)] == [0, 12, 36, 48]
    assert [a for *_, a in notes(result)] == [0, 1, 0, 1]
    assert_sound(result, g)


def test_two_syllable_pickup_in_three_four():
    g = grid("and the ^morn-ing ^came")
    take = timings(steady(["and", "the", "morning", "came"], [0, 2, 4, 8], [2, 2, 4, 4]))
    result = draft_from_recording(g, take, {"num": 3, "den": 4}, 90)
    assert result.score.pickup == 48  # "and the" = two eighths
    assert result.score.meters[0].num == 3
    assert_sound(result, g)


def test_stressed_first_word_starts_bar_zero():
    g = grid("^go ^on")
    result = draft_from_recording(g, timings(steady(["go", "on"], [0, 4], [2, 2])), FOUR_FOUR, 90)
    assert result.score.pickup == 0


def test_long_unstressed_run_has_no_pickup():
    # the first stressed syllable is more than a bar after the first onset
    g = grid("a a a ^go")
    take = timings(steady(["a", "a", "a", "go"], [0, 6, 12, 18], [1, 1, 1, 2]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert result.score.pickup == 0


def test_pickup_in_six_eight():
    g = grid("a ^sail-or ^went")
    take = timings(steady(["a", "sailor", "went"], [0, 2, 8], [2, 4, 2]))
    result = draft_from_recording(g, take, {"num": 6, "den": 8}, 90)
    assert result.score.pickup == 24
    assert result.score.pickup < result.score.meters[0].bar_length
    assert_sound(result, g)


# ---- rubato ----

RUBATO_PATTERN = [0, 1, 2, 4, 6, 7, 8, 10, 12, 14]
RUBATO_ONSETS = [bar * 16 + p for bar in range(4) for p in RUBATO_PATTERN]


@pytest.mark.parametrize("depth", [0.04, 0.08, 0.12])
def test_rubato_keeps_the_rhythm_with_the_tempo_given(depth):
    texts = [f"la{i}" for i in range(len(RUBATO_ONSETS))]
    g = grid(" ".join("^" + t for t in texts))
    take = timings(warped(texts, RUBATO_ONSETS, [1] * len(texts), sine_tempo(90, depth), jitter_ms=10))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert [t for _, t, _, _ in notes(result)] == [12 * o for o in RUBATO_ONSETS]
    assert_sound(result, g)


def test_rubato_with_an_estimated_tempo():
    texts = [f"la{i}" for i in range(len(RUBATO_ONSETS))]
    g = grid(" ".join("^" + t for t in texts))
    take = timings(warped(texts, RUBATO_ONSETS, [1] * len(texts), sine_tempo(90, 0.06)))
    result = draft_from_recording(g, take, FOUR_FOUR, None)
    assert abs(result.bpm_estimated - 90) <= 2
    assert [t for _, t, _, _ in notes(result)] == [12 * o for o in RUBATO_ONSETS]


def test_accelerating_take_keeps_quarters():
    texts = [f"go{i}" for i in range(12)]
    g = grid(" ".join("^" + t for t in texts))
    words, t = [], 0.0
    for i, text in enumerate(texts):
        words.append(TimedWord(text, int(t), int(t + 250)))
        t += 60000 / (90 + 20 * i / 11)
    result = draft_from_recording(g, timings(words), FOUR_FOUR, 90)
    assert [t for _, t, _, _ in notes(result)] == [48 * i for i in range(12)]


# ---- missing and extra words ----

def test_missing_word_is_unplaced():
    g = grid(STEADY_TEXT)
    keep = [i for i in range(8) if i != 2]  # the take skips "the"
    take = timings(steady([STEADY_WORDS[i] for i in keep], [STEADY_ONSETS[i] for i in keep], [STEADY_LENGTHS[i] for i in keep]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert [s for s, *_ in notes(result)] == keep
    assert result.unplaced == 1 and result.unmatched_words == 0
    assert_sound(result, g)


def test_missing_multi_syllable_word_counts_each_syllable():
    g = grid("^I ^love ^beau-ti-ful ^days")
    take = timings(steady(["I", "love", "days"], [0, 2, 8], [2, 2, 4]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert [s for s, *_ in notes(result)] == [0, 1, 5]
    assert result.unplaced == 3


def test_extra_words_are_counted_and_skipped():
    g = grid("^I ^got ^this")
    take = timings(steady(["yeah", "I", "got", "uh", "this"], [0, 4, 6, 8, 10], [2, 2, 2, 2, 2]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert result.unmatched_words == 2 and result.unplaced == 0
    # tick 0 is the first placed syllable ("I"), the extra words are not notes
    assert [t for _, t, _, _ in notes(result)] == [0, 24, 72]


def test_misheard_word_is_paired_by_position():
    g = grid("^so ^are ^we")
    take = timings(steady(["so", "are", "wee"], [0, 2, 4], [2, 2, 4]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert [s for s, *_ in notes(result)] == [0, 1, 2]
    assert result.unmatched_words == 0


def test_punctuation_and_case_do_not_matter():
    g = grid("^Hey, ^you!")
    take = timings(steady(["hey", "You"], [0, 4], [2, 2]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert result.unplaced == 0


def test_no_matching_word_is_draft_empty():
    g = grid("^one ^two ^three")
    with pytest.raises(DraftEmptyError):
        draft_from_recording(g, timings(steady(["x", "y"], [0, 2], [1, 1])), FOUR_FOUR, 90)


def test_empty_grid_is_draft_empty():
    with pytest.raises(DraftEmptyError):
        draft_from_recording(None, timings(steady(["x"], [0], [1])), FOUR_FOUR, 90)


# ---- syllables, lines, appended takes ----

def test_multi_syllable_word_split_by_vowels():
    g = grid("^beau-ti-ful")
    take = timings([TimedWord("beautiful", 0, 1000)])  # weights 3:1:1 -> 600, 200, 200 ms
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    # 90 bpm: 600 ms = 43.2 ticks -> 48 (snapped), 200 ms -> 14.4
    assert [(s, t) for s, t, _, _ in notes(result)] == [(0, 0), (1, 48), (2, 60)]
    assert [d for _, _, d, _ in notes(result)][:2] == [48, 12]  # contiguous syllables are legato
    assert [a for *_, a in notes(result)] == [1, 0, 0]


def test_take_across_two_lines_keeps_line_order():
    g = grid("^the ^night", "^is ^young")
    words = steady(["the", "night", "is", "young"], [0, 2, 8, 10], [2, 2, 2, 4])
    result = draft_from_recording(g, timings(words, [(0, 2), (2, 4)]), FOUR_FOUR, 90)
    assert notes(result, 0) == [(0, 0, 24, 1), (1, 24, 24, 1)]
    assert notes(result, 1) == [(0, 96, 24, 1), (1, 120, 48, 1)]
    assert_sound(result, g)


def test_appended_take_places_only_its_lines():
    g = grid("^old ^words ^here", "^old ^words", "^new ^line")
    take = timings(steady(["old", "words", "new", "line"], [0, 2, 8, 10], [2, 2, 2, 2]), [(0, 2), (2, 4)])
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert notes(result, 0) == []
    assert [s for s, *_ in notes(result, 1)] == [0, 1]  # the latest "old words"
    assert [s for s, *_ in notes(result, 2)] == [0, 1]
    assert result.unplaced == 3


def test_rest_only_line_gets_no_notes():
    g = {"lines": [line_from_cells([{"rest": True}]), grid("^hi").get("lines")[0]]}
    result = draft_from_recording(g, timings(steady(["hi"], [0], [2])), FOUR_FOUR, 90)
    assert notes(result, 0) == [] and len(notes(result, 1)) == 1


def test_interleaved_word_syllables_stay_valid():
    # after a chunk drag, word 0's syllables sit at ordinals 0 and 2, word 1 at ordinal 1
    cells = [
        {"syllable": "hel", "stress": 1, "word_index": 0, "word": "hello"},
        {"syllable": "big", "stress": 1, "word_index": 1, "word": "big"},
        {"syllable": "lo", "stress": 0, "word_index": 0, "word": "hello"},
    ]
    g = {"lines": [line_from_cells(cells)]}
    take = timings(steady(["hello", "big"], [0, 4], [2, 2]))
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert_sound(result, g)
    assert result.unplaced == 1  # "lo" would start before "big" ends in score order


def test_overlapping_and_out_of_order_stt_words():
    g = grid("^a ^b ^c")
    take = timings([TimedWord("a", 100, 500), TimedWord("b", 90, 200), TimedWord("c", 400, 300)])
    result = draft_from_recording(g, take, FOUR_FOUR, 90)
    assert_sound(result, g)
    assert result.unplaced == 0


# ---- arguments ----

@pytest.mark.parametrize("meter", [{"num": 0, "den": 4}, {"num": 4, "den": 3}, {"num": 4}, {"num": "4", "den": 4}])
def test_invalid_meter(meter):
    with pytest.raises(ScoreInvalid) as info:
        draft_from_recording(grid("^a"), timings(steady(["a"], [0], [1])), meter, 90)
    assert info.value.reason.value == "meter_invalid"


@pytest.mark.parametrize("bpm", [19, 401, 90.001, True, "90"])
def test_invalid_bpm(bpm):
    with pytest.raises(ScoreInvalid) as info:
        draft_from_recording(grid("^a"), timings(steady(["a"], [0], [1])), FOUR_FOUR, bpm)
    assert info.value.reason.value == "tempo_invalid"


def test_decimal_bpm_is_kept():
    result = draft_from_recording(grid("^a ^b"), timings(steady(["a", "b"], [0, 2], [1, 1], bpm=92.5)), FOUR_FOUR, 92.5)
    assert result.score.tempos[0].bpm == 92.5


# ---- properties over random grids ----

@pytest.mark.parametrize("seed", range(60))
def test_random_grids_and_takes_are_always_valid(seed):
    rng = Lcg(seed * 104729 + 3)
    g = random_grid(rng)
    words: list[TimedWord] = []
    t = rng.below(500)
    for line in grid_lines(g):
        spelled: dict[int, str] = {}
        for cell in line_cells(line):
            if cell.get("rest") is True:
                t += 150
                continue
            index = cell.get("word_index")
            if isinstance(index, int) and index not in spelled:
                spelled[index] = cell.get("word") or cell.get("syllable") or ""
        for text in spelled.values():
            if rng.below(10) == 0:
                continue  # a missing word
            if rng.below(10) == 0:
                words.append(TimedWord("uh", t, t + 80))
                t += 100
            length = 80 + rng.below(400)
            words.append(TimedWord(text, t, t + length))
            t += length + rng.below(300)
    if not words:
        return
    meter = [{"num": 4, "den": 4}, {"num": 3, "den": 4}, {"num": 6, "den": 8}, {"num": 7, "den": 16}][seed % 4]
    try:
        result = draft_from_recording(g, timings(words), meter, None if seed % 2 else 90)
    except DraftEmptyError:
        return
    assert_sound(result, g)
    placed = sum(len(line.notes) for line in result.score.lines)
    total = sum(sum(1 for c in line_cells(line) if c.get("rest") is not True) for line in grid_lines(g))
    assert result.unplaced == total - placed
