"""Spans: word ranges of a line that can rhyme, and their stressed tails.

A detection span (``line_spans``) has 1..``max_span_words`` words, at least one syllable and a
pronounceable last word. Single-syllable function words (unstressed monosyllables, plus the stop
list) never start a span, never end one inside the line, and never form a span alone; the one
exception is a stressed function word ending its line ("... the pressure on"), which carries the
end rhyme. ``build_span`` makes a span of any range (client-edited slots) with the same unit.
"""

from __future__ import annotations

from collections.abc import Sequence

from app.services.phonetics.dialect import DialectMap
from app.services.phonetics.pattern.types import LineWords, PatternSpan, SpanPos
from app.services.phonetics.types import Pronunciation, PronunciationSource, Provenance, Syllable, WordPronunciation
from app.services.phonetics.units import build_unit, note_unit_id

MAX_SPAN_WORDS = 2

STOP_WORDS: frozenset[str] = frozenset(
    {"a", "an", "the", "and", "or", "to", "of", "in", "on", "at", "it", "is", "i", "my", "me", "you", "your", "we", "be"}
)


def is_function_word(word: WordPronunciation) -> bool:
    """A single-syllable word that is unstressed or on the stop list."""
    if len(word.syllables) != 1:
        return False
    return word.syllables[0].stress == 0 or word.word in STOP_WORDS


def _stressed(word: WordPronunciation) -> bool:
    return any(s.stress in (1, 2) for s in word.syllables)


def stressed_tail(syllables: Sequence[Syllable]) -> int:
    """Syllables from the first stressed (1 or 2) syllable to the end; 1 when none is stressed."""
    for i, s in enumerate(syllables):
        if s.stress in (1, 2):
            return len(syllables) - i
    return 1 if syllables else 0


def build_span(
    snippet_id: str, line: LineWords, start: int, end: int, dialect: DialectMap, tags: Sequence[str] = ()
) -> PatternSpan | None:
    """The span ``[start, end)`` of ``line`` as a ``note:<sid>:<line>:<start>-<end>`` unit (the same
    unit ``phonetics_service.note_unit`` builds); None when it has no syllable."""
    words = line.words[start:end]
    pron = Pronunciation(words=words)
    if not pron.syllables:
        return None
    overridden = any(w.source is PronunciationSource.override for w in words)
    unit = build_unit(
        id=note_unit_id(snippet_id, line.line_index, start, end),
        text=" ".join(w.word for w in words if w.word),
        pronunciation=pron,
        provenance=Provenance.note,
        dialect=dialect,
        ephemeral=False,
        pronunciation_override=pron if overridden else None,
        tags=tags,
        snippet_id=snippet_id,
        line_index=line.line_index,
        word_offset=start,
        word_end=end,
    )
    return PatternSpan(
        pos=SpanPos(line.line_index, start, end),
        unit=unit,
        tail=stressed_tail(unit.effective_pronunciation.syllables),
    )


def _allowed(words: Sequence[WordPronunciation], start: int, end: int) -> bool:
    last = words[end - 1]
    if not last.syllables:
        return False
    line_final = end == len(words)
    if end - start == 1:
        return not is_function_word(last) or (line_final and _stressed(last))
    if is_function_word(words[start]):
        return False
    return not is_function_word(last) or (line_final and _stressed(last))


def line_spans(
    snippet_id: str,
    line: LineWords,
    dialect: DialectMap,
    max_span_words: int = MAX_SPAN_WORDS,
    tags: Sequence[str] = (),
) -> tuple[PatternSpan, ...]:
    """Every detection span of the line, by start then length."""
    spans: list[PatternSpan] = []
    n = len(line.words)
    for start in range(n):
        for end in range(start + 1, min(n, start + max_span_words) + 1):
            if not _allowed(line.words, start, end):
                continue
            span = build_span(snippet_id, line, start, end, dialect, tags)
            if span is not None:
                spans.append(span)
    return tuple(spans)
