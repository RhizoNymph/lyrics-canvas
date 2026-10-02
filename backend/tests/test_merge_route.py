"""POST /api/canvases/{canvas_id}/merge with the snippet service faked."""

import uuid
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.api.snippets import _compute_size
from app.db.database import get_db
from app.main import app
from app.models.snippet import GridMode, SnippetType
from app.services import snippet_service
from app.services.merge_service import MergePersistError
from tests.route_fakes import fake_snippet, fake_tag
from tests.merge_fixtures import line

USER_ID = uuid.uuid4()
CANVAS = uuid.uuid4()
REST = {"rest": True}


@pytest.fixture
def client():
    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER_ID)
    yield TestClient(app)
    app.dependency_overrides.clear()


class FakeStore:
    """Records calls; ``available`` are the snippets the caller owns in CANVAS."""

    def __init__(self, available: list):
        self.available = {str(s.id): s for s in available}
        self.get_calls: list = []
        self.replace_calls: list = []
        self.fail_with: Exception | None = None

    async def get_canvas_snippets(self, db, canvas_id, owner_id, snippet_ids):
        self.get_calls.append((canvas_id, owner_id, list(snippet_ids)))
        if canvas_id != CANVAS:
            return []
        return [self.available[str(i)] for i in snippet_ids if str(i) in self.available]

    async def replace_with_merged(self, db, canvas_id, fields, tags, originals):
        self.replace_calls.append(SimpleNamespace(canvas_id=canvas_id, fields=dict(fields), tags=list(tags), originals=list(originals)))
        if self.fail_with:
            raise self.fail_with
        return fake_snippet(canvas_id, fields["raw_text"], **fields, tags=list(tags))


@pytest.fixture
def make_store(monkeypatch):
    def make(*snippets) -> FakeStore:
        store = FakeStore(list(snippets))
        monkeypatch.setattr(snippet_service, "get_canvas_snippets", store.get_canvas_snippets)
        monkeypatch.setattr(snippet_service, "replace_with_merged", store.replace_with_merged)
        return store

    return make


def post(client, ids, mode="vertical", canvas=CANVAS):
    return client.post(f"/api/canvases/{canvas}/merge", json={"snippet_ids": [str(i) for i in ids], "mode": mode})


T1 = fake_tag("t1", CANVAS)
T2 = fake_tag("t2", CANVAS)
GROUP = uuid.uuid4()


def test_vertical_merge_orders_by_position_and_replaces_in_one_call(client, make_store):
    top = fake_snippet(CANVAS, "one two", position_x=100.0, position_y=100.0, snippet_type=SnippetType.chorus, tags=[T1], group_id=GROUP)
    bottom = fake_snippet(CANVAS, "three", position_x=50.0, position_y=300.0, tags=[T1, T2])
    store = make_store(top, bottom)
    res = post(client, [bottom.id, top.id])
    assert res.status_code == 200, res.text

    assert len(store.replace_calls) == 1
    call = store.replace_calls[0]
    assert call.canvas_id == CANVAS
    assert call.fields["raw_text"] == "one two\nthree"
    assert call.fields["snippet_type"] == SnippetType.chorus
    assert call.fields["grid_mode"] == GridMode.auto
    assert call.fields["beats_per_line"] is None
    assert (call.fields["position_x"], call.fields["position_y"]) == (100.0, 100.0)
    assert call.fields["group_id"] == GROUP
    assert [ln["raw_text"] for ln in call.fields["grid_data"]["lines"]] == ["one two", "three"]
    assert (call.fields["width"], call.fields["height"]) == _compute_size(call.fields["grid_data"])
    assert [t.id for t in call.tags] == [T1.id, T2.id]
    assert [o.id for o in call.originals] == [top.id, bottom.id]

    body = res.json()
    assert body["deleted_ids"] == [str(top.id), str(bottom.id)]
    assert body["snippet"]["raw_text"] == "one two\nthree"
    assert body["snippet"]["group_id"] == str(GROUP)
    assert [t["name"] for t in body["snippet"]["tags"]] == ["t1", "t2"]


def test_horizontal_merge_rebases_word_index(client, make_store):
    left = fake_snippet(CANVAS, "one two", position_x=0.0, position_y=500.0)
    right = fake_snippet(CANVAS, "three four", position_x=300.0, position_y=0.0)
    store = make_store(left, right)
    res = post(client, [right.id, left.id], mode="horizontal")
    assert res.status_code == 200, res.text
    grid = res.json()["snippet"]["grid_data"]
    assert grid["lines"][0]["raw_text"] == "one two three four"
    assert [c["word_index"] for c in grid["lines"][0]["cells"]] == [0, 1, 2, 3]
    assert (store.replace_calls[0].fields["position_x"], store.replace_calls[0].fields["position_y"]) == (0.0, 500.0)
    assert res.json()["deleted_ids"] == [str(left.id), str(right.id)]


def test_fixed_mode_pads_with_rests_and_raises_beats(client, make_store):
    a = fake_snippet(CANVAS, "one", grid_mode=GridMode.fixed, beats_per_line=2, grid_data={"lines": [line("one")]})
    b = fake_snippet(CANVAS, "two three four", position_y=50.0, grid_data={"lines": [line("two three four")]})
    store = make_store(a, b)
    res = post(client, [a.id, b.id])
    assert res.status_code == 200, res.text
    fields = store.replace_calls[0].fields
    assert fields["grid_mode"] == GridMode.fixed
    assert fields["beats_per_line"] == 3
    assert fields["grid_data"]["lines"][0]["cells"] == [{"syllable": "one", "stress": 1, "word_index": 0}, REST, REST]
    assert res.json()["snippet"]["grid_data"] == fields["grid_data"]


def test_null_grid_contributes_text_lines(client, make_store):
    a = fake_snippet(CANVAS, "cat")
    b = fake_snippet(CANVAS, "dog bat", position_y=10.0, grid_data=None)
    store = make_store(a, b)
    res = post(client, [a.id, b.id])
    assert res.status_code == 200, res.text
    assert store.replace_calls[0].fields["grid_data"]["lines"][1] == {"cells": [], "syllable_count": 0, "raw_text": "dog bat"}


def test_phonetics_follow_the_merged_grid_with_grid_stress_winning(client, make_store):
    a = fake_snippet(CANVAS, "cat", grid_data={"lines": [{"cells": [{"syllable": "cat", "stress": 1, "word_index": 0}, REST], "syllable_count": 1, "raw_text": "cat"}]})
    b = fake_snippet(CANVAS, "dog bat", position_y=10.0, grid_data={"lines": [{"cells": [{"syllable": "dog", "stress": 0, "word_index": 0}, {"syllable": "bat", "stress": 1, "word_index": 1}], "syllable_count": 2, "raw_text": "dog bat"}]})
    store = make_store(a, b)
    res = post(client, [a.id, b.id])
    assert res.status_code == 200, res.text
    phonetics = store.replace_calls[0].fields["phonetics"]
    assert [ln["line_index"] for ln in phonetics["lines"]] == [0, 1]
    assert phonetics["lines"][0]["rhyme_key"] == ["AE", "T"]
    assert phonetics["lines"][1]["stress"] == "01"
    assert "1:0" in phonetics["overrides"]
    assert res.json()["snippet"]["phonetics"] == phonetics


def test_duplicate_ids_count_once(client, make_store):
    a = fake_snippet(CANVAS, "one")
    b = fake_snippet(CANVAS, "two", position_y=10.0)
    store = make_store(a, b)
    res = post(client, [a.id, b.id, a.id])
    assert res.status_code == 200, res.text
    assert store.get_calls[0][2] == [a.id, b.id]
    assert res.json()["deleted_ids"] == [str(a.id), str(b.id)]


def test_unknown_or_foreign_id_is_404_and_nothing_changes(client, make_store):
    a = fake_snippet(CANVAS, "one")
    store = make_store(a)
    res = post(client, [a.id, uuid.uuid4()])
    assert res.status_code == 404
    assert res.json() == {"detail": "Snippet not found"}
    assert store.replace_calls == []


def test_other_canvas_is_404(client, make_store):
    a = fake_snippet(CANVAS, "one")
    b = fake_snippet(CANVAS, "two")
    store = make_store(a, b)
    res = post(client, [a.id, b.id], canvas=uuid.uuid4())
    assert res.status_code == 404
    assert store.replace_calls == []


@pytest.mark.parametrize(
    "body",
    [
        {"snippet_ids": [], "mode": "vertical"},
        {"snippet_ids": ["00000000-0000-0000-0000-000000000001"], "mode": "vertical"},
        {"snippet_ids": ["00000000-0000-0000-0000-000000000001"] * 2, "mode": "vertical"},
        {"snippet_ids": ["00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"], "mode": "diagonal"},
        {"snippet_ids": ["not-a-uuid", "00000000-0000-0000-0000-000000000002"], "mode": "vertical"},
        {"snippet_ids": ["00000000-0000-0000-0000-000000000001", "00000000-0000-0000-0000-000000000002"]},
    ],
)
def test_invalid_requests_are_422(client, make_store, body):
    store = make_store()
    res = client.post(f"/api/canvases/{CANVAS}/merge", json=body)
    assert res.status_code == 422, res.text
    assert store.get_calls == []


def test_storage_failure_is_500_with_nothing_changed(client, make_store):
    a = fake_snippet(CANVAS, "one")
    b = fake_snippet(CANVAS, "two", position_y=10.0)
    store = make_store(a, b)
    store.fail_with = MergePersistError("could not store the merge; nothing changed")
    res = post(client, [a.id, b.id])
    assert res.status_code == 500
    assert res.json() == {"detail": "could not store the merge; nothing changed"}


def test_response_snippet_has_the_snippet_wire_shape(client, make_store):
    a = fake_snippet(CANVAS, "one")
    b = fake_snippet(CANVAS, "two", position_y=10.0)
    make_store(a, b)
    body = post(client, [a.id, b.id]).json()
    assert list(body) == ["snippet", "deleted_ids"]
    assert list(body["snippet"]) == [
        "id", "canvas_id", "snippet_type", "grid_mode", "beats_per_line", "grid_data", "raw_text",
        "position_x", "position_y", "width", "height", "z_index", "group_id", "tags", "phonetics",
        "derived_from", "created_at", "updated_at",
    ]
