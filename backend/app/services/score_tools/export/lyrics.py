"""The lyric of each placed syllable: its grid text and its place in its word (MusicXML syllabic)."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from app.services.score import Score
from app.services.score.grid_view import grid_lines, line_cells, syllable_positions, word_groups
from app.services.score.types import GridJson


class Syllabic(StrEnum):
    single = "single"
    begin = "begin"
    middle = "middle"
    end = "end"


@dataclass(frozen=True, slots=True)
class Lyric:
    text: str  # the cell's syllable ("" when missing)
    syllabic: Syllabic

    @property
    def continues(self) -> bool:
        """More syllables of the same word follow (MIDI lyrics get a trailing ``-``)."""
        return self.syllabic in (Syllabic.begin, Syllabic.middle)


def _syllabic(position: int, count: int) -> Syllabic:
    if count == 1:
        return Syllabic.single
    if position == 0:
        return Syllabic.begin
    return Syllabic.end if position == count - 1 else Syllabic.middle


def line_lyrics(grid: GridJson) -> list[dict[int, Lyric]]:
    """Per grid line: syllable ordinal -> lyric (position within its word group in cell order)."""
    out: list[dict[int, Lyric]] = []
    for line in grid_lines(grid):
        cells = line_cells(line)
        positions = syllable_positions(cells)
        lyrics: dict[int, Lyric] = {}
        for group in word_groups(cells):
            for position, ordinal in enumerate(group.ordinals):
                value = cells[positions[ordinal]].get("syllable")
                text = value if isinstance(value, str) else ""
                lyrics[ordinal] = Lyric(text, _syllabic(position, len(group.ordinals)))
        out.append(lyrics)
    return out


def note_lyrics(score: Score, grid: GridJson) -> list[Lyric]:
    """One lyric per note of ``score`` (lines in order, notes in order); ``score`` must be valid
    against ``grid``."""
    per_line = line_lyrics(grid)
    return [per_line[index][note.s] for index, line in enumerate(score.lines) for note in line.notes]
