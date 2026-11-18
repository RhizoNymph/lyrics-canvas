"""The rhythm-fit score: how well a candidate's stress pattern sits on the slot's strong positions.

Exact rational arithmetic, rounded once to 4 decimals at the end, so the value is deterministic and
equal in every port (docs/features/rhyme_rhythm_fit.md#the-score):

- slot level per syllable: ``max(metric_weight, ACCENT_LEVEL[accent])`` in 0..3;
- target per syllable: ``(level - lo) / (hi - lo)`` over the slot's levels when they differ (the
  strongest slot syllable is 1, the weakest 0), else ``level / 3`` (a slot without contrast keeps its
  absolute strength, so a single syllable on a downbeat wants a stress and one off the beat does not);
- candidate prominence per syllable: primary stress 1, secondary 1/2, unstressed 0;
- fit = mean over syllables of ``1 - |prominence - target|``.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from fractions import Fraction

from app.services.phonetics.types import Stress
from app.services.rhythm_fit.errors import StressPatternError, SyllableCountMismatch
from app.services.rhythm_fit.types import MAX_LEVEL, SlotRhythm

FIT_DECIMALS = 4
REST_MARK = "_"
STRESS_PROMINENCE: Mapping[int, Fraction] = {0: Fraction(0), 1: Fraction(1), 2: Fraction(1, 2)}


def candidate_stresses(stress: str) -> tuple[Stress, ...]:
    """One stress per syllable of a candidate stress string; rest marks (``_``) are not syllables.

    Engine units never carry rests in ``stress`` (rest cells are not syllables), but a rest in a
    candidate, wherever it is written, neither counts toward the syllable count nor takes a slot
    position: the candidate's syllables are sung on the slot's onsets.
    """
    out: list[Stress] = []
    for position, char in enumerate(stress):
        match char:
            case "0":
                out.append(0)
            case "1":
                out.append(1)
            case "2":
                out.append(2)
            case "_":
                continue
            case _:
                raise StressPatternError(stress, position)
    return tuple(out)


def slot_targets(slot: SlotRhythm) -> tuple[Fraction, ...]:
    """The prominence each slot syllable asks for, 0..1 (see the module docstring)."""
    levels = slot.levels
    lo, hi = min(levels), max(levels)
    if hi > lo:
        return tuple(Fraction(level - lo, hi - lo) for level in levels)
    return tuple(Fraction(level, MAX_LEVEL) for level in levels)


def rhythm_fit_exact(slot: SlotRhythm, stresses: Sequence[Stress]) -> Fraction:
    if len(stresses) != slot.syllable_count:
        raise SyllableCountMismatch(slot.syllable_count, len(stresses))
    targets = slot_targets(slot)
    agreement = sum((1 - abs(STRESS_PROMINENCE[s] - t) for s, t in zip(stresses, targets)), Fraction(0))
    return agreement / len(targets)


def wire_fit(value: Fraction) -> float:
    """A fit as sent (and compared when ordering): rounded to 4 decimals."""
    return round(float(value), FIT_DECIMALS)


def rhythm_fit(slot: SlotRhythm, stresses: Sequence[Stress]) -> float:
    """The candidate's fit in [0, 1], 4 decimals. Raises ``SyllableCountMismatch`` on a count mismatch."""
    return wire_fit(rhythm_fit_exact(slot, stresses))
