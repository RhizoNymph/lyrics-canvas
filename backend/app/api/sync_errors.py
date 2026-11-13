"""HTTP mapping of the typed sync errors (docs/features/sync_versions.md#errors):
``detail = {"error": "version_conflict", "message": <text>, "current": <entity or list>}`` and
``detail = {"error": "id_conflict", "message": <text>}``, both 409."""

from __future__ import annotations

from collections.abc import Callable
from typing import Protocol, TypeVar

from fastapi import HTTPException, Query
from pydantic import BaseModel

from app.services.sync.errors import IdConflictError, VersionConflictError


T = TypeVar("T")


class Dumpable(Protocol):
    def model_dump(self, *, mode: str) -> object: ...


def version_conflict_http_error(exc: VersionConflictError[T] | VersionConflictError[list[T]], render: Callable[[T], Dumpable]) -> HTTPException:
    """``current`` is ``render(entity)``, or a list of them when the conflict carries a list."""
    current = exc.current
    body = [render(c).model_dump(mode="json") for c in current] if isinstance(current, list) else render(current).model_dump(mode="json")
    return HTTPException(status_code=exc.status, detail={"error": exc.code.value, "message": exc.message, "current": body})


def id_conflict_http_error(exc: IdConflictError) -> HTTPException:
    return HTTPException(status_code=exc.status, detail={"error": exc.code.value, "message": exc.message})


def conflict_responses(*models: type[BaseModel]) -> dict[int | str, dict[str, object]]:
    """The OpenAPI entry for a route's 409 bodies (one model, or any of several)."""
    model = models[0] if len(models) == 1 else _union(models)
    return {409: {"model": model}}


def _union(models: tuple[type[BaseModel], ...]) -> object:
    union = models[0]
    for model in models[1:]:
        union = union | model
    return union


def base_version_query():
    """The optional ``?base_version=`` of a delete (docs/features/sync_versions.md#base_version)."""
    return Query(None, ge=1, description="the entity version this change is based on; a mismatch is 409 version_conflict")
