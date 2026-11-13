"""Whole-line matches: the best in-order, non-overlapping set of slot hits on one line."""

from app.services.phonetics.pattern.line_match import HitOption, best_hits


def _opt(slot: int, start: int, end: int, score: float = 0.9, uid: str = "") -> HitOption:
    return HitOption(slot=slot, word_start=start, word_end=end, score=score, unit_id=uid or f"{slot}:{start}-{end}")


def test_hits_follow_slot_order_and_word_order():
    hits = best_hits([_opt(0, 0, 1), _opt(1, 2, 3), _opt(2, 4, 5)], word_count=6, slot_count=3)
    assert [(h.slot, h.word_start) for h in hits] == [(0, 0), (1, 2), (2, 4)]


def test_out_of_order_hits_are_not_combined():
    # slot 2 hits word 0, slot 0 hits word 3: only one of them can be used
    hits = best_hits([_opt(2, 0, 1), _opt(0, 3, 4)], word_count=4, slot_count=3)
    assert len(hits) == 1


def test_more_hits_beat_a_higher_score():
    options = [_opt(0, 0, 3, 1.0), _opt(1, 0, 3, 1.0), _opt(0, 0, 1, 0.3), _opt(1, 2, 3, 0.3)]
    hits = best_hits(options, word_count=3, slot_count=2)
    assert [(h.slot, h.word_start, h.word_end) for h in hits] == [(0, 0, 1), (1, 2, 3)]


def test_same_count_prefers_the_higher_summed_score():
    options = [_opt(0, 0, 1, 0.6), _opt(0, 1, 2, 0.9), _opt(1, 3, 4, 0.8)]
    hits = best_hits(options, word_count=4, slot_count=2)
    assert [(h.slot, h.word_start) for h in hits] == [(0, 1), (1, 3)]


def test_hits_never_overlap_and_use_each_slot_once():
    options = [_opt(0, 0, 2), _opt(1, 1, 2), _opt(0, 2, 3), _opt(1, 3, 4)]
    hits = best_hits(options, word_count=4, slot_count=2)
    assert [(h.slot, h.word_start, h.word_end) for h in hits] in ([(0, 0, 2), (1, 3, 4)], [(0, 2, 3), (1, 3, 4)])
    assert len({h.slot for h in hits}) == len(hits)


def test_no_options_no_hits():
    assert best_hits([], word_count=5, slot_count=3) == ()
