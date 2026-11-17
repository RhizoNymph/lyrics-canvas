"""Create, list, revoke and authenticate personal API tokens (docs/features/api_tokens.md)."""

from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime

from sqlalchemy import func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.api_token import ApiToken
from app.models.user import User
from app.services.api_tokens.errors import TokenNotFoundError
from app.services.api_tokens.secrets import hash_token, is_api_token, new_secret
from app.services.api_tokens.values import LAST_USED_RESOLUTION, expires_at, is_live, needs_touch, token_name

logger = logging.getLogger(__name__)


@dataclass(frozen=True, slots=True)
class CreatedToken:
    token: ApiToken
    secret: str  # the full token, returned once


@dataclass(frozen=True, slots=True)
class TokenAuth:
    user: User
    token_id: uuid.UUID


async def _now(db: AsyncSession) -> datetime:
    return (await db.execute(select(func.now()))).scalar_one()


async def create_token(db: AsyncSession, user_id: uuid.UUID, name: str, expires_in_days: int | None) -> CreatedToken:
    clean = token_name(name)
    secret = new_secret()
    now = await _now(db)
    token = ApiToken(
        id=uuid.uuid4(),
        user_id=user_id,
        name=clean,
        token_hash=secret.token_hash,
        prefix=secret.prefix,
        created_at=now,
        expires_at=expires_at(now, expires_in_days),
    )
    db.add(token)
    await db.commit()
    logger.info("api_token.created user_id=%s token_id=%s expires_at=%s", user_id, token.id, token.expires_at)
    return CreatedToken(token=token, secret=secret.token)


async def list_tokens(db: AsyncSession, user_id: uuid.UUID) -> list[ApiToken]:
    """The caller's tokens that are not revoked (expired ones included), newest first."""
    result = await db.execute(
        select(ApiToken)
        .where(ApiToken.user_id == user_id, ApiToken.revoked_at.is_(None))
        .order_by(ApiToken.created_at.desc(), ApiToken.id.desc())
    )
    return list(result.scalars().all())


async def revoke_token(db: AsyncSession, user_id: uuid.UUID, token_id: uuid.UUID) -> None:
    result = await db.execute(
        update(ApiToken)
        .where(ApiToken.id == token_id, ApiToken.user_id == user_id, ApiToken.revoked_at.is_(None))
        .values(revoked_at=func.now())
        .returning(ApiToken.id)
    )
    if result.scalar_one_or_none() is None:
        await db.rollback()
        raise TokenNotFoundError(f"token {token_id} not found")
    await db.commit()
    logger.info("api_token.revoked user_id=%s token_id=%s", user_id, token_id)


async def authenticate(db: AsyncSession, bearer: str) -> TokenAuth | None:
    """The user of a live API token (not revoked, not expired), or None. Writes ``last_used_at`` at
    most once per ``LAST_USED_RESOLUTION`` per token; the conditional UPDATE keeps concurrent requests
    from writing it twice."""
    if not is_api_token(bearer):
        return None
    result = await db.execute(
        select(ApiToken, User).join(User, User.id == ApiToken.user_id).where(ApiToken.token_hash == hash_token(bearer))
    )
    row = result.first()
    if row is None:
        return None
    token, user = row
    now = await _now(db)
    if not is_live(token.revoked_at, token.expires_at, now):
        return None
    if needs_touch(token.last_used_at, now):
        await db.execute(
            update(ApiToken)
            .where(
                ApiToken.id == token.id,
                or_(ApiToken.last_used_at.is_(None), ApiToken.last_used_at <= func.now() - LAST_USED_RESOLUTION),
            )
            .values(last_used_at=func.now())
        )
        await db.commit()
    logger.debug("auth.api_token user_id=%s token_id=%s", user.id, token.id)
    return TokenAuth(user=user, token_id=token.id)
