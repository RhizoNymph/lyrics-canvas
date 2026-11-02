"""Structured key=value logging on stderr via the stdlib."""

from __future__ import annotations

import logging
import sys
import traceback
from datetime import UTC, datetime

_FIELDS = "kv"
_LEVELS = {"debug": logging.DEBUG, "info": logging.INFO, "warning": logging.WARNING, "error": logging.ERROR}


def _quote(value: object) -> str:
    if value is None:
        return "none"
    text = str(value)
    if text and not any(c.isspace() or c in '"\\=' for c in text):
        return text
    escaped = text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n")
    return f'"{escaped}"'


def log_event(logger: logging.Logger, level: int, event: str, **fields: object) -> None:
    """Log `event` with key=value fields: log_event(log, INFO, "model.ready", load_s=3.2)."""
    logger.log(level, event, extra={_FIELDS: fields})


class KeyValueFormatter(logging.Formatter):
    """`ts=... level=... logger=... event=... k=v ...` on one line.

    Records from `log_event` carry their fields; plain library records (uvicorn,
    faster-whisper) become `event=log msg="..."`.
    """

    def format(self, record: logging.LogRecord) -> str:
        ts = datetime.fromtimestamp(record.created, UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        parts = [f"ts={ts}", f"level={record.levelname.lower()}", f"logger={record.name}"]
        fields = getattr(record, _FIELDS, None)
        if isinstance(fields, dict):
            parts.append(f"event={_quote(record.msg)}")
            parts.extend(f"{key}={_quote(value)}" for key, value in fields.items())
        else:
            parts.append("event=log")
            parts.append(f"msg={_quote(record.getMessage())}")
        if record.exc_info:
            exc_type, exc, tb = record.exc_info
            name = exc_type.__name__ if exc_type else "Exception"
            parts.append(f"exc={_quote(f'{name}: {exc}')}")
            parts.append(f"trace={_quote(''.join(traceback.format_tb(tb)))}")
        return " ".join(parts)


def configure_logging(level: str) -> None:
    handler = logging.StreamHandler(sys.stderr)
    handler.setFormatter(KeyValueFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(_LEVELS[level])
    # uvicorn installs its own handlers unless told otherwise; route through root.
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        lg = logging.getLogger(name)
        lg.handlers.clear()
        lg.propagate = True
