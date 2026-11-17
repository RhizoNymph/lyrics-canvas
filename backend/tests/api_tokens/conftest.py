"""Fixtures for the API token DB tests (docs/features/api_tokens.md): a migrated throwaway database
(see tests/tags/db.py), a session on it, and HTTP clients that authenticate for real (only get_db is
overridden). Skipped with the tests unless TAG_TEST_DATABASE_URL is set."""

from __future__ import annotations

import uuid
from collections.abc import AsyncIterator

import httpx
import pytest
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.pool import NullPool

from app.db.database import get_db
from app.main import app
from app.models.user import User
from app.services.auth_service import create_access_token
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


@pytest.fixture
async def user(db) -> uuid.UUID:
    row = User(id=uuid.uuid4(), email=f"{uuid.uuid4()}@example.test", display_name="t")
    db.add(row)
    await db.commit()
    return row.id


@pytest.fixture
async def bearer(db):
    """``bearer(token)`` -> an httpx client sending ``Authorization: Bearer <token>``."""
    engine = create_async_engine(DB_URL, poolclass=NullPool)
    sessions = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

    async def test_db():
        async with sessions() as session:
            yield session

    app.dependency_overrides[get_db] = test_db
    clients: list[httpx.AsyncClient] = []

    def with_token(token: str) -> httpx.AsyncClient:
        client = httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app), base_url="http://test", headers={"Authorization": f"Bearer {token}"}
        )
        clients.append(client)
        return client

    yield with_token
    for client in clients:
        await client.aclose()
    app.dependency_overrides.clear()
    await engine.dispose()


@pytest.fixture
def login(bearer):
    """``login(user_id)`` -> a client with a login JWT for that user."""
    return lambda user_id: bearer(create_access_token(str(user_id)))
