"""Fixtures for the placement DB tests: a migrated throwaway database (see tests/tags/db.py) and a
session on it. Skipped with the tests unless TAG_TEST_DATABASE_URL is set."""

from __future__ import annotations

from collections.abc import AsyncIterator

import pytest
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from tests.tags.db import DB_URL, alembic


@pytest.fixture(scope="module")
def migrated():
    alembic("downgrade", "base")
    alembic("upgrade", "head")
    yield
    alembic("downgrade", "base")


@pytest.fixture
async def db(migrated) -> AsyncIterator[AsyncSession]:
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)()
    yield session
    await session.close()
    await engine.dispose()
