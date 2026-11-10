"""snippet_service.replace_with_merged: create + placement + tag links + removals in one transaction.

An original only on the merge canvas is deleted; one also placed elsewhere loses only this canvas's
placement (docs/features/snippet_placements.md#merge)."""

import uuid

import pytest
from sqlalchemy.exc import IntegrityError

from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet
from app.models.tag import Tag
from app.services import snippet_service
from app.services.merge_service import MergePersistError
from app.services.placements.view import CanvasRef, PlacedSnippet

CANVAS = uuid.uuid4()


class FakeResult:
    def __init__(self, value):
        self.value = value

    def scalar_one(self):
        return self.value


class FakeSession:
    """Records the unit of work; ``fail_on`` names the step that raises a database error."""

    def __init__(self, fail_on: str | None = None):
        self.fail_on = fail_on
        self.log: list[tuple[str, object]] = []
        self.added: list = []
        self.deleted: list = []

    def _step(self, name: str, arg=None):
        self.log.append((name, arg))
        if name == self.fail_on:
            raise IntegrityError("stmt", {}, Exception(name))

    def add(self, obj):
        self.added.append(obj)
        self._step("add", obj)

    async def flush(self):
        for obj in self.added:
            if obj.id is None:
                obj.id = uuid.uuid4()
        self._step("flush")

    async def delete(self, obj):
        self._step("delete", obj.id)
        self.deleted.append(obj)

    async def commit(self):
        self._step("commit")

    async def rollback(self):
        self.log.append(("rollback", None))

    async def execute(self, stmt):
        self.log.append(("execute", None))
        return FakeResult(self.added[0])


@pytest.fixture(autouse=True)
def reload_added(monkeypatch):
    """The post-commit reload returns the merged snippet and placement that were added (logged as
    one "execute" step)."""

    async def reload(db, placement_id):
        db.log.append(("execute", None))
        (snippet,) = [o for o in db.added if isinstance(o, Snippet)]
        (placement,) = [o for o in db.added if isinstance(o, SnippetPlacement)]
        assert placement.id == placement_id
        return PlacedSnippet(snippet=snippet, placement=placement, canvases=(CanvasRef(CANVAS, "Song"),))

    monkeypatch.setattr(snippet_service, "placed_by_placement_id", reload)


OWNER = uuid.uuid4()


def placed(text: str, *also_on: uuid.UUID) -> PlacedSnippet:
    snippet = Snippet(id=uuid.uuid4(), owner_id=OWNER, raw_text=text)
    placement = SnippetPlacement(id=uuid.uuid4(), snippet_id=snippet.id, canvas_id=CANVAS)
    canvases = (CanvasRef(CANVAS, "Song"), *(CanvasRef(c, "other") for c in also_on))
    return PlacedSnippet(snippet=snippet, placement=placement, canvases=canvases)


def originals():
    return [placed("a"), placed("b")]


FIELDS = {"raw_text": "a\nb", "grid_data": {"lines": []}, "position_x": 1.0, "position_y": 2.0}


async def test_creates_links_tags_deletes_and_commits_once():
    db = FakeSession()
    owner = uuid.uuid4()
    tags = [Tag(id=uuid.uuid4(), user_id=owner, name="t1"), Tag(id=uuid.uuid4(), user_id=owner, name="t2")]
    olds = originals()
    created = await snippet_service.replace_with_merged(db, CANVAS, FIELDS, tags, olds)

    steps = [name for name, _ in db.log]
    assert steps == ["add", "add", "flush", "delete", "delete", "commit", "execute"]
    assert [arg for name, arg in db.log if name == "delete"] == [o.id for o in olds]
    assert created.canvas_id == CANVAS
    assert created.raw_text == "a\nb"
    assert created.owner_id == OWNER
    assert (created.position_x, created.position_y) == (1.0, 2.0)  # layout goes to the placement
    assert created.placement.snippet_id == created.id
    assert [t.name for t in created.tags] == ["t1", "t2"]


async def test_an_original_placed_elsewhere_loses_only_this_canvas_placement():
    db = FakeSession()
    shared, only = placed("a", uuid.uuid4()), placed("b")
    seen: list = []

    async def reparent(session, merged_id, original_ids):
        seen.extend(original_ids)

    await snippet_service.replace_with_merged(db, CANVAS, FIELDS, [], [shared, only], reparent=reparent)
    assert db.deleted == [shared.placement, only.snippet]
    # recordings follow only the originals the merge deletes
    assert seen == [only.id]


@pytest.mark.parametrize("fail_on", ["flush", "delete", "commit"])
async def test_any_database_error_rolls_back_everything(fail_on):
    db = FakeSession(fail_on=fail_on)
    with pytest.raises(MergePersistError):
        await snippet_service.replace_with_merged(db, CANVAS, FIELDS, [], originals())
    steps = [name for name, _ in db.log]
    # the failing step is the last thing attempted, then everything is rolled back
    assert steps[-2:] == [fail_on, "rollback"]
    assert steps.count("commit") == (1 if fail_on == "commit" else 0)
    assert "execute" not in steps
