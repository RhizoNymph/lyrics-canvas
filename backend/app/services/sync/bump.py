"""Raising content versions (docs/features/sync_versions.md#versions).

Bumps are SQL expressions (``version = version + 1``) so concurrent writers never lose one; the
services reload the row after commit, which reads the new value. Only content changes call these;
layout, viewport and phonetics-only writes never do.
"""

from __future__ import annotations

import uuid
from collections.abc import Iterable

from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.canvas import Canvas
from app.models.snippet import Snippet
from app.models.tag import Tag
from app.models.word_bank import BankWord

type Versioned = Snippet | Canvas | Tag | BankWord


def bump(entity: Versioned) -> None:
    """Raise one loaded entity's version by 1 on the next flush."""
    entity.version = type(entity).version + 1


async def bump_words(db: AsyncSession, word_ids: Iterable[uuid.UUID]) -> None:
    ids = list(set(word_ids))
    if ids:
        await db.execute(
            update(BankWord).where(BankWord.id.in_(ids)).values(version=BankWord.version + 1)
            .execution_options(synchronize_session=False)
        )
