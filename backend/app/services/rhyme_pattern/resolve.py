"""Pattern lines and client slots: load the line range of a snippet and check slot specs."""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from app.schemas.phonetics import LinePhonetics
from app.schemas.rhyme_pattern import MAX_PATTERN_LINES, SlotSpec
from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.pattern.spans import build_span
from app.services.phonetics.pattern.types import LineWords, PatternSpan
from app.services.phonetics_service import split_lines, word_from_model
from app.services.rest_marks import word_tokens
from app.services.rhyme_pattern.errors import (
    PatternRangeInvalidError,
    PatternSlotOrderError,
    PatternSpanInvalidError,
)
from app.services.rhyme_suggest_service import SnippetRecord, snippet_phonetics

MIN_PATTERN_LINES = 2


@dataclass(frozen=True, slots=True)
class PatternLine:
    """One line of the range: stored phonetics, engine words and the words as written
    (``tokens[i]`` is word ``i``: rest marks removed, case and punctuation kept)."""

    phonetics: LinePhonetics
    words: LineWords
    tokens: tuple[str, ...]
    grid: Mapping[str, object] | None

    @property
    def line_index(self) -> int:
        return self.phonetics.line_index

    @property
    def text(self) -> str:
        return " ".join(self.tokens)

    def display(self, start: int, end: int) -> str:
        return " ".join(self.tokens[start:end])


@dataclass(frozen=True, slots=True)
class PatternRange:
    record: SnippetRecord
    lines: tuple[PatternLine, ...]  # every line of [line_start, line_end)

    @property
    def line_count(self) -> int:
        """Pattern lines: the lines of the range that have words."""
        return sum(1 for line in self.lines if line.words.words)

    def line(self, line_index: int) -> PatternLine | None:
        start = self.lines[0].line_index
        offset = line_index - start
        return self.lines[offset] if 0 <= offset < len(self.lines) else None


def line_tokens(raw_line: str | None, line: LinePhonetics) -> tuple[str, ...]:
    """The line's words as written; the stored (cleaned) words if the text and phonetics disagree."""
    tokens = word_tokens(raw_line) if raw_line is not None else ()
    if len(tokens) != len(line.words):
        return tuple(w.word for w in line.words)
    return tokens


def _grid_line(record: SnippetRecord, line_index: int) -> Mapping[str, object] | None:
    lines = (record.grid_data or {}).get("lines") or []
    return lines[line_index] if line_index < len(lines) else None


def pattern_line(record: SnippetRecord, line: LinePhonetics, raw_lines: Sequence[str]) -> PatternLine:
    raw = raw_lines[line.line_index] if line.line_index < len(raw_lines) else None
    return PatternLine(
        phonetics=line,
        words=LineWords(line.line_index, tuple(word_from_model(w) for w in line.words)),
        tokens=line_tokens(raw, line),
        grid=_grid_line(record, line.line_index),
    )


def resolve_range(record: SnippetRecord, line_start: int, line_end: int) -> PatternRange:
    """Lines ``[line_start, line_end)`` of the snippet: inside it, at most 16 lines, at least 2 with words."""
    if line_end <= line_start:
        raise PatternRangeInvalidError(f"line_end {line_end} must be greater than line_start {line_start}")
    phonetics = snippet_phonetics(record)
    if line_end > len(phonetics.lines):
        raise PatternRangeInvalidError(
            f"lines {line_start}-{line_end} are outside snippet {record.id}, which has {len(phonetics.lines)} lines"
        )
    if line_end - line_start > MAX_PATTERN_LINES:
        raise PatternRangeInvalidError(f"at most {MAX_PATTERN_LINES} lines, got {line_end - line_start}")
    raw_lines = split_lines(record.raw_text)
    lines = tuple(pattern_line(record, line, raw_lines) for line in phonetics.lines[line_start:line_end])
    result = PatternRange(record=record, lines=lines)
    if result.line_count < MIN_PATTERN_LINES:
        raise PatternRangeInvalidError(
            f"lines {line_start}-{line_end} have {result.line_count} line(s) with words; at least {MIN_PATTERN_LINES} are needed"
        )
    return result


@dataclass(frozen=True, slots=True)
class ResolvedSlot:
    id: str
    members: tuple[PatternSpan, ...]  # line order
    k: int | None


def slot_ids(specs: Sequence[SlotSpec]) -> list[str]:
    """The given ids, and ``u0``, ``u1``, ... (skipping given ones) for slots without one."""
    taken = {s.id for s in specs if s.id is not None}
    ids: list[str] = []
    n = 0
    for spec in specs:
        if spec.id is not None:
            ids.append(spec.id)
            continue
        while f"u{n}" in taken:
            n += 1
        ids.append(f"u{n}")
        taken.add(f"u{n}")
    return ids


def resolve_slots(rng: PatternRange, specs: Sequence[SlotSpec], dialect: DialectMap) -> list[ResolvedSlot]:
    """Member spans of each slot, checked: inside the range and its line, pronounceable, one per
    line per slot, no overlap across slots (``PatternSpanInvalidError``) and the same slot order
    as word order on every line (``PatternSlotOrderError``)."""
    resolved: list[ResolvedSlot] = []
    for slot_id, spec in zip(slot_ids(specs), specs):
        members: list[PatternSpan] = []
        lines_seen: set[int] = set()
        for ref in spec.members:
            line = rng.line(ref.line_index)
            if line is None:
                raise PatternSpanInvalidError(f"slot {slot_id}: line {ref.line_index} is outside the pattern lines")
            word_count = len(line.words.words)
            if ref.word_end <= ref.word_start or ref.word_end > word_count:
                raise PatternSpanInvalidError(
                    f"slot {slot_id}: words {ref.word_start}-{ref.word_end} are outside line {ref.line_index}'s {word_count} words"
                )
            if ref.line_index in lines_seen:
                raise PatternSpanInvalidError(f"slot {slot_id}: two members on line {ref.line_index}")
            lines_seen.add(ref.line_index)
            span = build_span(rng.record.id, line.words, ref.word_start, ref.word_end, dialect)
            if span is None:
                raise PatternSpanInvalidError(
                    f"slot {slot_id}: words {ref.word_start}-{ref.word_end} of line {ref.line_index} have no pronounceable word"
                )
            members.append(span)
        resolved.append(ResolvedSlot(id=slot_id, members=tuple(sorted(members, key=lambda m: m.pos)), k=spec.k))
    _check_layout(resolved)
    return resolved


def _check_layout(slots: Sequence[ResolvedSlot]) -> None:
    by_line: dict[int, list[tuple[int, ResolvedSlot, PatternSpan]]] = {}
    for order, slot in enumerate(slots):
        for member in slot.members:
            by_line.setdefault(member.pos.line_index, []).append((order, slot, member))
    for line_index, entries in by_line.items():
        for i, (_, slot_a, a) in enumerate(entries):
            for _, slot_b, b in entries[i + 1 :]:
                if a.pos.overlaps(b.pos):
                    raise PatternSpanInvalidError(f"slots {slot_a.id} and {slot_b.id} overlap on line {line_index}")
    for line_index, entries in by_line.items():
        starts = [member.pos.word_start for _, _, member in entries]  # in slot order
        if starts != sorted(starts):
            raise PatternSlotOrderError(f"slot order differs from word order on line {line_index}")
