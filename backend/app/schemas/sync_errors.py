"""409 bodies of the sync contract (docs/features/sync_versions.md#errors), one model per entity
so the OpenAPI document says what ``current`` holds."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel

from app.schemas.canvas import CanvasResponse
from app.schemas.snippet import SnippetResponse
from app.schemas.tag import TagResponse
from app.schemas.word_bank import BankWordResponse


class IdConflictBody(BaseModel):
    error: Literal["id_conflict"]
    message: str


class IdConflictResponse(BaseModel):
    detail: IdConflictBody


class _VersionConflictBody(BaseModel):
    error: Literal["version_conflict"]
    message: str


class SnippetVersionConflictBody(_VersionConflictBody):
    current: SnippetResponse


class SnippetsVersionConflictBody(_VersionConflictBody):
    current: list[SnippetResponse]  # the stale merge originals


class CanvasVersionConflictBody(_VersionConflictBody):
    current: CanvasResponse


class TagVersionConflictBody(_VersionConflictBody):
    current: TagResponse


class WordVersionConflictBody(_VersionConflictBody):
    current: BankWordResponse


class WordsVersionConflictBody(_VersionConflictBody):
    current: list[BankWordResponse]  # the stale words of a bulk request


class SnippetVersionConflictResponse(BaseModel):
    detail: SnippetVersionConflictBody


class SnippetsVersionConflictResponse(BaseModel):
    detail: SnippetsVersionConflictBody


class CanvasVersionConflictResponse(BaseModel):
    detail: CanvasVersionConflictBody


class TagVersionConflictResponse(BaseModel):
    detail: TagVersionConflictBody


class WordVersionConflictResponse(BaseModel):
    detail: WordVersionConflictBody


class WordsVersionConflictResponse(BaseModel):
    detail: WordsVersionConflictBody
