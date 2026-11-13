"""Rhyme patterns across lines: spans, pairwise scoring, slot detection, slot candidates and
whole-line matches (docs/features/rhyme_patterns.md). Pure: engine types in, engine types out."""

from app.services.phonetics.pattern.detect import DETECT_MIN_SCORE, detect
from app.services.phonetics.pattern.pair import PairScorer
from app.services.phonetics.pattern.slots import MAX_SLOTS, cap_slots, order_slots, slot_shape, summarize
from app.services.phonetics.pattern.spans import MAX_SPAN_WORDS, STOP_WORDS, build_span, is_function_word, line_spans
from app.services.phonetics.pattern.types import LineWords, PatternSlot, PatternSpan, SpanPos, default_on

__all__ = [
    "DETECT_MIN_SCORE",
    "MAX_SLOTS",
    "MAX_SPAN_WORDS",
    "STOP_WORDS",
    "LineWords",
    "PairScorer",
    "PatternSlot",
    "PatternSpan",
    "SpanPos",
    "build_span",
    "cap_slots",
    "default_on",
    "detect",
    "is_function_word",
    "line_spans",
    "order_slots",
    "slot_shape",
    "summarize",
]
