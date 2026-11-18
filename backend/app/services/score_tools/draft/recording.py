"""Draft a score from a voice take (docs/features/score_draft_export.md#recording-draft).

align words -> split each matched word's time over its syllables -> keep score order -> estimate
the tempo when none is given -> quantize -> a ``Score`` validated against the grid.
"""

from __future__ import annotations

import logging
from collections.abc import Mapping, Sequence

from app.services.score import Meter, Note, Score, ScoreInvalid, ScoreLine, ScoreReason, Snap, Tempo, check_typed
from app.services.score.grid_view import syllable_counts
from app.services.score.types import GridJson
from app.services.score.validate import as_integer, as_number, is_valid_bpm, is_valid_meter_value
from app.services.score_tools.draft.align import align_words, grid_words, take_words
from app.services.score_tools.draft.quantize import quantize
from app.services.score_tools.draft.syllable_split import split_word
from app.services.score_tools.draft.tempo import estimate_bpm
from app.services.score_tools.draft.types import Alignment, DraftResult, GridWord, PairKind, SyllableTime, TakeWord
from app.services.score_tools.errors import DraftEmptyError
from app.services.voice.timings import Timings

logger = logging.getLogger(__name__)


def parse_meter(meter: Mapping[str, object]) -> Meter:
    """Bar-0 meter from ``{num, den}``; ScoreInvalid ``meter_invalid`` otherwise."""
    num, den = as_integer(meter.get("num")), as_integer(meter.get("den"))
    if num is None or den is None or not is_valid_meter_value(num, den):
        raise ScoreInvalid(ScoreReason.meter_invalid, f"meter {meter.get('num')}/{meter.get('den')} is not valid")
    return Meter(bar=0, num=num, den=den)


def parse_bpm(bpm: object) -> int | float:
    tempo = as_number(bpm)
    if tempo is None or not is_valid_bpm(tempo):
        raise ScoreInvalid(ScoreReason.tempo_invalid, f"bpm {bpm} is not valid")
    return tempo


def syllable_times(alignment: Alignment, take: Sequence[TakeWord], grid: Sequence[GridWord]) -> list[SyllableTime]:
    """Every syllable of every matched grid word with its share of the take word's time, in score
    order (lines, then ordinals). A syllable that would start before the previous kept one (only
    when a chunk drag interleaved words) is dropped and stays unplaced."""
    timed: list[SyllableTime] = []
    for pair in alignment.pairs:
        word, spoken = grid[pair.grid], take[pair.take]
        parts = split_word(spoken.start_ms, spoken.end_ms, word.syllables)
        for position, (ordinal, (start, end)) in enumerate(zip(word.ordinals, parts)):
            timed.append(SyllableTime(word.line, ordinal, start, end, position == 0, word.accents[position]))
    timed.sort(key=lambda syllable: (syllable.line, syllable.s))
    kept: list[SyllableTime] = []
    for syllable in timed:
        if not kept or syllable.start_ms >= kept[-1].start_ms:
            kept.append(syllable)
    return kept


def draft_from_recording(
    grid: GridJson, timings: Timings, meter: Mapping[str, object], bpm: object | None
) -> DraftResult:
    """Raises ScoreInvalid (meter_invalid, tempo_invalid, exceeds_max_ticks) or DraftEmptyError."""
    first_meter = parse_meter(meter)
    given = None if bpm is None else parse_bpm(bpm)
    words_of_grid = grid_words(grid)
    words_of_take = take_words(timings.words)
    alignment = align_words([w.key for w in words_of_take], [w.key for w in words_of_grid])
    syllables = syllable_times(alignment, words_of_take, words_of_grid)
    if not syllables:
        raise DraftEmptyError("no word of the take matches a word of the snippet")
    estimate = estimate_bpm([w.start_ms for w in words_of_take]) if given is None else None
    tempo = given if estimate is None else estimate.bpm
    quantized = quantize(syllables, float(tempo), first_meter.bar_length)
    per_line: list[list[Note]] = [[] for _ in syllable_counts(grid)]
    for syllable, placed in zip(syllables, quantized.notes):
        per_line[syllable.line].append(Note(s=syllable.s, t=placed.t, d=placed.d, a=syllable.accent))
    snap = Snap.sixteenth_triplet if quantized.triplet_beats > quantized.straight_beats else Snap.sixteenth
    score = Score(
        pickup=quantized.pickup,
        meters=(first_meter,),
        tempos=(Tempo(bar=0, bpm=tempo),),
        snap=snap,
        lines=tuple(ScoreLine(tuple(notes)) for notes in per_line),
    )
    checked = check_typed(score, grid)
    total = sum(syllable_counts(grid))
    fills = sum(1 for pair in alignment.pairs if pair.kind is PairKind.fill)
    logger.info(
        "score.draft.recording take_words=%d matched=%d filled=%d placed=%d unplaced=%d bpm=%s estimated=%s fit=%s pickup=%d",
        len(words_of_take), len(alignment.pairs), fills, len(syllables), total - len(syllables), tempo,
        estimate is not None, None if estimate is None else round(estimate.fit, 3), quantized.pickup,
    )
    return DraftResult(
        score=checked,
        bpm_estimated=None if estimate is None else estimate.bpm,
        unplaced=total - len(syllables),
        unmatched_words=alignment.unmatched_take_words,
    )
