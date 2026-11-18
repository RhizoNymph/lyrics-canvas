import pytest

from app.services.score import check_score
from app.services.score_tools.export.notation import decompose, measures
from tests.score.helpers import grid, score


@pytest.mark.parametrize("ticks", range(1, 400))
def test_decompose_sums_and_uses_at_most_one_triplet(ticks):
    values = decompose(ticks)
    assert sum(v.ticks for v in values) == ticks
    assert sum(1 for v in values if v.triplet) <= 1
    assert all(v.triplet for v in values[-1:]) or ticks % 3 == 0


@pytest.mark.parametrize(
    ("ticks", "names"),
    [
        (192, [("whole", 0, False)]),
        (144, [("half", 1, False)]),
        (48, [("quarter", 0, False)]),
        (36, [("eighth", 1, False)]),
        (12, [("16th", 0, False)]),
        (16, [("eighth", 0, True)]),
        (8, [("16th", 0, True)]),
        (32, [("quarter", 0, True)]),
        (20, [("16th", 0, False), ("16th", 0, True)]),
        (60, [("quarter", 0, False), ("16th", 0, False)]),
        (384, [("whole", 1, False), ("half", 0, False)]),
    ],
)
def test_decompose_values(ticks, names):
    assert [(v.name, v.dots, v.triplet) for v in decompose(ticks)] == names


def test_decompose_rejects_zero():
    with pytest.raises(ValueError):
        decompose(0)


def test_measures_split_notes_at_barlines_with_ties():
    g = grid("^a ^b")
    s = check_score(score([[(0, 0, 48, 1), (1, 144, 96, 0)]]), g)  # b crosses the bar at 192
    bars = measures(s)
    assert [(m.number, m.start, m.length) for m in bars] == [(1, 0, 192), (2, 192, 192)]
    first, second = bars
    assert [(p.start, p.value.ticks, p.note, p.tie_start, p.tie_stop) for p in first.pieces] == [
        (0, 48, 0, False, False),
        (48, 96, None, False, False),
        (144, 48, 1, True, False),
    ]
    assert [(p.start, p.value.ticks, p.note, p.tie_start, p.tie_stop) for p in second.pieces] == [
        (192, 48, 1, False, True),
        (240, 144, None, False, False),
    ]


def test_measures_with_pickup_meter_and_tempo_changes():
    g = grid("^a ^b ^c")
    raw = score([[(0, 0, 24, 0), (1, 24, 48, 1), (2, 400, 24, 0)]], meters=[(0, 4, 4), (1, 3, 4)], tempos=[(0, 90), (2, 120)], pickup=24)
    bars = measures(check_score(raw, g))
    assert [(m.number, m.implicit, m.start, m.length) for m in bars] == [
        (0, True, 0, 24),
        (1, False, 24, 192),
        (2, False, 216, 144),
        (3, False, 360, 144),
    ]
    assert [None if m.meter is None else (m.meter.num, m.meter.den) for m in bars] == [(4, 4), None, (3, 4), None]
    assert [m.bpm for m in bars] == [90, None, None, 120]


def test_empty_measure_has_no_pieces():
    g = grid("^a ^b")
    bars = measures(check_score(score([[(0, 0, 12, 0), (1, 400, 12, 0)]]), g))
    assert bars[1].pieces == ()
    assert all(sum(p.value.ticks for p in m.pieces) == m.length for m in bars if m.pieces)


def test_score_without_notes_has_one_empty_bar():
    bars = measures(check_score(score([[]]), grid("^a")))
    assert len(bars) == 1 and bars[0].pieces == ()
