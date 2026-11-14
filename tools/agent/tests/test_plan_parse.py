"""The strict plan parser: version, unknown ops and fields, types, op-specific rules."""

from __future__ import annotations

from typing import Any

import pytest

from lcxlib.errors import IssueCode, PlanError
from lcxlib.plan import (
    AddBankWord, CreateCanvas, CreateTag, EditText, Move, RemoveFrom, RenameCanvas, ReorderList, SetLayout, ShowOn,
    TagSnippet, TagWord, parse_plan,
)

SID = "00000000-0000-0000-0000-0000000000c9"


def plan(*ops: dict[str, Any], **top: Any) -> dict[str, Any]:
    return {"version": 1, "description": "d", "ops": list(ops), **top}


def issues(raw: object) -> list[tuple[int, IssueCode]]:
    with pytest.raises(PlanError) as exc:
        parse_plan(raw)
    return [(i.index, i.code) for i in exc.value.issues]


def test_every_op_parses_to_its_type() -> None:
    p = parse_plan(plan(
        {"op": "create_tag", "name": "hook"},
        {"op": "tag_snippet", "snippet_id": SID, "add": ["hook"], "remove": ["draft"]},
        {"op": "tag_word", "word_id": SID, "add": [], "remove": ["x"]},
        {"op": "move", "snippet_id": SID, "from_canvas": "A", "to_canvas": "B"},
        {"op": "show_on", "snippet_id": SID, "canvas": "A"},
        {"op": "remove_from", "snippet_id": SID, "canvas": "A"},
        {"op": "set_layout", "snippet_id": SID, "canvas": "A", "position_x": 1, "position_y": 2.5, "list_rank": "a0"},
        {"op": "reorder_list", "canvas": "A", "snippet_ids": [SID]},
        {"op": "create_canvas", "title": "New", "ref": "$new1"},
        {"op": "rename_canvas", "canvas": "$new1", "title": "Newer"},
        {"op": "add_bank_word", "text": "glow", "tags": ["hook"]},
        {"op": "edit_text", "snippet_id": SID, "raw_text": "x", "base_version": 3},
    ))
    assert [type(o) for o in p.ops] == [CreateTag, TagSnippet, TagWord, Move, ShowOn, RemoveFrom, SetLayout,
                                        ReorderList, CreateCanvas, RenameCanvas, AddBankWord, EditText]
    assert p.ops[1] == TagSnippet(SID, ("hook",), ("draft",))
    assert p.ops[6] == SetLayout(SID, "A", position_x=1, position_y=2.5, list_rank="a0")
    assert p.description == "d"


def test_not_an_object() -> None:
    assert issues([]) == [(0, IssueCode.INVALID_PLAN)]


@pytest.mark.parametrize("version", [None, 2, "1"])
def test_version_must_be_1(version: object) -> None:
    assert (0, IssueCode.INVALID_PLAN) in issues(plan({"op": "create_tag", "name": "x"}, version=version))


def test_empty_ops_and_unknown_top_level() -> None:
    assert issues({"version": 1, "ops": []}) == [(0, IssueCode.INVALID_PLAN)]
    assert issues(plan({"op": "create_tag", "name": "x"}, extra=1)) == [(0, IssueCode.INVALID_PLAN)]


def test_unknown_op_rejected() -> None:
    assert issues(plan({"op": "create_tag", "name": "x"}, {"op": "delete_snippet", "snippet_id": SID})) == [(2, IssueCode.UNKNOWN_OP)]
    assert issues(plan({"name": "x"})) == [(1, IssueCode.UNKNOWN_OP)]


def test_unknown_field_rejected() -> None:
    assert issues(plan({"op": "create_tag", "name": "x", "colour": "#ffffff"})) == [(1, IssueCode.INVALID_FIELD)]


def test_missing_field_rejected() -> None:
    assert issues(plan({"op": "move", "snippet_id": SID, "to_canvas": "B"})) == [(1, IssueCode.INVALID_FIELD)]
    assert issues(plan({"op": "edit_text", "snippet_id": SID, "raw_text": "x"})) == [(1, IssueCode.INVALID_FIELD)]


@pytest.mark.parametrize(
    "op",
    [
        {"op": "create_tag", "name": 5},
        {"op": "tag_snippet", "snippet_id": SID, "add": "hook"},
        {"op": "tag_snippet", "snippet_id": SID, "add": [1]},
        {"op": "set_layout", "snippet_id": SID, "canvas": "A", "position_x": "1"},
        {"op": "set_layout", "snippet_id": SID, "canvas": "A", "z_index": 1.5},
        {"op": "set_layout", "snippet_id": SID, "canvas": "A", "position_x": True},
        {"op": "edit_text", "snippet_id": SID, "raw_text": "x", "base_version": "3"},
        {"op": "edit_text", "snippet_id": SID, "raw_text": "x", "base_version": 0},
        {"op": "set_layout", "snippet_id": SID, "canvas": "A"},
        {"op": "create_canvas", "title": "T", "ref": "new1"},
        {"op": "create_canvas", "title": "T", "ref": "$has space"},
        "not an object",
    ],
)
def test_bad_values_rejected(op: object) -> None:
    found = issues(plan(op))
    assert found and all(i == 1 for i, _ in found)


def test_all_problems_reported_together() -> None:
    found = issues(plan({"op": "nope"}, {"op": "create_tag"}, {"op": "create_tag", "name": "ok"}, {"op": "move"}))
    assert {i for i, _ in found} == {1, 2, 4}
