"""Tempo estimate from a take's word onsets (docs/features/score_draft_export.md#3-tempo-estimate).

For every candidate bpm in 60..180 (hundredths, a coarse pass at 0.5 then a fine pass at 0.05
around the best), the inter-onset intervals (lags 1 and 2, at most 1.5 s) are scored by how close
they are to whole numbers of sixteenths: ``mean(cos(2 pi interval / q))`` with ``q = 15000 / bpm``
ms. The sixteenth-triplet lattice (``q = 10000 / bpm``) is scored too, minus a small penalty, and
the better of the two counts. A prior of ``-0.1 * |log2(bpm / 95)|`` breaks the octave ties (90 and
180 fit straight sixteenths at 90 equally well; ties resolve to the tempo at or below 134). A whole bpm next to the best wins when it scores
within ``WHOLE_BPM_TOLERANCE`` of it (millisecond rounding moves a 90 bpm take to 90.05). Intervals, not absolute onsets, are scored, so a
take that drifts (rubato) still has a clear peak.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

import numpy as np

MIN_BPM_HUNDREDTHS = 6000
MAX_BPM_HUNDREDTHS = 18000
COARSE_STEP = 50  # 0.5 bpm
FINE_STEP = 5  # 0.05 bpm
FINE_SPAN = 50  # +-0.5 bpm around the coarse best
LAGS = (1, 2)
MAX_INTERVAL_MS = 1500.0
TRIPLET_PENALTY = 0.1
PRIOR_WEIGHT = 0.1
PRIOR_CENTER_BPM = 95.0
DEFAULT_BPM = 90
MIN_INTERVALS = 2
WHOLE_BPM_TOLERANCE = 0.01


@dataclass(frozen=True, slots=True)
class TempoEstimate:
    bpm: int | float  # at most 2 decimals, in 60..180
    fit: float  # the winning candidate's score without the prior, in [-1, 1]; 0 for the default
    intervals: int  # intervals scored (0 when the default was used)


def onset_intervals(onsets_ms: Sequence[float]) -> np.ndarray:
    """Positive intervals between distinct onsets at lags 1 and 2, at most ``MAX_INTERVAL_MS``."""
    x = np.unique(np.asarray(onsets_ms, dtype=np.float64))
    parts = [x[lag:] - x[:-lag] for lag in LAGS if len(x) > lag]
    if not parts:
        return np.empty(0, dtype=np.float64)
    intervals = np.concatenate(parts)
    return intervals[(intervals > 0) & (intervals <= MAX_INTERVAL_MS)]


def _scores(intervals: np.ndarray, hundredths: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(score with prior, fit without) for each candidate."""
    bpm = hundredths.astype(np.float64) / 100.0
    straight = np.cos(2.0 * np.pi * intervals[None, :] * bpm[:, None] / 15000.0).mean(axis=1)
    triplet = np.cos(2.0 * np.pi * intervals[None, :] * bpm[:, None] / 10000.0).mean(axis=1) - TRIPLET_PENALTY
    fit = np.maximum(straight, triplet)
    return fit - PRIOR_WEIGHT * np.abs(np.log2(bpm / PRIOR_CENTER_BPM)), fit


def _best(intervals: np.ndarray, hundredths: np.ndarray) -> tuple[int, float, float]:
    """(candidate, its score with the prior, its fit) of the best candidate."""
    scored, fit = _scores(intervals, hundredths)
    index = int(np.argmax(scored))  # first maximum: the slowest of equal candidates
    return int(hundredths[index]), float(scored[index]), float(fit[index])


def _whole_neighbour(intervals: np.ndarray, best: int, best_score: float) -> tuple[int, float] | None:
    """The better of the whole bpms around ``best`` when it is within the tolerance."""
    below = best // 100 * 100
    whole = [c for c in (below, below + 100) if MIN_BPM_HUNDREDTHS <= c <= MAX_BPM_HUNDREDTHS]
    if best % 100 == 0 or not whole:
        return None
    candidate, score, fit = _best(intervals, np.asarray(whole))
    return (candidate, fit) if score >= best_score - WHOLE_BPM_TOLERANCE else None


def _as_bpm(hundredths: int) -> int | float:
    return hundredths // 100 if hundredths % 100 == 0 else hundredths / 100


def estimate_bpm(onsets_ms: Sequence[float]) -> TempoEstimate:
    """The take's tempo; ``DEFAULT_BPM`` when fewer than ``MIN_INTERVALS`` intervals exist."""
    intervals = onset_intervals(onsets_ms)
    if len(intervals) < MIN_INTERVALS:
        return TempoEstimate(DEFAULT_BPM, 0.0, 0)
    coarse, _, _ = _best(intervals, np.arange(MIN_BPM_HUNDREDTHS, MAX_BPM_HUNDREDTHS + 1, COARSE_STEP))
    low = max(MIN_BPM_HUNDREDTHS, coarse - FINE_SPAN)
    high = min(MAX_BPM_HUNDREDTHS, coarse + FINE_SPAN)
    best, score, fit = _best(intervals, np.arange(low, high + 1, FINE_STEP))
    whole = _whole_neighbour(intervals, best, score)
    if whole is not None:
        best, fit = whole
    return TempoEstimate(_as_bpm(best), fit, int(len(intervals)))
