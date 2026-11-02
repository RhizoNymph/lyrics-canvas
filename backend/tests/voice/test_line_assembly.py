"""Line assembly: timed words + tap breaks -> lines (taps), or an auto split by pause, balance and rhyme."""

import random
import time

import pytest

from app.models.recording import SplitMode
from app.services.voice.line_assembly import (
    DEFAULT_PARAMS,
    AutoSplitParams,
    LineAssemblyError,
    TimedWord,
    assemble_lines,
    auto_boundaries,
    lines_from_boundaries,
    pause_strengths,
    tap_boundaries,
)
from tests.voice.timeline import FakeOracle, numbered, timeline

ORACLE = FakeOracle()


# ---- tap_boundaries ----
# Default timeline: word i spans [400 i, 400 i + 300]; boundary i (after word i) sits at 400 i + 350.


def test_tap_on_the_boundary_point_splits_there():
    assert tap_boundaries(timeline("a b c d"), [350]) == [0]
    assert tap_boundaries(timeline("a b c d"), [750]) == [1]


def test_late_tap_inside_the_next_line_resolves_to_the_previous_boundary():
    # 500 is 100 ms into word 1: 150 from boundary 0, 250 from boundary 1
    assert tap_boundaries(timeline("a b c d"), [500]) == [0]


def test_early_tap_inside_the_last_word_resolves_to_the_next_boundary():
    # 600 is inside word 1: 250 from boundary 0, 150 from boundary 1
    assert tap_boundaries(timeline("a b c d"), [600]) == [1]


def test_tie_between_two_boundaries_prefers_the_earlier_one():
    # 550 is exactly 200 from boundary 0 (350) and boundary 1 (750)
    assert tap_boundaries(timeline("a b c d"), [550]) == [0]


def test_half_millisecond_tie_with_odd_gap_prefers_the_earlier_one():
    # boundary 0 at (300 + 401) / 2 = 350.5, boundary 1 at (701 + 802) / 2 = 751.5; 551 is 200.5 from both
    words = [TimedWord("a", 0, 300), TimedWord("b", 401, 701), TimedWord("c", 802, 1100)]
    assert tap_boundaries(words, [551]) == [0]


def test_breaks_before_the_first_word_are_ignored():
    words = timeline("a b c", gap_ms=100)
    shifted = [TimedWord(w.text, w.start_ms + 1000, w.end_ms + 1000) for w in words]
    assert tap_boundaries(shifted, [0, 500, 999, 1000]) == []


def test_breaks_after_the_last_word_are_ignored():
    words = timeline("a b c")  # last word ends at 1100
    assert tap_boundaries(words, [1100, 1500, 99999]) == []


def test_breaks_inside_the_range_survive_alongside_ignored_ones():
    assert tap_boundaries(timeline("a b c"), [0, 360, 5000]) == [0]


def test_duplicate_breaks_resolving_to_the_same_boundary_count_once():
    assert tap_boundaries(timeline("a b c d"), [340, 350, 360, 500]) == [0]


def test_close_breaks_on_different_boundaries_both_split():
    assert tap_boundaries(timeline("a b c d"), [350, 750]) == [0, 1]


def test_unsorted_breaks_are_handled():
    assert tap_boundaries(timeline("a b c d e"), [1150, 350]) == [0, 2]


def test_single_word_has_no_boundaries():
    assert tap_boundaries(timeline("a"), [100, 200]) == []


def test_overlapping_word_timestamps_still_resolve():
    # word 1 starts before word 0 ends: boundary 0 at (300 + 250) / 2 = 275
    words = [TimedWord("a", 0, 300), TimedWord("b", 250, 600), TimedWord("c", 700, 900)]
    assert tap_boundaries(words, [280]) == [0]
    assert tap_boundaries(words, [640]) == [1]


# ---- lines_from_boundaries ----


def test_lines_from_boundaries_joins_words_with_single_spaces():
    words = timeline("hello, world and more")
    assert lines_from_boundaries(words, []) == ("hello, world and more",)
    assert lines_from_boundaries(words, [1]) == ("hello, world", "and more")
    assert lines_from_boundaries(words, [0, 1, 2]) == ("hello,", "world", "and", "more")


# ---- assemble_lines with taps ----


def test_taps_split_mode_and_lines():
    result = assemble_lines(timeline("a b c d"), [750], ORACLE)
    assert result.lines == ("a b", "c d")
    assert result.split_mode == SplitMode.taps


def test_taps_never_produce_empty_lines():
    rng = random.Random(7)
    for _ in range(200):
        n = rng.randint(1, 30)
        words = timeline(numbered(n), gap_ms=rng.randint(0, 400))
        end = words[-1].end_ms
        breaks = sorted(rng.randint(-200, end + 200) for _ in range(rng.randint(1, 40)))
        result = assemble_lines(words, breaks, ORACLE)
        assert all(line.strip() for line in result.lines)
        assert " ".join(result.lines).split() == [w.text for w in words]


def test_taps_win_over_auto_split():
    # a long pause after word 0 would attract the auto split; the tap after word 2 decides instead
    words = timeline(numbered(12), pauses={0: 900})
    result = assemble_lines(words, [words[2].end_ms + 50], ORACLE)
    assert result.split_mode == SplitMode.taps
    assert result.lines == ("w0 w1 w2", "w3 w4 w5 w6 w7 w8 w9 w10 w11")


def test_all_breaks_ignored_falls_back_to_auto():
    words = timeline(numbered(12), pauses={5: 700})
    result = assemble_lines(words, [words[-1].end_ms + 10], ORACLE)
    assert result.split_mode == SplitMode.auto
    assert result.lines == ("w0 w1 w2 w3 w4 w5", "w6 w7 w8 w9 w10 w11")


def test_all_breaks_ignored_on_a_short_take_is_single():
    result = assemble_lines(timeline("a b"), [-5, 99999], ORACLE)
    assert result.split_mode == SplitMode.single
    assert result.lines == ("a b",)


def test_blank_words_are_dropped():
    words = [TimedWord("  ", 0, 10), TimedWord("a", 20, 300), TimedWord("", 310, 320), TimedWord("b", 400, 700)]
    result = assemble_lines(words, [350], ORACLE)
    assert result.lines == ("a", "b")


def test_word_text_is_trimmed():
    result = assemble_lines([TimedWord(" a ", 0, 300), TimedWord("b ", 400, 700)], [350], ORACLE)
    assert result.lines == ("a", "b")


def test_no_words_is_an_error():
    with pytest.raises(LineAssemblyError):
        assemble_lines([], [], ORACLE)
    with pytest.raises(LineAssemblyError):
        assemble_lines([TimedWord(" ", 0, 10)], [], ORACLE)


# ---- pause_strengths ----


def test_pause_strength_is_relative_to_the_median_gap_and_saturates():
    words = timeline(numbered(5), pauses={1: 100 + DEFAULT_PARAMS.pause_saturation_ms // 2, 2: 5000})
    strengths = pause_strengths(words, DEFAULT_PARAMS)
    assert strengths[0] == 0.0
    assert strengths[1] == pytest.approx(0.5)
    assert strengths[2] == 1.0
    assert strengths[3] == 0.0


def test_pause_strength_never_negative_for_overlaps():
    words = [TimedWord("a", 0, 300), TimedWord("b", 200, 500), TimedWord("c", 600, 900), TimedWord("d", 1000, 1200)]
    assert min(pause_strengths(words, DEFAULT_PARAMS)) >= 0.0


# ---- auto split ----


def test_short_take_stays_single_even_with_a_long_pause():
    words = timeline(numbered(6), pauses={2: 2000})
    result = assemble_lines(words, [], ORACLE)
    assert result.split_mode == SplitMode.single
    assert len(result.lines) == 1


def test_too_few_words_stay_single_even_when_long():
    oracle = FakeOracle(syllables={"w0": 6, "w1": 6, "w2": 6})
    result = assemble_lines(timeline(numbered(3), pauses={0: 2000}), [], oracle)
    assert result.split_mode == SplitMode.single


def test_uniform_delivery_without_rhyme_stays_single():
    result = assemble_lines(timeline(numbered(12)), [], ORACLE)
    assert result.split_mode == SplitMode.single
    assert result.lines == (numbered(12),)


def test_long_pause_splits_a_balanced_take():
    words = timeline(numbered(12), pauses={5: 700})
    result = assemble_lines(words, [], ORACLE)
    assert result.split_mode == SplitMode.auto
    assert result.lines == ("w0 w1 w2 w3 w4 w5", "w6 w7 w8 w9 w10 w11")


def test_a_clear_pause_beats_perfect_balance():
    # 4 | 8 at a real pause beats 4 | 4 | 4 with a split where the speaker did not pause
    words = timeline(numbered(12), pauses={3: 700})
    assert auto_boundaries(words, ORACLE) == [3]


def test_balance_chooses_between_equal_pauses():
    words = timeline(numbered(12), pauses={2: 700, 5: 700})
    assert auto_boundaries(words, ORACLE) == [5]


def test_balance_counts_syllables_not_words():
    # w0 has six syllables: after w0 is 6 | 6, after w3 is 9 | 3
    oracle = FakeOracle(syllables={"w0": 6})
    words = timeline(numbered(7), pauses={0: 700, 3: 700})
    assert auto_boundaries(words, oracle) == [0]


def test_end_rhyme_moves_the_split():
    words = timeline(numbered(12), pauses={4: 400, 5: 400})
    assert auto_boundaries(words, ORACLE) == [5]
    rhyming = FakeOracle(rhymes=[("w4", "w11")])
    assert auto_boundaries(words, rhyming) == [4]


def test_rhyme_below_the_threshold_gives_no_bonus():
    class Weak(FakeOracle):
        def end_rhyme(self, a: str, b: str) -> float:
            return DEFAULT_PARAMS.rhyme_min_score - 0.01 if {a, b} == {"w4", "w11"} else 0.0

    words = timeline(numbered(12), pauses={4: 400, 5: 400})
    assert auto_boundaries(words, Weak()) == [5]


def test_identical_end_words_get_a_reduced_bonus():
    assert 0 < DEFAULT_PARAMS.identical_rhyme_factor < 1
    # equal pauses after word 3 (4 | 8) and word 5 (6 | 6): a full rhyme bonus pulls the split to
    # word 3, the reduced bonus for the same word repeated does not
    pauses = {3: 400, 5: 400}
    rhyming = timeline("a b c night e f g h i j k light", pauses=pauses)
    assert auto_boundaries(rhyming, FakeOracle(rhymes=[("night", "light")])) == [3]
    repeated = timeline("a b c night e f g h i j k night", pauses=pauses)
    assert auto_boundaries(repeated, FakeOracle(rhymes=[("night", "night")])) == [5]


def test_very_long_uniform_take_is_split_into_several_lines():
    words = timeline(numbered(60))
    result = assemble_lines(words, [], ORACLE)
    assert result.split_mode == SplitMode.auto
    assert len(result.lines) >= 3
    assert all(len(line.split()) <= DEFAULT_PARAMS.max_line_syllables for line in result.lines)
    assert " ".join(result.lines) == numbered(60)


def test_an_overlong_single_word_is_still_a_line():
    oracle = FakeOracle(syllables={"w3": 60})
    words = timeline(numbered(8), pauses={2: 700, 3: 700})
    result = assemble_lines(words, [], oracle)
    assert " ".join(result.lines) == numbered(8)
    assert all(line.strip() for line in result.lines)


def test_weights_are_tunable():
    # uniform delivery stays one line by default; a tiny line-length budget forces splits
    words = timeline(numbered(12))
    assert auto_boundaries(words, ORACLE) == []
    params = AutoSplitParams(min_line_syllables=1, max_line_syllables=3, overlong_penalty=2.0)
    assert len(auto_boundaries(words, ORACLE, params)) >= 3


def test_auto_split_is_deterministic():
    words = timeline(numbered(40), pauses={7: 500, 15: 450, 23: 600, 31: 520})
    first = auto_boundaries(words, ORACLE)
    assert all(auto_boundaries(words, ORACLE) == first for _ in range(3))


def test_auto_split_never_produces_empty_lines():
    rng = random.Random(11)
    for _ in range(100):
        n = rng.randint(1, 50)
        pauses = {rng.randrange(max(n - 1, 1)): rng.randint(0, 1500) for _ in range(rng.randint(0, 6))}
        words = timeline(numbered(n), gap_ms=rng.randint(20, 200), pauses=pauses)
        result = assemble_lines(words, [], ORACLE)
        assert all(line.strip() for line in result.lines)
        assert " ".join(result.lines) == numbered(n)
        assert (len(result.lines) == 1) == (result.split_mode == SplitMode.single)


def test_auto_split_of_a_long_take_is_fast():
    words = timeline(numbered(2000), pauses={i: 600 for i in range(9, 2000, 10)})
    started = time.perf_counter()
    lines = assemble_lines(words, [], ORACLE).lines
    assert time.perf_counter() - started < 5.0
    assert len(lines) > 100


# ---- with the real phonetic engine ----


def test_engine_oracle_counts_syllables_and_scores_rhymes():
    from app.services.voice.engine_oracle import EngineOracle

    oracle = EngineOracle()
    assert oracle.syllables("tomorrow") == 3
    assert oracle.syllables("cat,") == 1
    assert oracle.syllables("...") == 0
    assert oracle.end_rhyme("cat", "mat.") >= DEFAULT_PARAMS.rhyme_min_score
    assert oracle.end_rhyme("cat", "dog") < DEFAULT_PARAMS.rhyme_min_score
    assert oracle.end_rhyme("cat", "...") == 0.0


def test_engine_split_of_a_rhyming_couplet():
    from app.services.voice.engine_oracle import EngineOracle

    words = timeline("I saw a black cat sleeping on the mat", gap_ms=80, pauses={4: 500})
    result = assemble_lines(words, [], EngineOracle())
    assert result.split_mode == SplitMode.auto
    assert result.lines == ("I saw a black cat", "sleeping on the mat")
