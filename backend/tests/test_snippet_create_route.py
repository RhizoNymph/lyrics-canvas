"""POST /api/canvases/{id}/snippets with and without a client-supplied beat grid."""

import uuid
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.api.snippets import _compute_size
from app.db.database import get_db
from app.main import app
from app.services import snippet_service

USER_ID = "u1"


@pytest.fixture
def client(monkeypatch):
    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER_ID)
    yield TestClient(app)
    app.dependency_overrides.clear()


@pytest.fixture
def captured(monkeypatch) -> dict:
    """Replace the service with a fake that records the kwargs the route passes."""
    seen: dict = {}

    async def fake_create(db, canvas_id, owner_id, **kwargs):
        seen.update(kwargs)
        return SimpleNamespace(
            id=uuid.uuid4(),
            canvas_id=uuid.UUID(canvas_id),
            snippet_type=kwargs["snippet_type"],
            grid_mode=kwargs["grid_mode"],
            beats_per_line=kwargs.get("beats_per_line"),
            grid_data=kwargs["grid_data"],
            raw_text=kwargs["raw_text"],
            position_x=kwargs["position_x"],
            position_y=kwargs["position_y"],
            width=kwargs["width"],
            height=kwargs["height"],
            z_index=0,
            group_id=kwargs.get("group_id"),
            tags=[],
            phonetics=kwargs["phonetics"],
            derived_from=kwargs.get("derived_from"),
            created_at="now",
            updated_at="now",
            content_updated_at="now",
        )

    monkeypatch.setattr(snippet_service, "create_snippet", fake_create)
    return seen


def cell(syllable: str, stress: int, word_index: int) -> dict:
    return {"syllable": syllable, "stress": stress, "word_index": word_index}


REST = {"rest": True}

# "cat" / "dog" are one syllable each; the grid marks "dog" unstressed and adds rests.
GRID = {
    "lines": [
        {"cells": [cell("cat", 1, 0), REST, REST, REST], "syllable_count": 1, "raw_text": "cat"},
        {"cells": [cell("dog", 0, 0), REST, cell("bat", 1, 1), REST], "syllable_count": 2, "raw_text": "dog bat"},
    ]
}


def test_create_with_grid_data_keeps_cells_and_rests(client: TestClient, captured: dict):
    res = client.post(
        f"/api/canvases/{uuid.uuid4()}/snippets",
        json={"raw_text": "cat\ndog bat", "grid_data": GRID, "grid_mode": "fixed", "beats_per_line": 4},
    )
    assert res.status_code == 201, res.text
    assert captured["grid_data"] == GRID
    assert captured["beats_per_line"] == 4
    assert res.json()["grid_data"] == GRID


def test_create_with_grid_data_computes_phonetics_from_that_grid(client: TestClient, captured: dict):
    res = client.post(
        f"/api/canvases/{uuid.uuid4()}/snippets",
        json={"raw_text": "cat\ndog bat", "grid_data": GRID},
    )
    assert res.status_code == 201, res.text
    lines = captured["phonetics"]["lines"]
    assert [line["line_index"] for line in lines] == [0, 1]
    assert lines[0]["rhyme_key"] == ["AE", "T"]
    # the grid's stress wins: "dog" is 0 in the grid though the engine says 1
    assert lines[1]["stress"] == "01"
    assert "1:0" in captured["phonetics"]["overrides"]


def test_create_with_grid_data_sizes_from_the_grid(client: TestClient, captured: dict):
    res = client.post(
        f"/api/canvases/{uuid.uuid4()}/snippets",
        json={"raw_text": "cat\ndog bat", "grid_data": GRID},
    )
    assert res.status_code == 201, res.text
    width, height = _compute_size(GRID)
    assert (captured["width"], captured["height"]) == (width, height)
    # 4 cells wide, not the 1 and 2 syllabification would produce
    assert width > _compute_size({"lines": [{"cells": [cell("cat", 1, 0)]}]})[0]


def test_create_with_grid_data_keeps_extra_keys(client: TestClient, captured: dict):
    grid = {"lines": [{"cells": [{**cell("cat", 1, 0), "note": "x"}], "syllable_count": 1, "raw_text": "cat", "tag": "y"}]}
    res = client.post(f"/api/canvases/{uuid.uuid4()}/snippets", json={"raw_text": "cat", "grid_data": grid})
    assert res.status_code == 201, res.text
    assert captured["grid_data"]["lines"][0]["cells"][0]["note"] == "x"
    assert captured["grid_data"]["lines"][0]["tag"] == "y"


def test_create_with_group_id(client: TestClient, captured: dict):
    group_id = uuid.uuid4()
    res = client.post(f"/api/canvases/{uuid.uuid4()}/snippets", json={"raw_text": "cat", "group_id": str(group_id)})
    assert res.status_code == 201, res.text
    assert captured["group_id"] == group_id
    assert res.json()["group_id"] == str(group_id)


@pytest.mark.parametrize(
    "grid",
    [
        {},
        {"lines": "nope"},
        {"lines": [{"syllable_count": 1}]},
        {"lines": [{"cells": "cat"}]},
        {"lines": [{"cells": [{"stress": "loud"}]}]},
    ],
)
def test_create_rejects_malformed_grid_data(client: TestClient, captured: dict, grid):
    res = client.post(f"/api/canvases/{uuid.uuid4()}/snippets", json={"raw_text": "cat", "grid_data": grid})
    assert res.status_code == 422, res.text
    assert captured == {}


def test_create_without_grid_data_syllabifies(client: TestClient, captured: dict):
    res = client.post(f"/api/canvases/{uuid.uuid4()}/snippets", json={"raw_text": "cat\ndog bat"})
    assert res.status_code == 201, res.text
    lines = captured["grid_data"]["lines"]
    assert [line["raw_text"] for line in lines] == ["cat", "dog bat"]
    assert [len(line["cells"]) for line in lines] == [1, 2]
    assert captured["phonetics"]["lines"][1]["stress"] == "11"
    assert captured["phonetics"]["overrides"] == {}
    assert captured["group_id"] is None
    assert (captured["width"], captured["height"]) == _compute_size(captured["grid_data"])
