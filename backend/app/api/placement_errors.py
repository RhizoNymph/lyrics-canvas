"""HTTP mapping of the typed canvas/placement errors: ``detail = {"error": <code>, "message": <text>}``."""

from __future__ import annotations

from fastapi import HTTPException

from app.schemas.placement import PlacementErrorBody, PlacementErrorResponse
from app.services.placements.errors import PlacementError


def placement_http_error(exc: PlacementError) -> HTTPException:
    body = PlacementErrorBody(error=exc.code.value, message=exc.message)
    return HTTPException(status_code=exc.status, detail=body.model_dump())


def placement_errors(*statuses: int) -> dict[int | str, dict[str, object]]:
    return {status: {"model": PlacementErrorResponse} for status in statuses}
