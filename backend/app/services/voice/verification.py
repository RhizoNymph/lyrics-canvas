"""The user's checked transcript of a recording (contract section 3). Pure; no I/O.

Normalizing: CR LF and lone CR become LF; trailing whitespace is stripped from every line; blank
lines at the start and end are dropped; runs of blank lines collapse to one. The result must be
non-empty and at most ``MAX_VERIFIED_CHARS`` code points, else ``InvalidVerificationError``.
"""

from __future__ import annotations

from typing import NewType

from app.services.voice.errors import InvalidVerificationError

MAX_VERIFIED_CHARS = 10_000

VerifiedText = NewType("VerifiedText", str)  # normalized, 1..MAX_VERIFIED_CHARS code points


def normalize_verification(raw: str) -> VerifiedText:
    lines = [line.rstrip() for line in raw.replace("\r\n", "\n").replace("\r", "\n").split("\n")]
    while lines and not lines[0]:
        lines.pop(0)
    while lines and not lines[-1]:
        lines.pop()
    collapsed: list[str] = []
    for line in lines:
        if not line and collapsed and not collapsed[-1]:
            continue
        collapsed.append(line)
    text = "\n".join(collapsed)
    if not text:
        raise InvalidVerificationError("the checked transcript is empty")
    if len(text) > MAX_VERIFIED_CHARS:
        raise InvalidVerificationError(
            f"the checked transcript is {len(text)} characters; at most {MAX_VERIFIED_CHARS} are allowed"
        )
    return VerifiedText(text)
