"""``/api/tokens`` and API-token authentication end to end on Postgres (see tests/tags/db.py)."""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta

import pytest
from sqlalchemy import select, text, update

from app.models.api_token import ApiToken
from app.services.api_tokens.secrets import hash_token
from tests.tags.db import requires_db

pytestmark = requires_db

TOKEN_KEYS = {"id", "name", "prefix", "created_at", "last_used_at", "expires_at"}


def error_of(res, status: int, code: str) -> dict:
    assert res.status_code == status, res.text
    detail = res.json()["detail"]
    assert detail["error"] == code
    assert isinstance(detail["message"], str) and detail["message"]
    return detail


def parse(ts: str) -> datetime:
    return datetime.fromisoformat(ts)


async def create(client, name: str = "agent", **body) -> dict:
    res = await client.post("/api/tokens", json={"name": name, **body})
    assert res.status_code == 201, res.text
    return res.json()


async def stored(db, token_id: str) -> ApiToken:
    result = await db.execute(
        select(ApiToken).where(ApiToken.id == uuid.UUID(token_id)).execution_options(populate_existing=True)
    )
    return result.scalar_one()


# ---- create / list / revoke ----------------------------------------------------------------


async def test_create_returns_the_secret_once_and_stores_only_its_hash(db, user, login):
    made = await create(login(user), "  laptop agent  ")
    assert set(made) == {"token", "secret"}
    secret, token = made["secret"], made["token"]
    assert set(token) == TOKEN_KEYS
    assert secret.startswith("lct_") and len(secret) == 47
    assert token["name"] == "laptop agent"
    assert token["prefix"] == secret[:12]
    assert token["last_used_at"] is None
    assert parse(token["expires_at"]) - parse(token["created_at"]) == timedelta(days=90)
    row = await stored(db, token["id"])
    assert row.token_hash == hash_token(secret) and row.user_id == user and row.revoked_at is None
    # nothing returns the secret again
    listed = (await login(user).get("/api/tokens")).json()
    assert secret not in str(listed)


async def test_expiry_choices(db, user, login):
    client = login(user)
    never = (await create(client, "never", expires_in_days=None))["token"]
    assert never["expires_at"] is None
    month = (await create(client, "month", expires_in_days=30))["token"]
    assert parse(month["expires_at"]) - parse(month["created_at"]) == timedelta(days=30)
    year = (await create(client, "year", expires_in_days=365))["token"]
    assert parse(year["expires_at"]) - parse(year["created_at"]) == timedelta(days=365)
    for bad in (0, 366, -1):
        res = await client.post("/api/tokens", json={"name": "x", "expires_in_days": bad})
        assert res.status_code == 422


@pytest.mark.parametrize("name", ["", "   ", "x" * 65])
async def test_invalid_names(db, user, login, name):
    error_of(await login(user).post("/api/tokens", json={"name": name}), 422, "invalid_token_name")
    assert (await login(user).get("/api/tokens")).json() == []


async def test_list_is_newest_first_without_revoked_and_only_mine(db, user, login):
    other = uuid.uuid4()
    await db.execute(text("INSERT INTO users (id, email, display_name) VALUES (:u, :e, 'o')"), {"u": other, "e": f"{other}@x.test"})
    await db.commit()
    client = login(user)
    first = (await create(client, "first"))["token"]
    second = (await create(client, "second"))["token"]
    gone = (await create(client, "gone"))["token"]
    await create(login(other), "theirs")
    assert (await client.delete(f"/api/tokens/{gone['id']}")).status_code == 204
    listed = (await client.get("/api/tokens")).json()
    assert [t["id"] for t in listed] == [second["id"], first["id"]]
    assert all(set(t) == TOKEN_KEYS for t in listed)


async def test_revoke(db, user, login, bearer):
    client = login(user)
    made = await create(client)
    token_id, agent = made["token"]["id"], bearer(made["secret"])
    assert (await agent.get("/api/canvases")).status_code == 200
    assert (await client.delete(f"/api/tokens/{token_id}")).status_code == 204
    assert (await stored(db, token_id)).revoked_at is not None
    error_of(await client.delete(f"/api/tokens/{token_id}"), 404, "token_not_found")
    error_of(await client.delete(f"/api/tokens/{uuid.uuid4()}"), 404, "token_not_found")
    # a revoked token stops working at once; the login keeps working
    assert (await agent.get("/api/canvases")).status_code == 401
    assert (await login(user).get("/api/canvases")).status_code == 200


async def test_cannot_revoke_someone_elses_token(db, user, login):
    other = uuid.uuid4()
    await db.execute(text("INSERT INTO users (id, email, display_name) VALUES (:u, :e, 'o')"), {"u": other, "e": f"{other}@x.test"})
    await db.commit()
    theirs = (await create(login(other), "theirs"))["token"]
    error_of(await login(user).delete(f"/api/tokens/{theirs['id']}"), 404, "token_not_found")
    assert (await stored(db, theirs["id"])).revoked_at is None


# ---- authentication with a token ---------------------------------------------------------


async def test_a_token_authenticates_like_its_user(db, user, login, bearer):
    secret = (await create(login(user)))["secret"]
    res = await bearer(secret).post("/api/canvases", json={"title": "Via token"})
    assert res.status_code == 201, res.text
    assert res.json()["owner_id"] == str(user)
    titles = [c["title"] for c in (await bearer(secret).get("/api/canvases")).json()]
    assert titles == ["Via token"]


async def test_revoked_expired_and_unknown_tokens_are_401(db, user, login, bearer):
    client = login(user)
    revoked = await create(client, "revoked")
    await client.delete(f"/api/tokens/{revoked['token']['id']}")
    expired = await create(client, "expired")
    await db.execute(
        update(ApiToken).where(ApiToken.id == uuid.UUID(expired["token"]["id"])).values(expires_at=text("now() - interval '1 second'"))
    )
    await db.commit()
    for secret in (revoked["secret"], expired["secret"], "lct_" + "A" * 43, "lct_"):
        res = await bearer(secret).get("/api/canvases")
        assert res.status_code == 401, (secret[:12], res.text)
        assert res.json() == {"detail": "Invalid token"}


async def test_jwt_path_is_unchanged(db, user, login, bearer):
    assert (await login(user).get("/api/canvases")).status_code == 200
    assert (await bearer("not-a-jwt").get("/api/canvases")).status_code == 401


async def test_token_management_needs_a_login(db, user, login, bearer):
    made = await create(login(user))
    agent = bearer(made["secret"])
    error_of(await agent.get("/api/tokens"), 403, "token_management_forbidden")
    error_of(await agent.post("/api/tokens", json={"name": "child"}), 403, "token_management_forbidden")
    error_of(await agent.delete(f"/api/tokens/{made['token']['id']}"), 403, "token_management_forbidden")
    listed = (await login(user).get("/api/tokens")).json()
    assert [t["name"] for t in listed] == ["agent"]


async def test_last_used_is_written_at_most_once_a_minute(db, user, login, bearer):
    made = await create(login(user))
    token_id, agent = made["token"]["id"], bearer(made["secret"])
    await agent.get("/api/canvases")
    first = (await stored(db, token_id)).last_used_at
    assert first is not None
    await agent.get("/api/canvases")
    assert (await stored(db, token_id)).last_used_at == first
    # 30 s ago: still within the minute, untouched
    await db.execute(update(ApiToken).where(ApiToken.id == uuid.UUID(token_id)).values(last_used_at=text("now() - interval '30 seconds'")))
    await db.commit()
    held = (await stored(db, token_id)).last_used_at
    await agent.get("/api/canvases")
    assert (await stored(db, token_id)).last_used_at == held
    # 2 minutes ago: rewritten
    await db.execute(update(ApiToken).where(ApiToken.id == uuid.UUID(token_id)).values(last_used_at=text("now() - interval '2 minutes'")))
    await db.commit()
    stale = (await stored(db, token_id)).last_used_at
    await agent.get("/api/canvases")
    assert (await stored(db, token_id)).last_used_at > stale
    listed = (await login(user).get("/api/tokens")).json()
    assert listed[0]["last_used_at"] is not None


async def test_the_secret_is_never_logged(db, user, login, bearer, caplog):
    caplog.set_level(logging.DEBUG)
    made = await create(login(user))
    await bearer(made["secret"]).get("/api/canvases")
    assert made["secret"] not in caplog.text
    assert f"auth.api_token user_id={user} token_id={made['token']['id']}" in caplog.text


async def test_deleting_the_user_deletes_the_tokens(db, user, login):
    made = await create(login(user))
    await db.execute(text("DELETE FROM users WHERE id = :u"), {"u": user})
    await db.commit()
    count = (await db.execute(text("SELECT count(*) FROM api_tokens WHERE id = :i"), {"i": uuid.UUID(made["token"]["id"])})).scalar_one()
    assert count == 0
