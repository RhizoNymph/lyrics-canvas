"""The excerpt of a snippet search hit: the first display line containing the first term (else the
first non-empty display line), at most EXCERPT_MAX code points, cut around the match with "…"."""

from __future__ import annotations

import unicodedata
from collections.abc import Sequence

from app.services.rest_marks import display_lines

EXCERPT_MAX = 160
LEAD = 40  # code points kept before the match when the line is cut
ELLIPSIS = "…"


def _window(line: str, at: int) -> str:
    if len(line) <= EXCERPT_MAX:
        return line
    start = max(0, at - LEAD)
    if start == 0:
        return line[: EXCERPT_MAX - 1] + ELLIPSIS
    body = EXCERPT_MAX - 2
    if start + body + 1 >= len(line):
        # the match is near the end: the window ends with the line
        return ELLIPSIS + line[len(line) - (EXCERPT_MAX - 1) :]
    return ELLIPSIS + line[start : start + body] + ELLIPSIS


def excerpt(raw_text: str, terms: Sequence[str]) -> str:
    lines = [unicodedata.normalize("NFC", line) for line in display_lines(raw_text)]
    first = terms[0] if terms else ""
    if first:
        for line in lines:
            lowered = line.lower()
            at = lowered.find(first)
            if at >= 0:
                return _window(line, min(at, len(line)))
    for line in lines:
        if line:
            return _window(line, 0)
    return ""
