import pytest

from app.services.score.validate import is_valid_bpm
from app.services.score_tools.draft.tempo import DEFAULT_BPM, estimate_bpm, onset_intervals
from tests.score_tools.takes import sine_tempo, steady, warped

PATTERN = [0, 1, 2, 4, 6, 7, 8, 10, 12, 14]  # sixteenths within a bar


def pattern_onsets(bars: int) -> list[int]:
    return [bar * 16 + p for bar in range(bars) for p in PATTERN]


def starts(words) -> list[float]:
    return [w.start_ms for w in words]


def test_onset_intervals_lags_one_and_two_and_cap():
    assert sorted(onset_intervals([0, 100, 300, 2000]).tolist()) == [100, 200, 300]


def test_onset_intervals_ignore_duplicates():
    assert onset_intervals([0, 0, 100]).tolist() == [100]


@pytest.mark.parametrize("bpm", [70, 90, 100, 120, 140])
def test_steady_take_recovers_its_tempo(bpm):
    onsets = pattern_onsets(4)
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=bpm)
    assert estimate_bpm(starts(words)).bpm == bpm


@pytest.mark.parametrize("jitter", [10, 20, 30])
def test_jittered_take_lands_near_its_tempo(jitter):
    onsets = pattern_onsets(4)
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=90, jitter_ms=jitter)
    assert abs(estimate_bpm(starts(words)).bpm - 90) <= 1.5


def test_fast_take_reads_at_half_time():
    # eighths at 200 bpm are sixteenths at 100; 200 is outside 60..180
    onsets = [2 * o for o in pattern_onsets(4)]
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=200)
    assert estimate_bpm(starts(words)).bpm == 100


def test_fast_sixteenths_read_as_triplets():
    # sixteenths at 200 bpm (75 ms) are not sixteenths of any tempo in range; 133 1/3 has them as
    # sixteenth triplets
    onsets = pattern_onsets(4)
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=200)
    assert abs(estimate_bpm(starts(words)).bpm - 133.33) <= 0.5


@pytest.mark.parametrize(("bpm", "expected"), [(70, 70), (130, 130), (60, 120)])
def test_octave_ties_resolve_at_or_below_134(bpm, expected):
    # sixteenths at bpm are eighths at 2 * bpm
    onsets = pattern_onsets(3)
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=bpm)
    assert estimate_bpm(starts(words)).bpm == expected


def test_octave_tie_prefers_the_slower_tempo_near_95():
    # straight sixteenths at 90 fit 180 equally well; the prior picks 90
    onsets = pattern_onsets(2)
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=90)
    assert estimate_bpm(starts(words)).bpm == 90


def test_moderate_rubato_stays_near_the_base_tempo():
    onsets = pattern_onsets(4)
    words = warped([f"w{i}" for i in onsets], onsets, [1] * len(onsets), sine_tempo(90, 0.06))
    assert abs(estimate_bpm(starts(words)).bpm - 90) <= 2


@pytest.mark.parametrize("onsets", [[], [100], [100, 100]])
def test_too_few_onsets_fall_back_to_the_default(onsets):
    estimate = estimate_bpm(onsets)
    assert estimate.bpm == DEFAULT_BPM and estimate.intervals == 0


def test_estimate_is_a_valid_score_tempo_and_deterministic():
    onsets = pattern_onsets(3)
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=83.3, jitter_ms=12)
    first = estimate_bpm(starts(words))
    assert first == estimate_bpm(starts(words))
    assert 60 <= first.bpm <= 180 and is_valid_bpm(first.bpm)


def test_estimate_has_at_most_two_decimals():
    onsets = pattern_onsets(4)
    words = steady([f"w{i}" for i in onsets], onsets, [1] * len(onsets), bpm=97.37)
    bpm = estimate_bpm(starts(words)).bpm
    assert round(bpm * 100) == pytest.approx(bpm * 100)


def test_far_apart_onsets_are_ignored():
    # only intervals up to 1.5 s count; 0..3 ms intervals still give an in-range tempo
    assert 60 <= estimate_bpm([0, 1, 2, 3, 1e9]).bpm <= 180
