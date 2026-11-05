"""Tag and preset routes with the service layer faked: wire shapes and the error envelope."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.services import tag_preset_service, tag_service
from app.services.tag_errors import (
    PresetExpressionTooLongError,
    PresetNameTakenError,
    PresetNotFoundError,
    SnippetNotFoundError,
    TagNameTakenError,
    TagNotFoundError,
)
from app.services.tag_service import TagSummary

USER_ID = uuid.uuid4()
CREATED = datetime(2026, 11, 3, 10, 0, 0, tzinfo=timezone.utc)
UPDATED = datetime(2026, 11, 3, 11, 0, 0, tzinfo=timezone.utc)
LOVE = TagSummary(id=uuid.uuid4(), name="love", color="#ff0000", snippet_count=3, created_at=CREATED)
NIGHT = TagSummary(id=uuid.uuid4(), name="Night", color="#6366f1", snippet_count=0, created_at=CREATED)


def preset(name: str = "Dark", expression: str = "night & !love"):
    return SimpleNamespace(id=uuid.uuid4(), user_id=USER_ID, name=name, expression=expression, created_at=CREATED, updated_at=UPDATED)


def tag_json(t: TagSummary) -> dict:
    return {"id": str(t.id), "name": t.name, "color": t.color, "snippet_count": t.snippet_count, "word_count": t.word_count, "created_at": str(t.created_at)}


@pytest.fixture
def client():
    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER_ID)
    yield TestClient(app)
    app.dependency_overrides.clear()


def fake(monkeypatch, module, name, result=None, error: Exception | None = None, seen: list | None = None):
    async def impl(*args):
        if seen is not None:
            seen.append(args[1:])
        if error is not None:
            raise error
        return result

    monkeypatch.setattr(module, name, impl)


def assert_error(res, status: int, code: str) -> dict:
    assert res.status_code == status, res.text
    detail = res.json()["detail"]
    assert detail["error"] == code
    assert isinstance(detail["message"], str) and detail["message"]
    return detail


# ---- tags ----


def test_list_tags(client, monkeypatch):
    seen: list = []
    fake(monkeypatch, tag_service, "list_tags", [LOVE, NIGHT], seen=seen)
    res = client.get("/api/tags")
    assert res.status_code == 200
    assert res.json() == [tag_json(LOVE), tag_json(NIGHT)]
    assert list(res.json()[0]) == ["id", "name", "color", "snippet_count", "word_count", "created_at"]
    assert seen == [(USER_ID,)]


def test_create_tag_trims_and_defaults_color(client, monkeypatch):
    seen: list = []
    fake(monkeypatch, tag_service, "create_tag", LOVE, seen=seen)
    res = client.post("/api/tags", json={"name": "  love "})
    assert res.status_code == 201
    assert res.json() == tag_json(LOVE)
    assert seen == [(USER_ID, "love", "#6366f1")]


@pytest.mark.parametrize(
    ("body", "code"),
    [({"name": ""}, "invalid_tag_name"), ({"name": "a\nb"}, "invalid_tag_name"), ({"name": "x" * 101}, "invalid_tag_name"), ({"name": "ok", "color": "blue"}, "invalid_color")],
)
def test_create_tag_validation(client, monkeypatch, body, code):
    fake(monkeypatch, tag_service, "create_tag", LOVE)
    assert_error(client.post("/api/tags", json=body), 422, code)


def test_create_tag_conflict(client, monkeypatch):
    fake(monkeypatch, tag_service, "create_tag", error=TagNameTakenError("a tag named 'LOVE' already exists"))
    assert_error(client.post("/api/tags", json={"name": "LOVE"}), 409, "tag_name_taken")


def test_update_tag(client, monkeypatch):
    seen: list = []
    fake(monkeypatch, tag_service, "update_tag", NIGHT, seen=seen)
    res = client.put(f"/api/tags/{NIGHT.id}", json={"name": " Night ", "color": "#ABCDEF"})
    assert res.status_code == 200
    assert res.json() == tag_json(NIGHT)
    assert seen == [(NIGHT.id, USER_ID, "Night", "#abcdef")]


def test_update_tag_with_nothing_passes_none(client, monkeypatch):
    seen: list = []
    fake(monkeypatch, tag_service, "update_tag", NIGHT, seen=seen)
    assert client.put(f"/api/tags/{NIGHT.id}", json={}).status_code == 200
    assert seen == [(NIGHT.id, USER_ID, None, None)]


@pytest.mark.parametrize(
    ("error", "status", "code"),
    [
        (TagNotFoundError("tag not found"), 404, "tag_not_found"),
        (TagNameTakenError("taken"), 409, "tag_name_taken"),
        (PresetExpressionTooLongError("too long"), 409, "preset_expression_too_long"),
    ],
)
def test_update_tag_errors(client, monkeypatch, error, status, code):
    fake(monkeypatch, tag_service, "update_tag", error=error)
    assert_error(client.put(f"/api/tags/{uuid.uuid4()}", json={"name": "x"}), status, code)


def test_update_tag_rejects_a_bad_id(client):
    assert client.put("/api/tags/not-a-uuid", json={"name": "x"}).status_code == 422


def test_delete_tag(client, monkeypatch):
    seen: list = []
    fake(monkeypatch, tag_service, "delete_tag", None, seen=seen)
    res = client.delete(f"/api/tags/{LOVE.id}")
    assert res.status_code == 204
    assert seen == [(LOVE.id, USER_ID)]
    fake(monkeypatch, tag_service, "delete_tag", error=TagNotFoundError("tag not found"))
    assert_error(client.delete(f"/api/tags/{LOVE.id}"), 404, "tag_not_found")


def test_link_and_unlink(client, monkeypatch):
    snippet = uuid.uuid4()
    seen: list = []
    fake(monkeypatch, tag_service, "add_tag_to_snippet", None, seen=seen)
    fake(monkeypatch, tag_service, "remove_tag_from_snippet", None, seen=seen)
    res = client.post(f"/api/snippets/{snippet}/tags", params={"tag_id": str(LOVE.id)})
    assert res.status_code == 200 and res.json() == {"ok": True}
    assert client.delete(f"/api/snippets/{snippet}/tags/{LOVE.id}").status_code == 204
    assert seen == [(snippet, LOVE.id, USER_ID), (snippet, LOVE.id, USER_ID)]


@pytest.mark.parametrize(("error", "code"), [(SnippetNotFoundError("snippet not found"), "snippet_not_found"), (TagNotFoundError("tag not found"), "tag_not_found")])
def test_link_and_unlink_errors(client, monkeypatch, error, code):
    fake(monkeypatch, tag_service, "add_tag_to_snippet", error=error)
    fake(monkeypatch, tag_service, "remove_tag_from_snippet", error=error)
    assert_error(client.post(f"/api/snippets/{uuid.uuid4()}/tags", params={"tag_id": str(uuid.uuid4())}), 404, code)
    assert_error(client.delete(f"/api/snippets/{uuid.uuid4()}/tags/{uuid.uuid4()}"), 404, code)


def test_canvas_scoped_tag_creation_is_gone(client):
    assert client.post(f"/api/canvases/{uuid.uuid4()}/tags", json={"name": "x"}).status_code in (404, 405)


# ---- presets ----


def preset_json(p) -> dict:
    return {"id": str(p.id), "name": p.name, "expression": p.expression, "created_at": str(p.created_at), "updated_at": str(p.updated_at)}


def test_list_presets(client, monkeypatch):
    rows = [preset("Dark"), preset("hooks", "type:hook")]
    fake(monkeypatch, tag_preset_service, "list_presets", rows)
    res = client.get("/api/tag-presets")
    assert res.status_code == 200
    assert res.json() == [preset_json(p) for p in rows]
    assert list(res.json()[0]) == ["id", "name", "expression", "created_at", "updated_at"]


def test_create_preset_normalizes_the_expression(client, monkeypatch):
    seen: list = []
    row = preset()
    fake(monkeypatch, tag_preset_service, "create_preset", row, seen=seen)
    res = client.post("/api/tag-presets", json={"name": " Dark ", "expression": "night and not love"})
    assert res.status_code == 201
    assert res.json() == preset_json(row)
    assert seen == [(USER_ID, "Dark", "night & !love")]


def test_invalid_expression_has_the_expression_error(client, monkeypatch):
    fake(monkeypatch, tag_preset_service, "create_preset", preset())
    detail = assert_error(client.post("/api/tag-presets", json={"name": "x", "expression": "love & ("}), 422, "invalid_tag_expression")
    assert detail["expression_error"]["code"] == "unexpected_end"
    assert detail["expression_error"]["position"] == 8
    assert detail["expression_error"]["message"]


@pytest.mark.parametrize(
    ("body", "code"),
    [({"name": "x", "expression": "  "}, "empty_preset"), ({"name": " ", "expression": "a"}, "invalid_preset_name")],
)
def test_create_preset_validation(client, monkeypatch, body, code):
    fake(monkeypatch, tag_preset_service, "create_preset", preset())
    detail = assert_error(client.post("/api/tag-presets", json=body), 422, code)
    assert "expression_error" not in detail


def test_create_preset_conflict(client, monkeypatch):
    fake(monkeypatch, tag_preset_service, "create_preset", error=PresetNameTakenError("taken"))
    assert_error(client.post("/api/tag-presets", json={"name": "dark", "expression": "a"}), 409, "preset_name_taken")


def test_update_preset(client, monkeypatch):
    seen: list = []
    row = preset()
    fake(monkeypatch, tag_preset_service, "update_preset", row, seen=seen)
    res = client.put(f"/api/tag-presets/{row.id}", json={"expression": "(a)"})
    assert res.status_code == 200
    assert seen == [(row.id, USER_ID, None, "a")]
    assert_error(client.put(f"/api/tag-presets/{row.id}", json={"expression": ""}), 422, "empty_preset")
    assert_error(client.put(f"/api/tag-presets/{row.id}", json={"expression": ")"}), 422, "invalid_tag_expression")


@pytest.mark.parametrize(("error", "status", "code"), [(PresetNotFoundError("nope"), 404, "preset_not_found"), (PresetNameTakenError("taken"), 409, "preset_name_taken")])
def test_update_preset_errors(client, monkeypatch, error, status, code):
    fake(monkeypatch, tag_preset_service, "update_preset", error=error)
    assert_error(client.put(f"/api/tag-presets/{uuid.uuid4()}", json={"name": "n"}), status, code)


def test_delete_preset(client, monkeypatch):
    fake(monkeypatch, tag_preset_service, "delete_preset", None)
    assert client.delete(f"/api/tag-presets/{uuid.uuid4()}").status_code == 204
    fake(monkeypatch, tag_preset_service, "delete_preset", error=PresetNotFoundError("nope"))
    assert_error(client.delete(f"/api/tag-presets/{uuid.uuid4()}"), 404, "preset_not_found")
