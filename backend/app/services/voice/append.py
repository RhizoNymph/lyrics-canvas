"""Append voice-take lines to an existing snippet's content. Pure; no I/O.

The existing grid lines are copied verbatim (rests, hand-set stress, client keys such as
``arranged``); only the new lines are syllabified. A blank trailing line (text or grid) is
reused instead of leaving a gap. ``PUT /api/snippets/{id}`` with only ``raw_text`` would
re-syllabify every line, which is why appends do not go through it.
"""

from __future__ import annotations

import copy
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from app.services.nlp.syllabifier import syllabify_text


class AppendError(Exception):
    """Nothing (or a blank line) to append."""


@dataclass(frozen=True, slots=True)
class AppendedContent:
    raw_text: str
    grid_data: dict  # {"lines": [...]} - existing lines verbatim, then the new syllabified lines


def _strip_trailing_blank_text(raw_text: str) -> str:
    lines = raw_text.split("\n")
    while lines and not lines[-1].strip():
        lines.pop()
    return "\n".join(lines)


def _is_blank_grid_line(line: Mapping[str, object]) -> bool:
    text = line.get("raw_text", "")
    if isinstance(text, str) and text.strip():
        return False
    cells = line.get("cells", [])
    if not isinstance(cells, list):
        return False
    return all(isinstance(cell, Mapping) and cell.get("rest") for cell in cells)


def append_lines(raw_text: str, grid_data: Mapping[str, object] | None, new_lines: Sequence[str]) -> AppendedContent:
    cleaned = [line.strip() for line in new_lines]
    if not cleaned or not all(cleaned):
        raise AppendError("need at least one non-blank line to append")
    base_text = _strip_trailing_blank_text(raw_text)
    if grid_data is None:
        existing = syllabify_text(base_text)["lines"] if base_text.strip() else []
    else:
        stored = grid_data.get("lines", [])
        existing = [copy.deepcopy(line) for line in stored] if isinstance(stored, list) else []
        while existing and isinstance(existing[-1], Mapping) and _is_blank_grid_line(existing[-1]):
            existing.pop()
    added_text = "\n".join(cleaned)
    added = syllabify_text(added_text)["lines"]
    text = f"{base_text}\n{added_text}" if base_text.strip() else added_text
    return AppendedContent(raw_text=text, grid_data={"lines": [*existing, *added]})
