"""Search DB tests reuse the placement fixtures: a migrated throwaway database (TAG_TEST_DATABASE_URL,
see tests/tags/db.py) and a session on it, plus an HTTP client acting as a given user."""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import httpx
import pytest
from fastapi import Request
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from tests.placements.conftest import db, migrated  # noqa: F401  (fixtures)
from tests.tags.db import DB_URL

USER_HEADER = "x-test-user"


@pytest.fixture
async def api(db):  # noqa: F811
    """``api(user_id)`` -> an httpx client acting as that user; each request gets its own session."""
    engine = create_async_engine(DB_URL, poolclass=NullPool)
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
