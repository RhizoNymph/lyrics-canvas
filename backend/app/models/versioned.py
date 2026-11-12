"""The content version column shared by snippets, canvases, tags and bank words
(docs/features/sync_versions.md#versions).

``version`` starts at 1 and goes up by exactly 1 in the same transaction as each content change
(app/services/sync/bump.py). Layout, viewport and derived values (phonetics) never change it.
"""

from __future__ import annotations

from typing import Any

from sqlalchemy import CheckConstraint, Integer, event, text
from sqlalchemy.orm import Mapped, mapped_column

INITIAL_VERSION = 1


def version_column() -> Mapped[int]:
    return mapped_column(Integer, nullable=False, default=INITIAL_VERSION, server_default=text("1"))


def starts_at_initial_version(model: type) -> type:
    """Class decorator: a freshly constructed entity reads ``version == 1`` before it is flushed
    (the column default only applies on INSERT), so a new object is never versionless."""

    @event.listens_for(model, "init")
    def _initial(target: Any, args: tuple, kwargs: dict) -> None:
        kwargs.setdefault("version", INITIAL_VERSION)

    return model


def version_check(table: str) -> CheckConstraint:
    """``version >= 1``, named as migration 009 names it."""
    return CheckConstraint("version >= 1", name=f"ck_{table}_version_positive")
