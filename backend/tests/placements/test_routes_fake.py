"""Placement routes with the services faked: request validation and typed error bodies (no DB)."""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api import placements as placement_routes
from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.services import canvas_service, snippet_service
from app.services.placements.errors import CanvasNotFoundError, PlacementNotFoundError, SameCanvasError
from app.services.placements.transfer import TransferMode, TransferResult
from tests.route_fakes import fake_snippet

USER = uuid.uuid4()
CANVAS = uuid.uuid4()
TARGET = uuid.uuid4()


@pytest.fixture
def client():
    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER)
    yield TestClient(app)
    app.dependency_overrides.clear()


def assert_error(res, status: int, code: str) -> None:
    assert res.status_code == status, res.text
    assert res.json()["detail"]["error"] == code


def test_put_and_batch_position_require_canvas_id(client, monkeypatch):
    async def must_not_run(*args, **kwargs):
        raise AssertionError("service called without canvas_id")

    monkeypatch.setattr(snippet_service, "update_snippet", must_not_run)
    monkeypatch.setattr(snippet_service, "batch_update_positions", must_not_run)
    assert client.put(f"/api/snippets/{uuid.uuid4()}", json={"position_x": 1}).status_code == 422
    assert client.patch("/api/snippets/batch-position", json={"updates": [{"id": str(uuid.uuid4()), "position_x": 1}]}).status_code == 422


def test_put_passes_canvas_id_and_maps_placement_not_found(client, monkeypatch):
    seen = {}

    async def fake_update(db, snippet_id, owner_id, *, canvas_id, content_edits, **changes):
        seen["canvas_id"] = canvas_id
        raise PlacementNotFoundError("not here")

    monkeypatch.setattr(snippet_service, "update_snippet", fake_update)
    assert_error(client.put(f"/api/snippets/{uuid.uuid4()}", params={"canvas_id": str(CANVAS)}, json={"z_index": 1}), 404, "placement_not_found")
    assert seen["canvas_id"] == CANVAS


def test_put_with_a_malformed_group_id_is_group_not_found(client, monkeypatch):
    async def fake_update(*args, **kwargs):
        raise AssertionError("not reached")

    monkeypatch.setattr(snippet_service, "update_snippet", fake_update)
    assert_error(client.put(f"/api/snippets/{uuid.uuid4()}", params={"canvas_id": str(CANVAS)}, json={"group_id": "nope"}), 404, "group_not_found")


@pytest.mark.parametrize("title", ["", "  ", "x" * 256])
def test_rename_rejects_bad_titles_before_the_service(client, monkeypatch, title):
    async def must_not_run(*args, **kwargs):
        raise AssertionError("service called with an invalid title")

    monkeypatch.setattr(canvas_service, "update_canvas", must_not_run)
    assert_error(client.put(f"/api/canvases/{CANVAS}", json={"title": title}), 422, "invalid_canvas_title")


def test_create_and_rename_pass_the_stripped_title(client, monkeypatch):
    seen = []
    canvas = SimpleNamespace(id=CANVAS, owner_id=USER, title="t", is_public=False, share_token=None, viewport_state=None,
                             created_at="c", updated_at="u", version=1)

    async def fake_create(db, owner_id, title):
        seen.append(title)
        return canvas

    async def fake_update(db, canvas_id, owner_id, **changes):
        seen.append(changes)
        return canvas

    monkeypatch.setattr(canvas_service, "create_canvas", fake_create)
    monkeypatch.setattr(canvas_service, "update_canvas", fake_update)
    assert client.post("/api/canvases", json={"title": "  A  "}).status_code == 201
    assert client.post("/api/canvases", json={"title": ""}).status_code == 201
    assert_error(client.post("/api/canvases", json={"title": "z" * 256}), 422, "invalid_canvas_title")
    assert client.put(f"/api/canvases/{CANVAS}", json={"title": " B ", "viewport_state": {"x": 1}}).status_code == 200
    assert client.put(f"/api/canvases/{CANVAS}", json={"viewport_state": {"x": 2}}).status_code == 200
    assert seen == ["A", "Untitled Canvas", {"title": "B", "viewport_state": {"x": 1}}, {"viewport_state": {"x": 2}}]


def test_remove_from_canvas_route(client, monkeypatch):
    calls = []

    async def fake_remove(db, canvas_id, snippet_id, owner_id):
        calls.append((canvas_id, snippet_id, owner_id))
        if len(calls) > 1:
            raise PlacementNotFoundError("gone")
        return True

    monkeypatch.setattr(placement_routes, "remove_from_canvas", fake_remove)
    sid = uuid.uuid4()
    res = client.delete(f"/api/canvases/{CANVAS}/snippets/{sid}")
    assert res.status_code == 200 and res.json() == {"snippet_deleted": True}
    assert calls == [(CANVAS, sid, USER)]
    assert_error(client.delete(f"/api/canvases/{CANVAS}/snippets/{sid}"), 404, "placement_not_found")


@pytest.mark.parametrize(
    "error, status, code",
    [(SameCanvasError("s"), 422, "same_canvas"), (CanvasNotFoundError("c"), 404, "canvas_not_found"), (PlacementNotFoundError("p"), 404, "placement_not_found")],
)
def test_transfer_maps_errors(client, monkeypatch, error, status, code):
    async def fake_transfer(*args):
        raise error

    monkeypatch.setattr(placement_routes, "transfer", fake_transfer)
    body = {"snippet_ids": [str(uuid.uuid4())], "target_canvas_id": str(TARGET), "mode": "move"}
    assert_error(client.post(f"/api/canvases/{CANVAS}/snippets/transfer", json=body), status, code)


def test_transfer_response_and_request_validation(client, monkeypatch):
    a, b = uuid.uuid4(), uuid.uuid4()
    seen = {}

    async def fake_transfer(db, source, target, ids, mode, owner):
        seen.update(source=source, target=target, ids=ids, mode=mode, owner=owner)
        return TransferResult(target_canvas_id=target, placed=(a,), already_on_target=(b,), removed_from_source=False)

    monkeypatch.setattr(placement_routes, "transfer", fake_transfer)
    url = f"/api/canvases/{CANVAS}/snippets/transfer"
    res = client.post(url, json={"snippet_ids": [str(a), str(b)], "target_canvas_id": str(TARGET), "mode": "show"})
    assert res.status_code == 200
    assert res.json() == {"target_canvas_id": str(TARGET), "placed": [str(a)], "already_on_target": [str(b)], "removed_from_source": False}
    assert seen == {"source": CANVAS, "target": TARGET, "ids": [a, b], "mode": TransferMode.show, "owner": USER}
    for bad in ([], [str(uuid.uuid4()) for _ in range(501)]):
        assert client.post(url, json={"snippet_ids": bad, "target_canvas_id": str(TARGET), "mode": "show"}).status_code == 422
    assert client.post(url, json={"snippet_ids": [str(a)], "target_canvas_id": str(TARGET), "mode": "copy"}).status_code == 422
    ok = [str(uuid.uuid4()) for _ in range(500)]
    assert client.post(url, json={"snippet_ids": ok, "target_canvas_id": str(TARGET), "mode": "show"}).status_code == 200


def test_snippet_response_has_placement_and_canvases(client, monkeypatch):
    s = fake_snippet(CANVAS, "cat", canvases=(SimpleNamespace(id=TARGET, title="b"), SimpleNamespace(id=CANVAS, title="Song")))

    async def fake_update(db, snippet_id, owner_id, *, canvas_id, content_edits, **changes):
        return s

    monkeypatch.setattr(snippet_service, "update_snippet", fake_update)
    body = client.put(f"/api/snippets/{s.id}", params={"canvas_id": str(CANVAS)}, json={"z_index": 2}).json()
    assert body["placement_id"] == str(s.placement_id)
    assert body["canvas_id"] == str(CANVAS)
    assert body["canvases"] == [{"id": str(TARGET), "title": "b"}, {"id": str(CANVAS), "title": "Song"}]
