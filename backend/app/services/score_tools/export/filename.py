"""Download names and titles for score exports: ``<title-or-snippet>.mid`` / ``.musicxml``."""

from __future__ import annotations

import re
import uuid

TITLE_MAX = 80
SLUG_MAX = 48
FALLBACK_TITLE = "Snippet"


def export_title(raw_text: str | None) -> str:
    """The snippet's first non-blank text line (trimmed, at most 80 code points), else "Snippet"."""
    for line in (raw_text or "").split("\n"):
        stripped = " ".join(line.split())
        if stripped:
            return stripped[:TITLE_MAX]
    return FALLBACK_TITLE


def slug(text: str) -> str:
    """ASCII letters, digits and ``-`` only; runs of anything else become one ``-``; lowercase."""
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")[:SLUG_MAX].strip("-")


def export_filename(raw_text: str | None, snippet_id: uuid.UUID, extension: str) -> str:
    """``<slug of the title>.<extension>``, or ``snippet-<first 8 hex of the id>.<extension>`` when
    the title has no ASCII letter or digit."""
    name = slug(export_title(raw_text)) if raw_text else ""
    return f"{name or 'snippet-' + snippet_id.hex[:8]}.{extension}"
