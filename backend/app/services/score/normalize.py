"""Word and line keys for reconcile (spec/score_format.md section 10)."""

from __future__ import annotations

import unicodedata
from collections.abc import Mapping, Sequence
from typing import Any

from app.services.score.grid_view import line_cells, word_groups

APOSTROPHE = "'"
RIGHT_SINGLE_QUOTE = "’"
FALLBACK_PREFIX = "#"


def _kept(c: str) -> bool:
    return c == APOSTROPHE or unicodedata.category(c).startswith("L") or unicodedata.category(c) == "Nd"


def normalize_word(text: str) -> str:
    """NFC, per-code-point lowercase, ``’`` -> ``'``, keep letters, decimal digits and ``'``."""
    lowered = "".join(c.lower() for c in unicodedata.normalize("NFC", text))
    return "".join(c for c in lowered.replace(RIGHT_SINGLE_QUOTE, APOSTROPHE) if _kept(c))


def word_key(spelling: str) -> str:
    normalized = normalize_word(spelling)
    return normalized if normalized else FALLBACK_PREFIX + unicodedata.normalize("NFC", spelling)


def word_keys(line: Mapping[str, Any]) -> list[str]:
    return [word_key(group.spelling) for group in word_groups(line_cells(line))]


def line_key(line: Mapping[str, Any]) -> str:
    return " ".join(word_keys(line))


def lcs_pairs(a: Sequence[str], b: Sequence[str]) -> list[tuple[int, int]]:
    """A longest common subsequence as index pairs; ``""`` never matches; ties skip ``a`` first."""
    n, m = len(a), len(b)

    def eq(i: int, j: int) -> bool:
        return a[i] == b[j] and a[i] != ""

    table = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        row, below = table[i], table[i + 1]
        for j in range(m - 1, -1, -1):
            row[j] = below[j + 1] + 1 if eq(i, j) else max(below[j], row[j + 1])
    pairs: list[tuple[int, int]] = []
    i = j = 0
    while i < n and j < m:
        if eq(i, j):
            pairs.append((i, j))
            i += 1
            j += 1
        elif table[i + 1][j] >= table[i][j + 1]:
            i += 1
        else:
            j += 1
    return pairs
