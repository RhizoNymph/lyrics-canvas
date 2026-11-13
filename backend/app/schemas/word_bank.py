"""Word bank request/response models (docs/features/word_bank.md#http-api).

Word texts, ids and batch sizes are plain values here; the routes validate them so failures carry
the contract's error codes (``invalid_word``, ``too_many_words``, ``word_not_found``,
``tag_not_found``) instead of pydantic's.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from app.schemas.tag import SnippetTagResponse, TagErrorBody
from app.schemas.versioning import BaseVersion, BaseVersions, ClientId

_RESPONSE_CONFIG = {"from_attributes": True, "json_schema_serialization_defaults_required": True}

MAX_ADD_WORDS = 200
MAX_ADD_TAGS = 20
MAX_IDS = 500
MAX_RHYME_K = 4


class BankWordResponse(BaseModel):
    id: str
    text: str
    tags: list[SnippetTagResponse]  # sorted by lower(name)
    created_at: datetime
    version: int  # +1 per text change and per tag link or unlink

    model_config = _RESPONSE_CONFIG


class BankWordNew(BaseModel):
    """A word to add with a client-chosen id."""

    text: str
    id: ClientId | None = None


class BankWordsCreate(BaseModel):
    words: list[str | BankWordNew] = Field(
        min_length=1, description=f"1..{MAX_ADD_WORDS} words (plain text, or {{text, id}}); more is too_many_words"
    )
    tag_ids: list[str] = Field(default_factory=list, description=f"0..{MAX_ADD_TAGS} tag ids; more is too_many_words")


class BankWordsCreateResponse(BaseModel):
    created: list[BankWordResponse]
    existing: list[BankWordResponse]

    model_config = _RESPONSE_CONFIG


class BankWordUpdate(BaseModel):
    text: str
    base_version: BaseVersion | None = None


class BankWordsDelete(BaseModel):
    ids: list[str] = Field(min_length=1, description=f"1..{MAX_IDS} ids; more is too_many_words")
    base_versions: BaseVersions | None = None


class BankWordTagsUpdate(BaseModel):
    word_ids: list[str] = Field(min_length=1, description=f"1..{MAX_IDS} ids; more is too_many_words")
    tag_id: str
    op: Literal["link", "unlink"]
    base_versions: BaseVersions | None = None


class BankWordsResponse(BaseModel):
    words: list[BankWordResponse]

    model_config = _RESPONSE_CONFIG


class BankRhymesRequest(BaseModel):
    """Exactly one of ``word_id`` and ``text``."""

    word_id: str | None = None
    text: str | None = None
    tag_filter: str | None = None
    min_score: float = Field(0.5, ge=0.0, le=1.0)
    k: int = Field(2, ge=1, le=MAX_RHYME_K)
    limit: int = Field(100, ge=1, le=200)

    @model_validator(mode="after")
    def _one_anchor(self) -> BankRhymesRequest:
        if (self.word_id is None) == (self.text is None):
            raise ValueError("exactly one of word_id and text is required")
        return self


class BankRhymeAnchor(BaseModel):
    text: str
    rhyme_key: list[str]  # empty when the text has no pronounceable syllable
    syllable_count: int

    model_config = _RESPONSE_CONFIG


class BankRhymeMatch(BaseModel):
    word: BankWordResponse
    score: float
    match_depth: int

    model_config = _RESPONSE_CONFIG


class BankRhymesResponse(BaseModel):
    anchor: BankRhymeAnchor
    matches: list[BankRhymeMatch]

    model_config = _RESPONSE_CONFIG


class BankFamiliesRequest(BaseModel):
    tag_filter: str | None = None
    min_score: float = Field(0.8, ge=0.0, le=1.0)
    k: int = Field(2, ge=1, le=MAX_RHYME_K)


class BankFamily(BaseModel):
    label: str
    rhyme_key: list[str]
    words: list[BankWordResponse]  # medoid first
    cohesion: float

    model_config = _RESPONSE_CONFIG


class BankFamiliesResponse(BaseModel):
    families: list[BankFamily]
    unmatched: list[BankWordResponse]

    model_config = _RESPONSE_CONFIG


class RelatedWordResult(BaseModel):
    text: str
    similarity: float
    bank_word_id: str | None  # set when the user already has the word

    model_config = _RESPONSE_CONFIG


class RelatedWordsResponse(BaseModel):
    query: str
    results: list[RelatedWordResult]

    model_config = _RESPONSE_CONFIG


class WordBankErrorBody(TagErrorBody):
    index: int | None = None  # only for invalid_word in a batch: the first bad position


class WordBankErrorResponse(BaseModel):
    detail: WordBankErrorBody
