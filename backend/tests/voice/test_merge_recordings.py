"""Merging snippets moves their recordings onto the merged snippet inside the merge transaction."""

import uuid
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.dialects import postgresql
from sqlalchemy.exc import OperationalError

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.models.snippet import Snippet
from app.services import recording_service, snippet_service
from app.services.merge_service import MergePersistError
from tests.route_fakes import fake_snippet
from tests.test_snippet_service_merge import FakeSession

CANVAS = uuid.uuid4()
FIELDS = {"raw_text": "a\nb", "grid_data": {"lines": []}, "position_x": 1.0, "position_y": 2.0}


def originals() -> list[Snippet]:
    return [Snippet(id=uuid.uuid4(), canvas_id=CANVAS, raw_text="a"), Snippet(id=uuid.uuid4(), canvas_id=CANVAS, raw_text="b")]


async def test_reparent_runs_after_the_insert_and_before_the_deletes():
    db = FakeSession()
    olds = originals()
    calls: list[tuple] = []

    async def reparent(session, merged_id, original_ids):
        calls.append((session, merged_id, list(original_ids)))
        db.log.append(("reparent", merged_id))

    created = await snippet_service.replace_with_merged(db, CANVAS, FIELDS, [], olds, reparent=reparent)

    assert calls == [(db, created.id, [o.id for o in olds])]
    assert [name for name, _ in db.log] == ["add", "flush", "reparent", "delete", "delete", "commit", "execute"]


async def test_a_failing_reparent_rolls_the_merge_back():
    db = FakeSession()

    async def reparent(session, merged_id, original_ids):
        raise OperationalError("update", {}, Exception("db down"))

    with pytest.raises(MergePersistError):
        await snippet_service.replace_with_merged(db, CANVAS, FIELDS, [], originals(), reparent=reparent)
    steps = [name for name, _ in db.log]
    assert steps[-1] == "rollback"
    assert "delete" not in steps and "commit" not in steps


class RecordingSession:
    def __init__(self):
        self.statements: list = []

    async def execute(self, stmt):
        self.statements.append(stmt)


async def test_reassign_recordings_updates_the_originals_rows():
    db = RecordingSession()
    merged, a, b = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    await recording_service.reassign_recordings(db, merged, [a, b])
    (stmt,) = db.statements
    sql = str(stmt.compile(dialect=postgresql.dialect()))
    assert sql.startswith("UPDATE snippet_recordings SET snippet_id=")
    assert "WHERE snippet_recordings.snippet_id IN" in sql
    params = stmt.compile(dialect=postgresql.dialect()).params
    assert merged in params.values()
    assert [a, b] in params.values()


async def test_reassign_recordings_without_originals_does_nothing():
    db = RecordingSession()
    await recording_service.reassign_recordings(db, uuid.uuid4(), [])
    assert db.statements == []


def test_merge_route_moves_recordings(monkeypatch):
    a = fake_snippet(CANVAS, "one", position_y=0.0)
    b = fake_snippet(CANVAS, "two", position_y=100.0)
    seen: dict = {}

    async def fake_get(db, canvas_id, owner_id, snippet_ids):
        return [a, b]

    async def fake_replace(db, canvas_id, fields, tags, originals, *, reparent=None):
        seen["reparent"] = reparent
        return fake_snippet(canvas_id, fields["raw_text"], **fields)

    async def fake_db():
        yield object()

    monkeypatch.setattr(snippet_service, "get_canvas_snippets", fake_get)
    monkeypatch.setattr(snippet_service, "replace_with_merged", fake_replace)
    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=uuid.uuid4())
    try:
        res = TestClient(app).post(f"/api/canvases/{CANVAS}/merge", json={"snippet_ids": [str(a.id), str(b.id)], "mode": "vertical"})
    finally:
        app.dependency_overrides.clear()
    assert res.status_code == 200, res.text
    assert seen["reparent"] is recording_service.reassign_recordings
