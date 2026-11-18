import pytest

from app.services.score_tools.draft.quantize import choose_grid, next_point_after, quantize, snap
from app.services.score_tools.draft.types import Grid, SyllableTime

BPM = 90
SIXTEENTH_MS = 60000 / BPM / 4  # 166.67
TRIPLET_MS = 60000 / BPM / 6  # 111.11


def syl(start: float, end: float, accent: int = 0, word_start: bool = True, s: int = 0) -> SyllableTime:
    return SyllableTime(line=0, s=s, start_ms=start, end_ms=end, word_start=word_start, accent=accent)


def sung(units: list[tuple[float, float]], unit_ms: float = SIXTEENTH_MS, accents: list[int] | None = None, offset: float = 300):
    accents = accents or [1] * len(units)
    return [
        syl(offset + on * unit_ms, offset + (on + length) * unit_ms, accents[i], s=i)
        for i, (on, length) in enumerate(units)
    ]


def ticks(result):
    return [(n.t, n.d) for n in result.notes]


def test_snap_and_next_point():
    assert snap(13.9, Grid.straight, 0) == 12
    assert snap(18, Grid.straight, 0) == 24  # half rounds up
    assert snap(13, Grid.triplet, 0) == 16
    assert snap(-11, Grid.straight, 0) == -12
    assert snap(25, Grid.straight, 12) == 24
    assert next_point_after(12, Grid.straight, 0) == 24
    assert next_point_after(13, Grid.triplet, 0) == 16
    assert next_point_after(0, Grid.straight, 12) == 12


def test_choose_grid():
    assert choose_grid([0, 16, 32], None, True) is Grid.triplet
    assert choose_grid([0, 12, 24, 36], None, True) is Grid.straight
    assert choose_grid([0, 16, 32], None, False) is Grid.straight
    assert choose_grid([16], None, True) is Grid.straight  # one onset: straight unless already in triplets
    assert choose_grid([16], Grid.triplet, True) is Grid.triplet
    assert choose_grid([], Grid.triplet, True) is Grid.straight
    # ambiguous (14 and 34 are 2 ticks from both lattices): straight after straight, triplet after triplet
    assert choose_grid([0, 14, 34], None, True) is Grid.straight
    assert choose_grid([0, 14, 34], Grid.triplet, True) is Grid.triplet


def test_empty_input():
    result = quantize([], BPM, 192)
    assert result.notes == () and result.pickup == 0


def test_straight_sixteenths_land_exactly():
    result = quantize(sung([(0, 1), (1, 1), (2, 2), (4, 1), (6, 2)]), BPM, 192)
    assert ticks(result) == [(0, 12), (12, 12), (24, 24), (48, 12), (72, 24)]
    assert result.pickup == 0


def test_triplets_land_on_the_triplet_lattice():
    result = quantize(sung([(0, 1), (1, 1), (2, 1), (3, 1), (4, 1), (5, 1), (6, 3)], TRIPLET_MS), BPM, 192)
    assert ticks(result) == [(0, 8), (8, 8), (16, 8), (24, 8), (32, 8), (40, 8), (48, 24)]
    assert result.triplet_beats >= 1


def test_triplets_not_allowed_when_the_bar_is_not_a_multiple_of_8():
    result = quantize(sung([(0, 1), (2, 1), (4, 1)], TRIPLET_MS), BPM, 84)  # 7/16
    assert all(n.t % 12 == 0 for n in result.notes)
    assert result.triplet_beats == 0


def test_identical_onsets_are_pushed_apart():
    result = quantize([syl(100, 100, 1, s=0), syl(100, 100, 0, False, s=1), syl(100, 400, 0, False, s=2)], BPM, 192)
    assert [n.t for n in result.notes] == [0, 12, 24]
    assert all(n.d >= 1 for n in result.notes)
    assert all(a.t + a.d <= b.t for a, b in zip(result.notes, result.notes[1:]))


def test_legato_fills_to_the_next_onset():
    # the first word ends 30 ms before the next onset (less than half a sixteenth): legato
    result = quantize([syl(0, 470, 1, s=0), syl(500, 600, 1, s=1)], BPM, 192)
    assert ticks(result)[0] == (0, 36)


def test_detached_note_keeps_its_own_length_then_a_gap():
    result = quantize(sung([(0, 1), (8, 2)]), BPM, 192)
    assert ticks(result) == [(0, 12), (96, 24)]


def test_minimum_duration_is_one_step():
    result = quantize([syl(0, 1, 1, s=0), syl(1000, 1001, 1, s=1)], BPM, 192)
    assert [n.d for n in result.notes] == [12, 12]


def test_pickup_starts_bar_zero_at_the_first_accented_syllable():
    result = quantize(sung([(0, 1), (1, 1), (3, 1)], accents=[0, 1, 0]), BPM, 192)
    assert result.pickup == 12
    assert [n.t for n in result.notes] == [0, 12, 36]


def test_two_syllable_pickup():
    result = quantize(sung([(0, 1), (1, 1), (2, 2)], accents=[0, 0, 1]), BPM, 192)
    assert result.pickup == 24


def test_accented_first_syllable_has_no_pickup():
    assert quantize(sung([(0, 1), (1, 1)], accents=[1, 0]), BPM, 192).pickup == 0


def test_no_accent_has_no_pickup():
    assert quantize(sung([(0, 1), (1, 1)], accents=[0, 0]), BPM, 192).pickup == 0


def test_accent_after_the_first_bar_has_no_pickup():
    result = quantize(sung([(0, 1), (17, 1)], accents=[0, 1]), BPM, 192)
    assert result.pickup == 0


def test_pickup_is_always_shorter_than_the_bar():
    result = quantize(sung([(0, 1), (5, 1)], accents=[0, 1]), BPM, 72)  # 3/8: bar 72 ticks, accent at 60
    assert 0 < result.pickup < 72


def test_tempo_follower_keeps_quarters_through_an_acceleration():
    # quarters accelerating from 90 to 110 bpm, quantized at 90
    times, t = [], 0.0
    for i in range(12):
        times.append(t)
        t += 60000 / (90 + 20 * i / 11)
    result = quantize([syl(x, x + 200, 1, s=i) for i, x in enumerate(times)], BPM, 192)
    assert [n.t for n in result.notes] == [48 * i for i in range(12)]


@pytest.mark.parametrize("bar_length", [192, 144, 72, 120, 84])
def test_output_is_strictly_ordered_and_never_overlaps(bar_length):
    starts = [0, 30, 31, 200, 260, 900, 905, 2000, 2001, 2002]
    result = quantize([syl(x, x + 150, i % 2, i % 3 == 0, s=i) for i, x in enumerate(starts)], BPM, bar_length)
    notes = result.notes
    assert notes[0].t == 0
    assert all(n.d >= 1 for n in notes)
    assert all(a.t + a.d <= b.t for a, b in zip(notes, notes[1:]))
    assert 0 <= result.pickup < bar_length
