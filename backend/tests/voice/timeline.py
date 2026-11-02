"""Builders for timed-word fixtures and a deterministic stand-in for the phonetic engine."""

from __future__ import annotations

from collections.abc import Iterable, Mapping

from app.services.voice.line_assembly import TimedWord

WORD_MS = 300
GAP_MS = 100


def timeline(spec: str, *, word_ms: int = WORD_MS, gap_ms: int = GAP_MS, pauses: Mapping[int, int] | None = None) -> list[TimedWord]:
    """Words of ``word_ms`` separated by ``gap_ms``; ``pauses`` maps a boundary index (after word i)
    to a different gap. With the defaults word i spans [400 i, 400 i + 300] and boundary i sits at
    400 i + 350."""
    pauses = dict(pauses or {})
    words: list[TimedWord] = []
    t = 0
    for i, text in enumerate(spec.split()):
        words.append(TimedWord(text=text, start_ms=t, end_ms=t + word_ms))
        t += word_ms + pauses.get(i, gap_ms)
    return words


def numbered(n: int, prefix: str = "w") -> str:
    return " ".join(f"{prefix}{i}" for i in range(n))


class FakeOracle:
    """Syllables from a table (default 1); rhymes only between the listed pairs."""

    def __init__(self, syllables: Mapping[str, int] | None = None, rhymes: Iterable[tuple[str, str]] = ()):
        self._syllables = dict(syllables or {})
        self._rhymes = {frozenset(pair) for pair in rhymes}
        self.rhyme_calls = 0

    def syllables(self, word: str) -> int:
        return self._syllables.get(word, 1)

    def end_rhyme(self, a: str, b: str) -> float:
        self.rhyme_calls += 1
        return 1.0 if frozenset((a, b)) in self._rhymes else 0.0
