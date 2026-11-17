"""Typed failures of the score algorithms (spec/score_format.md section 4)."""

from __future__ import annotations

from enum import StrEnum


class ScoreReason(StrEnum):
    """Every ``reason`` of ``invalid_score``, in the order validate checks them."""

    malformed = "malformed"
    unsupported_version = "unsupported_version"
    bad_ppq = "bad_ppq"
    meters_empty = "meters_empty"
    too_many_meters = "too_many_meters"
    meters_first_not_zero = "meters_first_not_zero"
    meters_not_increasing = "meters_not_increasing"
    bar_out_of_range = "bar_out_of_range"
    meter_invalid = "meter_invalid"
    tempos_empty = "tempos_empty"
    too_many_tempos = "too_many_tempos"
    tempos_first_not_zero = "tempos_first_not_zero"
    tempos_not_increasing = "tempos_not_increasing"
    tempo_invalid = "tempo_invalid"
    bad_pickup = "bad_pickup"
    snap_invalid = "snap_invalid"
    line_count_mismatch = "line_count_mismatch"
    syllable_out_of_range = "syllable_out_of_range"
    syllables_not_increasing = "syllables_not_increasing"
    start_negative = "start_negative"
    duration_invalid = "duration_invalid"
    accent_invalid = "accent_invalid"
    exceeds_max_ticks = "exceeds_max_ticks"
    notes_overlap = "notes_overlap"


class ScoreError(Exception):
    """Base of every score failure."""


class ScoreInvalid(ScoreError):
    """A score that breaks format v1 or its grid; the API answers 422 ``invalid_score``."""

    def __init__(self, reason: ScoreReason, message: str, line: int | None = None, note: int | None = None) -> None:
        where = "" if line is None else f" (line {line}" + ("" if note is None else f", note {note}") + ")"
        super().__init__(f"{reason.value}{where}: {message}")
        self.reason = reason
        self.message = message
        self.line = line
        self.note = note

    def as_dict(self) -> dict[str, str | int]:
        """The wire shape: ``{"error": "invalid_score", "reason", "line"?, "note"?}``."""
        body: dict[str, str | int] = {"error": "invalid_score", "reason": self.reason.value}
        if self.line is not None:
            body["line"] = self.line
        if self.note is not None:
            body["note"] = self.note
        return body


class NotRestOnlyEdit(ScoreError):
    """apply_grid_rest_edit was given a grid edit that changed syllables, not only rests."""

    def __init__(self, message: str, line: int | None = None) -> None:
        super().__init__(message if line is None else f"line {line}: {message}")
        self.line = line


class ScoreTextMismatch(ScoreError):
    """splice_line_texts was given a grid line index the snippet text does not have."""

    def __init__(self, index: int, available: int) -> None:
        super().__init__(f"grid line {index} has no text line (text has {available} non-blank lines)")
        self.index = index
        self.available = available


class ScoreArgumentError(ScoreError, ValueError):
    """A timing query outside its domain (negative tick, bar below -1)."""
