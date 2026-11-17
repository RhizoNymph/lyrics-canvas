"""Token secrets: format, hashing, display prefix (docs/features/api_tokens.md#token-format).

A token is ``lct_`` + ``secrets.token_urlsafe(32)`` (43 URL-safe chars): 47 chars, 256 bits of
randomness. Only ``sha256(token)`` hex is stored, so a database leak does not leak usable tokens; a
fast hash is enough because the secret is random, not a password.
"""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass

from app.models.api_token import PREFIX_LENGTH

TOKEN_PREFIX = "lct_"
SECRET_CHARS = 43
TOKEN_LENGTH = len(TOKEN_PREFIX) + SECRET_CHARS


@dataclass(frozen=True, slots=True)
class NewSecret:
    token: str  # shown once, never stored or logged
    token_hash: str
    prefix: str


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def display_prefix(token: str) -> str:
    return token[:PREFIX_LENGTH]


def is_api_token(bearer: str) -> bool:
    """Whether a bearer credential is meant as an API token (as opposed to a login JWT)."""
    return bearer.startswith(TOKEN_PREFIX)


def new_secret() -> NewSecret:
    token = TOKEN_PREFIX + secrets.token_urlsafe(32)[:SECRET_CHARS]
    return NewSecret(token=token, token_hash=hash_token(token), prefix=display_prefix(token))
