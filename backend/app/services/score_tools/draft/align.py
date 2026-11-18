"""Align a take's STT words to the grid's words (docs/features/score_draft_export.md#1-word-alignment).

Keys are the score spec's ``word_key`` (section 10) on both sides. Exact matches come from the
spec's ``lcs_pairs`` run on the **reversed** sequences, so a word the grid holds more than once pairs
with its latest occurrence (voice takes are appended at the end of a snippet). Gaps between matches
with as many take words as grid words are then paired by position (a misheard word, ``wee`` for
``we``).
"""

from __future__ import annotations

from collections.abc import Sequence

from app.services.score import default_accent, lcs_pairs, word_key
from app.services.score.grid_view import grid_lines, line_cells, syllable_positions, word_groups
from app.services.score.types import GridJson
from app.services.score_tools.draft.types import Alignment, GridWord, PairKind, TakeWord, WordPair
from app.services.voice.line_assembly import TimedWord


def grid_words(grid: GridJson) -> list[GridWord]:
    """Every word group of every grid line, lines in order, groups in their order."""
    out: list[GridWord] = []
    for index, line in enumerate(grid_lines(grid)):
        cells = line_cells(line)
        positions = syllable_positions(cells)
        for group in word_groups(cells):
            syllable_cells = [cells[positions[o]] for o in group.ordinals]
            out.append(
                GridWord(
                    line=index,
                    ordinals=group.ordinals,
                    syllables=tuple(_text(c.get("syllable")) for c in syllable_cells),
                    accents=tuple(default_accent(c.get("stress")) for c in syllable_cells),
                    key=word_key(group.spelling),
                )
            )
    return out


def _text(value: object) -> str:
    return value if isinstance(value, str) else ""


def take_words(words: Sequence[TimedWord]) -> list[TakeWord]:
    """The take's words with monotonic times: a start before the previous start is moved up to it,
    an end before its start is moved up to the start."""
    out: list[TakeWord] = []
    previous = 0.0
    for word in words:
        start = max(float(word.start_ms), previous)
        out.append(TakeWord(key=word_key(word.text), start_ms=start, end_ms=max(float(word.end_ms), start)))
        previous = start
    return out


def _exact_pairs(take: Sequence[str], grid: Sequence[str]) -> list[tuple[int, int]]:
    """lcs_pairs on the reversed sequences, mapped back and in increasing order (latest match)."""
    n, m = len(take), len(grid)
    reversed_pairs = lcs_pairs(list(reversed(take)), list(reversed(grid)))
    return [(n - 1 - i, m - 1 - j) for i, j in reversed(reversed_pairs)]


def align_words(take: Sequence[str], grid: Sequence[str]) -> Alignment:
    """Pairs of (take index, grid index), strictly increasing in both, exact first then gap fills."""
    exact = _exact_pairs(take, grid)
    pairs: list[WordPair] = []
    bounds = [(-1, -1), *exact, (len(take), len(grid))]
    for (t0, g0), (t1, g1) in zip(bounds, bounds[1:]):
        gap_take, gap_grid = t1 - t0 - 1, g1 - g0 - 1
        if gap_take > 0 and gap_take == gap_grid:
            pairs.extend(WordPair(t0 + 1 + k, g0 + 1 + k, PairKind.fill) for k in range(gap_take))
        if t1 < len(take):
            pairs.append(WordPair(t1, g1, PairKind.exact))
    return Alignment(pairs=tuple(pairs), unmatched_take_words=len(take) - len(pairs))
