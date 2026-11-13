"""Fixtures for the sync DB tests (docs/features/sync_versions.md): a migrated throwaway database
(see tests/tags/db.py), a session on it, and ``api(user_id)`` HTTP clients acting as a user.
Skipped with the tests unless TAG_TEST_DATABASE_URL is set."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator
from types import SimpleNamespace

import httpx
import pytest
from fastapi import Request
from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from tests.tags.db import DB_URL, alembic

USER_HEADER = "x-test-user"


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


@pytest.fixture
def statements() -> list[str]:
    """Every SQL statement the app ran through the ``api`` clients, in order."""
    return []


@pytest.fixture
async def api(db, statements):
    """``api(user_id)`` -> an httpx client acting as that user; each request gets its own session."""
    engine = create_async_engine(DB_URL, poolclass=NullPool)

    @event.listens_for(engine.sync_engine, "before_cursor_execute")
    def _record(conn, cursor, statement, parameters, context, executemany):
        statements.append(statement)

    sessions = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async def test_db():
        async with sessions() as session:
            yield session

    def test_user(request: Request) -> SimpleNamespace:
        return SimpleNamespace(id=uuid.UUID(request.headers[USER_HEADER]))

    app.dependency_overrides[get_db] = test_db
    app.dependency_overrides[get_current_user] = test_user
    clients: list[httpx.AsyncClient] = []

    def as_user(user_id: uuid.UUID) -> httpx.AsyncClient:
        client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test", headers={USER_HEADER: str(user_id)}
        )
        clients.append(client)
        return client

    yield as_user
    for client in clients:
        await client.aclose()
    app.dependency_overrides.clear()
    await engine.dispose()
