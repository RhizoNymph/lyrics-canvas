"""HTTP mapping of the word bank errors: the tag error body plus ``index`` for a batch's
``invalid_word`` and ``Retry-After`` for ``related_words_warming``."""

from __future__ import annotations

from fastapi import HTTPException

from app.api.tag_errors import tag_http_error
from app.schemas.word_bank import WordBankErrorBody, WordBankErrorResponse
from app.services.tag_errors import TagServiceError
from app.services.word_bank.errors import InvalidWordError, RelatedWordsWarmingError


def word_bank_http_error(exc: TagServiceError) -> HTTPException:
    match exc:
        case InvalidWordError(index=int() as index):
            body = WordBankErrorBody(error=exc.code.value, message=exc.message, index=index)
            return HTTPException(status_code=exc.status, detail=body.model_dump(exclude_none=True))
        case RelatedWordsWarmingError():
            body = WordBankErrorBody(error=exc.code.value, message=exc.message)
            return HTTPException(
                status_code=exc.status,
                detail=body.model_dump(exclude_none=True),
                headers={"Retry-After": str(exc.retry_after)},
            )
        case _:
            return tag_http_error(exc)


def word_bank_errors(*statuses: int) -> dict[int | str, dict[str, object]]:
    return {status: {"model": WordBankErrorResponse} for status in statuses}
