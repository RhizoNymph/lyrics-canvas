"""snippets.content_updated_at: bumped by content edits only, never by moves, resizes or tags.

See docs/features/snippet_content_timestamp.md.
"""

from __future__ import annotations

import importlib.util
import io
import uuid
from datetime import datetime, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.sql import functions

from app.api.canvases import _snippet_response
from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.models.snippet import GridMode, Snippet, SnippetType
from app.services import canvas_service, snippet_service, tag_service
from app.services.nlp.syllabifier import syllabify_text
from app.services.snippet_service import CONTENT_FIELDS, content_edits_differ

BACKEND = Path(__file__).resolve().parents[1]
CANVAS = uuid.uuid4()
OWNER = uuid.uuid4()
EARLIER = datetime(2026, 9, 1, 8, 0, 0, tzinfo=timezone.utc)
GRID = {"lines": [{"cells": [{"syllable": "cat", "stress": 1, "word_index": 0}, {"rest": True}], "syllable_count": 1, "raw_text": "cat"}]}


def stored_snippet(**overrides) -> Snippet:
    fields = dict(
        id=uuid.uuid4(),
        canvas_id=CANVAS,
        snippet_type=SnippetType.verse,
        grid_mode=GridMode.auto,
        beats_per_line=None,
        grid_data=syllabify_text("cat"),
        raw_text="cat",
        position_x=0.0,
        position_y=0.0,
        width=200.0,
        height=80.0,
        z_index=0,
        group_id=None,
        created_at=EARLIER,
        updated_at=EARLIER,
        content_updated_at=EARLIER,
    )
    fields.update(overrides)
    return Snippet(**fields)


def bumped(snippet: Snippet) -> bool:
    """The service marks a content edit by assigning the SQL ``now()`` (same clock as updated_at)."""
    return isinstance(snippet.content_updated_at, functions.now)


# --- the pure decision -------------------------------------------------------------------------


def test_content_fields_are_exactly_the_content_columns():
    assert CONTENT_FIELDS == frozenset({"raw_text", "snippet_type", "grid_mode", "beats_per_line", "grid_data"})


@pytest.mark.parametrize(
    "edits",
    [
        {"raw_text": "dog"},
        {"snippet_type": SnippetType.chorus},
        {"grid_mode": GridMode.fixed},
        {"beats_per_line": 8},
        {"grid_data": GRID},
        {"raw_text": "cat", "snippet_type": SnippetType.hook},
    ],
)
def test_a_differing_content_field_is_an_edit(edits):
    assert content_edits_differ(stored_snippet(), edits) is True


@pytest.mark.parametrize(
    "edits",
    [
        {},
        {"raw_text": "cat"},
        {"snippet_type": SnippetType.verse},
        {"snippet_type": "verse"},
        {"grid_mode": GridMode.auto},
        {"grid_data": syllabify_text("cat")},
        {"raw_text": "cat", "grid_data": syllabify_text("cat"), "snippet_type": SnippetType.verse},
    ],
)
def test_identical_content_is_not_an_edit(edits):
    assert content_edits_differ(stored_snippet(), edits) is False


@pytest.mark.parametrize(
    "edits",
    [
        {"position_x": 50.0},
        {"position_y": 50.0},
        {"width": 999.0, "height": 999.0},
        {"z_index": 3},
        {"group_id": uuid.uuid4()},
        {"phonetics": {"lines": []}},
    ],
)
def test_non_content_fields_are_never_an_edit(edits):
    assert content_edits_differ(stored_snippet(), edits) is False


# --- the service ------------------------------------------------------------------------------


class FakeResult:
    def __init__(self, value):
        self.value = value

    def scalar_one_or_none(self):
        return self.value

    def scalar_one(self):
        return self.value


class FakeSession:
    """Every select returns the one stored snippet; commits are counted."""

    def __init__(self, snippet: Snippet | None):
        self.snippet = snippet
        self.commits = 0
        self.statements: list = []

    async def execute(self, stmt):
        self.statements.append(stmt)
        return FakeResult(self.snippet)

    async def commit(self):
        self.commits += 1


async def update(snippet: Snippet, content_edits: dict, **changes) -> Snippet:
    db = FakeSession(snippet)
    result = await snippet_service.update_snippet(db, str(snippet.id), str(OWNER), content_edits=content_edits, **changes)
    assert result is snippet
    assert db.commits == 1
    return result


async def test_text_edit_bumps():
    s = await update(stored_snippet(), {"raw_text": "dog"}, raw_text="dog", grid_data=syllabify_text("dog"))
    assert s.raw_text == "dog"
    assert bumped(s)


async def test_type_edit_bumps():
    s = await update(stored_snippet(), {"snippet_type": SnippetType.bridge}, snippet_type=SnippetType.bridge)
    assert s.snippet_type == SnippetType.bridge
    assert bumped(s)


async def test_grid_edit_bumps():
    s = await update(stored_snippet(), {"grid_data": GRID}, grid_data=GRID, width=300.0, height=90.0)
    assert s.grid_data == GRID
    assert bumped(s)


async def test_grid_mode_and_beats_edit_bumps():
    s = await update(
        stored_snippet(), {"grid_mode": GridMode.fixed, "beats_per_line": 4}, grid_mode=GridMode.fixed, beats_per_line=4
    )
    assert bumped(s)


@pytest.mark.parametrize(
    "changes",
    [
        {"position_x": 10.0, "position_y": 20.0},
        {"width": 400.0, "height": 300.0},
        {"z_index": 7},
        {"group_id": uuid.uuid4()},
    ],
)
async def test_layout_changes_do_not_bump(changes):
    s = await update(stored_snippet(), {}, **changes)
    for key, value in changes.items():
        assert getattr(s, key) == value
    assert s.content_updated_at == EARLIER


async def test_identical_text_does_not_bump():
    # The route re-syllabifies and recomputes phonetics; the text itself did not change.
    s = await update(stored_snippet(), {"raw_text": "cat"}, raw_text="cat", grid_data=syllabify_text("cat"), phonetics={"lines": []})
    assert s.content_updated_at == EARLIER


async def test_unknown_snippet_is_none():
    db = FakeSession(None)
    assert await snippet_service.update_snippet(db, str(uuid.uuid4()), str(OWNER), content_edits={"raw_text": "x"}, raw_text="x") is None
    assert db.commits == 0


async def test_batch_position_does_not_bump():
    s = stored_snippet()
    db = FakeSession(s)
    count = await snippet_service.batch_update_positions(db, [{"id": str(s.id), "position_x": 5.0, "position_y": 6.0}], str(OWNER))
    assert count == 1
    assert (s.position_x, s.position_y) == (5.0, 6.0)
    assert s.content_updated_at == EARLIER


async def test_tag_assignment_does_not_touch_the_snippet():
    s = stored_snippet()
    tag = SimpleNamespace(id=uuid.uuid4())

    class TagSession(FakeSession):
        async def execute(self, stmt):
            self.statements.append(stmt)
            return FakeResult(s if len(self.statements) == 1 else tag)

    db = TagSession(s)
    assert await tag_service.add_tag_to_snippet(db, str(s.id), str(tag.id), str(OWNER)) is True
    assert s.content_updated_at == EARLIER
    assert s.updated_at == EARLIER
    # the only write is the association insert
    assert db.statements[-1].is_insert
    assert db.statements[-1].table.name == "snippet_tags"


# --- the model --------------------------------------------------------------------------------


def test_column_is_non_null_with_server_default_and_no_onupdate():
    col = Snippet.__table__.c.content_updated_at
    assert col.nullable is False
    assert col.server_default is not None
    assert col.onupdate is None
    assert col.type.timezone is True


# --- the wire shape ---------------------------------------------------------------------------


def test_response_carries_content_updated_at_like_updated_at():
    s = stored_snippet(
        updated_at=datetime(2026, 9, 30, 12, 5, 0, tzinfo=timezone.utc),
        content_updated_at=datetime(2026, 9, 30, 12, 0, 0, 123456, tzinfo=timezone.utc),
    )
    s.tags = []
    body = _snippet_response(s).model_dump(mode="json")
    assert body["content_updated_at"] == str(s.content_updated_at) == "2026-09-30 12:00:00.123456+00:00"
    assert body["updated_at"] == str(s.updated_at)


def test_openapi_snippet_response_requires_content_updated_at():
    schema = app.openapi()["components"]["schemas"]["SnippetResponse"]
    assert schema["properties"]["content_updated_at"] == {"title": "Content Updated At", "type": "string"}
    assert "content_updated_at" in schema["required"]


@pytest.fixture
def client():
    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=OWNER)
    yield TestClient(app)
    app.dependency_overrides.clear()


def test_put_passes_only_client_sent_content_fields_as_edits(client, monkeypatch):
    seen: dict = {}

    async def fake_update(db, snippet_id, owner_id, *, content_edits, **changes):
        seen["content_edits"] = dict(content_edits)
        seen["changes"] = changes
        s = stored_snippet(raw_text=changes.get("raw_text", "cat"))
        s.tags = []
        return s

    monkeypatch.setattr(snippet_service, "update_snippet", fake_update)
    res = client.put(f"/api/snippets/{uuid.uuid4()}", json={"raw_text": "dog", "position_x": 3.0})
    assert res.status_code == 200, res.text
    # the re-syllabified grid and phonetics are derived, not client edits
    assert seen["content_edits"] == {"raw_text": "dog"}
    assert {"raw_text", "grid_data", "phonetics", "position_x"} <= set(seen["changes"])
    assert res.json()["content_updated_at"] == str(EARLIER)


def test_put_position_only_has_no_content_edits(client, monkeypatch):
    seen: dict = {}

    async def fake_update(db, snippet_id, owner_id, *, content_edits, **changes):
        seen["content_edits"] = dict(content_edits)
        s = stored_snippet()
        s.tags = []
        return s

    monkeypatch.setattr(snippet_service, "update_snippet", fake_update)
    res = client.put(f"/api/snippets/{uuid.uuid4()}", json={"position_x": 3.0, "position_y": 4.0, "width": 500, "height": 100})
    assert res.status_code == 200, res.text
    assert seen["content_edits"] == {}


# --- fork keeps the content clock -------------------------------------------------------------


async def test_fork_copies_content_updated_at():
    original = stored_snippet()
    source = SimpleNamespace(id=uuid.uuid4(), title="Song", is_public=True, viewport_state=None, snippets=[original])
    added: list = []

    class ForkSession:
        async def execute(self, stmt):
            return FakeResult(source)

        def add(self, obj):
            added.append(obj)

        async def flush(self):
            for obj in added:
                if getattr(obj, "id", None) is None:
                    obj.id = uuid.uuid4()

        async def commit(self):
            pass

        async def refresh(self, obj):
            pass

    await canvas_service.fork_canvas(ForkSession(), str(source.id), str(OWNER))
    copies = [o for o in added if isinstance(o, Snippet)]
    assert len(copies) == 1
    assert copies[0].content_updated_at == EARLIER


# --- the migration ----------------------------------------------------------------------------


def load_migration():
    path = BACKEND / "alembic" / "versions" / "003_snippet_content_updated_at.py"
    spec = importlib.util.spec_from_file_location("migration_003", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_migration_follows_002():
    m = load_migration()
    assert (m.revision, m.down_revision) == ("003", "002")


def offline_sql(target: str) -> str:
    from alembic import command
    from alembic.config import Config

    buffer = io.StringIO()
    # No ini file: env.py skips fileConfig, so the test run's logging is left alone.
    cfg = Config(output_buffer=buffer)
    cfg.set_main_option("script_location", str(BACKEND / "alembic"))
    match target:
        case "upgrade":
            command.upgrade(cfg, "002:003", sql=True)
        case "downgrade":
            command.downgrade(cfg, "003:002", sql=True)
    return buffer.getvalue()


def test_migration_adds_backfills_then_requires_the_column():
    sql = offline_sql("upgrade")
    add = sql.index("ALTER TABLE snippets ADD COLUMN content_updated_at TIMESTAMP WITH TIME ZONE DEFAULT now()")
    backfill = sql.index("UPDATE snippets SET content_updated_at = COALESCE(updated_at, created_at, now())")
    not_null = sql.index("ALTER TABLE snippets ALTER COLUMN content_updated_at SET NOT NULL")
    assert add < backfill < not_null


def test_migration_downgrade_drops_the_column():
    assert "ALTER TABLE snippets DROP COLUMN content_updated_at" in offline_sql("downgrade")


# --- merge: the merged snippet is new content, stamped by the server default --------------------


async def test_merged_snippet_leaves_content_updated_at_to_the_server_default():
    olds = [stored_snippet(raw_text="a"), stored_snippet(raw_text="b")]
    added: list = []

    class MergeSession:
        def add(self, obj):
            added.append(obj)

        async def flush(self):
            for obj in added:
                if obj.id is None:
                    obj.id = uuid.uuid4()

        async def delete(self, obj):
            pass

        async def commit(self):
            pass

        async def rollback(self):
            pass

        async def execute(self, stmt):
            return FakeResult(added[0])

    created = await snippet_service.replace_with_merged(
        MergeSession(), CANVAS, {"raw_text": "a\nb", "grid_data": {"lines": []}}, [], olds
    )
    # not copied from the originals: unset, so the INSERT takes server_default now()
    assert created.content_updated_at is None


def test_merge_route_returns_content_updated_at_and_does_not_set_it(client, monkeypatch):
    from tests.route_fakes import fake_snippet

    a = fake_snippet(CANVAS, "one")
    b = fake_snippet(CANVAS, "two", position_y=10.0)
    merged_at = datetime(2026, 10, 13, 9, 0, 0, tzinfo=timezone.utc)
    seen: dict = {}

    async def fake_get(db, canvas_id, owner_id, snippet_ids):
        return [a, b]

    async def fake_replace(db, canvas_id, fields, tags, originals):
        seen.update(fields)
        return fake_snippet(canvas_id, fields["raw_text"], **fields, content_updated_at=merged_at)

    monkeypatch.setattr(snippet_service, "get_canvas_snippets", fake_get)
    monkeypatch.setattr(snippet_service, "replace_with_merged", fake_replace)
    res = client.post(f"/api/canvases/{CANVAS}/merge", json={"snippet_ids": [str(a.id), str(b.id)], "mode": "vertical"})
    assert res.status_code == 200, res.text
    assert "content_updated_at" not in seen
    assert res.json()["snippet"]["content_updated_at"] == str(merged_at)
