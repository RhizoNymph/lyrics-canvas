"""Typed response models keep the wire shape the old dict-returning routes produced.

``legacy_*`` are verbatim copies of the pre-typing dict builders from app/api/canvases.py;
every route's JSON is compared with what those builders produced for the same objects.
"""

import json
import uuid
from types import SimpleNamespace

import pytest
from fastapi.encoders import jsonable_encoder
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.services import canvas_service, group_service, snippet_service, tag_service
from app.services.nlp.syllabifier import syllabify_text
from tests.route_fakes import fake_canvas, fake_group, fake_snippet, fake_tag

USER_ID = uuid.uuid4()
CANVAS_ID = uuid.uuid4()


# ---- the pre-typing builders, verbatim ----


def legacy_canvas(canvas) -> dict:
    return {
        "id": str(canvas.id),
        "owner_id": str(canvas.owner_id),
        "title": canvas.title,
        "is_public": canvas.is_public,
        "share_token": canvas.share_token,
        "viewport_state": canvas.viewport_state,
        "created_at": str(canvas.created_at),
        "updated_at": str(canvas.updated_at),
    }


def legacy_snippet(s) -> dict:
    return {
        "id": str(s.id),
        "canvas_id": str(s.canvas_id),
        "snippet_type": s.snippet_type.value,
        "grid_mode": s.grid_mode.value,
        "beats_per_line": s.beats_per_line,
        "grid_data": s.grid_data,
        "raw_text": s.raw_text,
        "position_x": s.position_x,
        "position_y": s.position_y,
        "width": s.width,
        "height": s.height,
        "z_index": s.z_index,
        "group_id": str(s.group_id) if s.group_id else None,
        "tags": [{"id": str(t.id), "name": t.name, "color": t.color, "canvas_id": str(t.canvas_id)} for t in (s.tags or [])],
        "phonetics": s.phonetics,
        "derived_from": s.derived_from,
        "created_at": str(s.created_at),
        "updated_at": str(s.updated_at),
        "content_updated_at": str(s.content_updated_at),
    }


def legacy_group(g) -> dict:
    return {
        "id": str(g.id),
        "canvas_id": str(g.canvas_id),
        "name": g.name,
        "position_x": g.position_x,
        "position_y": g.position_y,
        "snippet_ids": [str(s.id) for s in (g.snippets or [])],
        "created_at": str(g.created_at),
    }


def legacy_tag(t) -> dict:
    return {"id": str(t.id), "canvas_id": str(t.canvas_id), "name": t.name, "color": t.color}


def legacy_detail(canvas) -> dict:
    return {
        **legacy_canvas(canvas),
        "snippets": [legacy_snippet(s) for s in canvas.snippets],
        "groups": [legacy_group(g) for g in canvas.groups],
        "tags": [legacy_tag(t) for t in canvas.tags],
    }


def as_wire(value) -> object:
    """What FastAPI sent for a plain dict return: jsonable_encoder, then JSON."""
    return json.loads(json.dumps(jsonable_encoder(value)))


def assert_same_wire(res, legacy) -> None:
    expected = as_wire(legacy)
    actual = res.json()
    assert actual == expected
    if isinstance(expected, dict):
        assert list(actual) == list(expected)


# ---- fixtures ----


TAG_A = fake_tag("hook", CANVAS_ID, "#ff0000")
TAG_B = fake_tag("draft", CANVAS_ID)
RICH_GRID = {
    "lines": [
        {
            "cells": [
                {"syllable": "hel", "stress": 1, "word_index": 0, "word": "Hello,"},
                {"syllable": "lo", "stress": 0, "word_index": 0, "word": "Hello,"},
                {"rest": True},
                {"syllable": "world", "stress": 1, "word_index": 1},
            ],
            "syllable_count": 3,
            "raw_text": "Hello, world",
            "note": "kept",
        },
        {"cells": [{"syllable": "cat", "stress": 2, "word_index": 0}], "syllable_count": 1, "raw_text": "cat"},
    ]
}
DERIVED = {
    "provenance": "dictionary",
    "source_unit_id": "dict:cat",
    "score": 0.875,
    "match_depth": 1,
    "k": 2,
    "anchor_unit_id": "s:1:0",
    "anchor_text": "hat",
}


def rich_snippet():
    return fake_snippet(
        CANVAS_ID,
        "Hello, world\ncat",
        grid_data=RICH_GRID,
        position_x=12.5,
        position_y=-40.0,
        beats_per_line=4,
        z_index=3,
        group_id=uuid.uuid4(),
        tags=[TAG_A, TAG_B],
        derived_from=DERIVED,
    )


def plain_snippets():
    return [rich_snippet(), fake_snippet(CANVAS_ID, "walking the dog"), fake_snippet(CANVAS_ID, "", grid_data={"lines": []}, phonetics=None)]


def detail_canvas(**overrides):
    snippets = plain_snippets()
    groups = [fake_group(CANVAS_ID, "Verse 1", snippets[:2]), fake_group(CANVAS_ID, "Empty", [])]
    return fake_canvas(USER_ID, snippets, groups, [TAG_A, TAG_B], id=CANVAS_ID, **overrides)


@pytest.fixture
def client():
    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER_ID)
    yield TestClient(app)
    app.dependency_overrides.clear()


# ---- canvases ----


def test_get_canvas(client, monkeypatch):
    canvas = detail_canvas()

    async def fake_get(db, canvas_id, owner_id=None):
        return canvas

    monkeypatch.setattr(canvas_service, "get_canvas", fake_get)
    res = client.get(f"/api/canvases/{CANVAS_ID}")
    assert res.status_code == 200, res.text
    assert_same_wire(res, legacy_detail(canvas))
    # grids come back exactly as stored: no null-filled cell fields, extras kept
    assert res.json()["snippets"][0]["grid_data"] == RICH_GRID


def test_get_shared_canvas(client, monkeypatch):
    canvas = detail_canvas(is_public=True, share_token="tok", viewport_state=None)

    async def fake_shared(db, token):
        return canvas

    monkeypatch.setattr(canvas_service, "get_canvas_by_share_token", fake_shared)
    res = client.get("/api/canvases/shared/tok")
    assert res.status_code == 200, res.text
    assert_same_wire(res, legacy_detail(canvas))


def test_share_canvas(client, monkeypatch):
    async def fake_share(db, canvas_id, owner_id):
        return "abc"

    monkeypatch.setattr(canvas_service, "generate_share_token", fake_share)
    res = client.post(f"/api/canvases/{CANVAS_ID}/share")
    assert_same_wire(res, {"share_token": "abc"})


# ---- snippets ----


def test_create_snippet(client, monkeypatch):
    made = {}

    async def fake_create(db, canvas_id, owner_id, **kwargs):
        made["s"] = fake_snippet(uuid.UUID(canvas_id), kwargs.pop("raw_text"), **kwargs)
        return made["s"]

    monkeypatch.setattr(snippet_service, "create_snippet", fake_create)
    res = client.post(f"/api/canvases/{CANVAS_ID}/snippets", json={"raw_text": "Hello, world\ncat", "grid_data": RICH_GRID, "derived_from": DERIVED})
    assert res.status_code == 201, res.text
    assert_same_wire(res, legacy_snippet(made["s"]))


def test_update_snippet(client, monkeypatch):
    snippet = rich_snippet()

    async def fake_update(db, snippet_id, owner_id, **kwargs):
        return snippet

    monkeypatch.setattr(snippet_service, "update_snippet", fake_update)
    res = client.put(f"/api/snippets/{snippet.id}", json={"position_x": 1})
    assert res.status_code == 200, res.text
    assert_same_wire(res, legacy_snippet(snippet))


def test_update_snippet_stores_a_typed_grid_verbatim(client, monkeypatch):
    seen = {}

    async def fake_owned(db, snippet_id, owner_id):
        return rich_snippet()

    async def fake_update(db, snippet_id, owner_id, **kwargs):
        seen.update(kwargs)
        return rich_snippet()

    monkeypatch.setattr(snippet_service, "get_owned_snippet", fake_owned)
    monkeypatch.setattr(snippet_service, "update_snippet", fake_update)
    res = client.put(f"/api/snippets/{uuid.uuid4()}", json={"grid_data": RICH_GRID})
    assert res.status_code == 200, res.text
    assert seen["grid_data"] == RICH_GRID


def test_update_snippet_rejects_a_malformed_grid(client):
    res = client.put(f"/api/snippets/{uuid.uuid4()}", json={"grid_data": {"lines": [{"cells": "x"}]}})
    assert res.status_code == 422


def test_batch_position(client, monkeypatch):
    async def fake_batch(db, updates, owner_id):
        return 2

    monkeypatch.setattr(snippet_service, "batch_update_positions", fake_batch)
    res = client.patch("/api/snippets/batch-position", json={"updates": [{"id": str(uuid.uuid4()), "position_x": 1, "position_y": 2}]})
    assert_same_wire(res, {"updated": 2})


# ---- groups ----


def test_create_and_update_group(client, monkeypatch):
    group = fake_group(CANVAS_ID, "Chorus", plain_snippets())

    async def fake_create(db, canvas_id, owner_id, **kwargs):
        return group

    async def fake_update(db, group_id, owner_id, **kwargs):
        return group

    monkeypatch.setattr(group_service, "create_group", fake_create)
    monkeypatch.setattr(group_service, "update_group", fake_update)
    res = client.post(f"/api/canvases/{CANVAS_ID}/groups", json={"name": "Chorus"})
    assert res.status_code == 201
    assert_same_wire(res, legacy_group(group))
    assert_same_wire(client.put(f"/api/groups/{group.id}", json={"name": "x"}), legacy_group(group))


# ---- tags ----


def test_create_update_and_link_tag(client, monkeypatch):
    async def fake_create(db, canvas_id, owner_id, name, color):
        return TAG_A

    async def fake_update(db, tag_id, owner_id, name, color):
        return TAG_B

    async def fake_link(db, snippet_id, tag_id, owner_id):
        return True

    monkeypatch.setattr(tag_service, "create_tag", fake_create)
    monkeypatch.setattr(tag_service, "update_tag", fake_update)
    monkeypatch.setattr(tag_service, "add_tag_to_snippet", fake_link)
    res = client.post(f"/api/canvases/{CANVAS_ID}/tags", json={"name": "hook"})
    assert res.status_code == 201
    assert_same_wire(res, legacy_tag(TAG_A))
    assert_same_wire(client.put(f"/api/tags/{TAG_B.id}", json={"name": "draft"}), legacy_tag(TAG_B))
    assert_same_wire(client.post(f"/api/snippets/{uuid.uuid4()}/tags", params={"tag_id": str(TAG_A.id)}), {"ok": True})


# ---- nlp ----


@pytest.mark.parametrize("text", ["hello world\nthe cat sat", "", "1999 !!\n\n  spaced   out  "])
def test_syllabify(client, text):
    res = client.post("/api/nlp/syllabify", json={"text": text})
    assert res.status_code == 200
    assert_same_wire(res, syllabify_text(text))
