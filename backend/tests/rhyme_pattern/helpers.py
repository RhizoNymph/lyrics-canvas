"""Shared helpers for the rhyme-pattern tests."""

from __future__ import annotations

from collections.abc import Sequence

from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics.pattern.types import LineWords, PatternSlot
from app.services.phonetics_service import compute_snippet_phonetics, word_from_model

EXAMPLE = (
    "I keep it steady when the pressure on",
    "already heavy and the weather wrong",
    "ready or not it's better long",
)


def line_words(lines: Sequence[str]) -> list[LineWords]:
    text = "\n".join(lines)
    phonetics = compute_snippet_phonetics(text, syllabify_text(text), None)
    return [LineWords(line.line_index, tuple(word_from_model(w) for w in line.words)) for line in phonetics.lines]


def texts(slot: PatternSlot) -> list[str]:
    return [m.unit.text for m in slot.members]


def assert_slot_invariants(slots: Sequence[PatternSlot], min_score: float | None = None) -> None:
    """At most one member per line; within each line members of successive slots are in order and
    do not overlap."""
    last_end: dict[int, int] = {}
    for slot in slots:
        lines = [m.pos.line_index for m in slot.members]
        assert len(lines) == len(set(lines)), "two members on one line"
        assert lines == sorted(lines), "members not in line order"
        for m in slot.members:
            assert m.pos.word_start >= last_end.get(m.pos.line_index, 0), "slot order differs between lines"
            last_end[m.pos.line_index] = m.pos.word_end
        if min_score is not None:
            assert slot.cohesion >= min_score
