"""Pure rules of the token routes: name, expiry and the last-used throttle."""

from __future__ import annotations

from datetime import datetime, timedelta

from app.models.api_token import NAME_MAX_LENGTH
from app.services.api_tokens.errors import InvalidTokenNameError

DEFAULT_EXPIRES_IN_DAYS = 90
MIN_EXPIRES_IN_DAYS = 1
MAX_EXPIRES_IN_DAYS = 365
# last_used_at is written at most once per this interval per token
LAST_USED_RESOLUTION = timedelta(minutes=1)


def token_name(raw: str) -> str:
    """The trimmed name: 1-64 code points, else ``InvalidTokenNameError``."""
    name = raw.strip()
    if not name:
        raise InvalidTokenNameError("the token name is empty")
    if len(name) > NAME_MAX_LENGTH:
        raise InvalidTokenNameError(f"the token name is longer than {NAME_MAX_LENGTH} characters")
    return name


def expires_at(created: datetime, expires_in_days: int | None) -> datetime | None:
    """None means the token never expires."""
    match expires_in_days:
        case None:
            return None
        case days:
            return created + timedelta(days=days)


def is_live(revoked_at: datetime | None, expires: datetime | None, now: datetime) -> bool:
    return revoked_at is None and (expires is None or now < expires)


def needs_touch(last_used_at: datetime | None, now: datetime) -> bool:
    return last_used_at is None or now - last_used_at >= LAST_USED_RESOLUTION
