"""Typed sync failures (docs/features/sync_versions.md#errors). Each carries its wire code and
HTTP status; app/api/sync_errors.py turns them into
``HTTPException(409, {"error": code, "message": message[, "current": ...]})``."""

from __future__ import annotations

from enum import StrEnum
from typing import ClassVar


class SyncErrorCode(StrEnum):
    version_conflict = "version_conflict"
    id_conflict = "id_conflict"


class SyncError(Exception):
    code: ClassVar[SyncErrorCode]
    status: ClassVar[int]

    def __init__(self, message: str) -> None:
        super().__init__(message)
        self.message = message


class VersionConflictError[T](SyncError):
    """A ``base_version`` that is not the entity's current version; nothing changed.

    ``current`` is the server's entity as the service holds it (a ``PlacedSnippet``, ``Canvas``,
    ``TagSummary`` or ``BankWord``), or a list of them for the bulk word endpoints and merge;
    the route renders it as its response model.
    """

    code = SyncErrorCode.version_conflict
    status = 409

    def __init__(self, message: str, current: T) -> None:
        super().__init__(message)
        self.current = current


class IdConflictError(SyncError):
    """A client-chosen id names a row of another user, or a row the identical create did not make."""

    code = SyncErrorCode.id_conflict
    status = 409
