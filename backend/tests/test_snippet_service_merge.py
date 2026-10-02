"""snippet_service.replace_with_merged: create + tag links + deletes in one transaction."""

import uuid

import pytest
from sqlalchemy.exc import IntegrityError

from app.models.snippet import Snippet
from app.models.tag import Tag
from app.services import snippet_service
from app.services.merge_service import MergePersistError

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

    async def commit(self):
        self._step("commit")

    async def rollback(self):
        self.log.append(("rollback", None))

    async def execute(self, stmt):
        self.log.append(("execute", None))
        return FakeResult(self.added[0])


def originals():
    return [Snippet(id=uuid.uuid4(), canvas_id=CANVAS, raw_text="a"), Snippet(id=uuid.uuid4(), canvas_id=CANVAS, raw_text="b")]


FIELDS = {"raw_text": "a\nb", "grid_data": {"lines": []}, "position_x": 1.0, "position_y": 2.0}


async def test_creates_links_tags_deletes_and_commits_once():
    db = FakeSession()
    tags = [Tag(id=uuid.uuid4(), canvas_id=CANVAS, name="t1"), Tag(id=uuid.uuid4(), canvas_id=CANVAS, name="t2")]
    olds = originals()
    created = await snippet_service.replace_with_merged(db, CANVAS, FIELDS, tags, olds)

    steps = [name for name, _ in db.log]
    assert steps == ["add", "flush", "delete", "delete", "commit", "execute"]
    assert [arg for name, arg in db.log if name == "delete"] == [o.id for o in olds]
    assert created.canvas_id == CANVAS
    assert created.raw_text == "a\nb"
    assert [t.name for t in created.tags] == ["t1", "t2"]


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
