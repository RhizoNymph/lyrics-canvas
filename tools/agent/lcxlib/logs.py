"""Structured key=value logging for lcx."""

from __future__ import annotations

import json
import logging

log = logging.getLogger("lcx")


def kv(event: str, **fields: object) -> str:
    """A structured log line: ``event k=v k=v``."""
    return " ".join([event, *(f"{k}={json.dumps(v) if isinstance(v, str) and ' ' in v else v}" for k, v in fields.items())])
