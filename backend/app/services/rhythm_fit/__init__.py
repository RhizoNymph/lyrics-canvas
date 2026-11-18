"""Rhyme-suggest "Fit rhythm": slot rhythm from the anchor snippet's score, the fit score, ordering.

See docs/features/rhyme_rhythm_fit.md.
"""

from app.services.rhythm_fit.errors import RhythmFitError, StressPatternError, SyllableCountMismatch
from app.services.rhythm_fit.fit import (
    FIT_DECIMALS,
    STRESS_PROMINENCE,
    candidate_stresses,
    rhythm_fit,
    rhythm_fit_exact,
    slot_targets,
    wire_fit,
)
from app.services.rhythm_fit.order import order_by_rhythm
from app.services.rhythm_fit.slot import slot_ordinals, slot_rhythm, stored_slot_rhythm
from app.services.rhythm_fit.types import (
    ACCENT_LEVEL,
    MAX_LEVEL,
    RhythmUnavailable,
    RhythmUnavailableReason,
    SlotBeat,
    SlotRhythm,
    SlotRhythmResult,
)

__all__ = [
    "ACCENT_LEVEL",
    "FIT_DECIMALS",
    "MAX_LEVEL",
    "RhythmFitError",
    "RhythmUnavailable",
    "RhythmUnavailableReason",
    "STRESS_PROMINENCE",
    "SlotBeat",
    "SlotRhythm",
    "SlotRhythmResult",
    "StressPatternError",
    "SyllableCountMismatch",
    "candidate_stresses",
    "order_by_rhythm",
    "rhythm_fit",
    "rhythm_fit_exact",
    "slot_ordinals",
    "slot_rhythm",
    "slot_targets",
    "stored_slot_rhythm",
    "wire_fit",
]
