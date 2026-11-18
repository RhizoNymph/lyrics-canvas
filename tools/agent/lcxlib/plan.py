"""Plan file types (version 1) and the strict parser."""

from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from lcxlib.errors import Issue, IssueCode, PlanError, UsageError

PLAN_VERSION = 1


@dataclass(frozen=True)
class CreateTag:
    name: str
    color: str | None = None


@dataclass(frozen=True)
class TagSnippet:
    snippet_id: str
    add: tuple[str, ...] = ()
    remove: tuple[str, ...] = ()


@dataclass(frozen=True)
class TagWord:
    word_id: str
    add: tuple[str, ...] = ()
    remove: tuple[str, ...] = ()


@dataclass(frozen=True)
class Move:
    snippet_id: str
    from_canvas: str
    to_canvas: str


@dataclass(frozen=True)
class ShowOn:
    snippet_id: str
    canvas: str


@dataclass(frozen=True)
class RemoveFrom:
    snippet_id: str
    canvas: str


@dataclass(frozen=True)
class SetLayout:
    snippet_id: str
    canvas: str
    position_x: float | None = None
    position_y: float | None = None
    width: float | None = None
    height: float | None = None
    z_index: int | None = None
    list_rank: str | None = None


@dataclass(frozen=True)
class ReorderList:
    canvas: str
    snippet_ids: tuple[str, ...]


@dataclass(frozen=True)
class CreateCanvas:
    title: str
    ref: str


@dataclass(frozen=True)
class RenameCanvas:
    canvas: str
    title: str


@dataclass(frozen=True)
class AddBankWord:
    text: str
    tags: tuple[str, ...] = ()


@dataclass(frozen=True)
class EditText:
    snippet_id: str
    raw_text: str
    base_version: int


@dataclass(frozen=True)
class SetScore:
    """Replace (``score`` an object) or remove (``score`` null) a snippet's score; ``score_view``
    None keeps the snippet's view. Checked locally against the snippet's grid."""

    snippet_id: str
    score: dict | None
    score_view: bool | None = None


Op = (
    CreateTag | TagSnippet | TagWord | Move | ShowOn | RemoveFrom | SetLayout | ReorderList | CreateCanvas
    | RenameCanvas | AddBankWord | EditText | SetScore
)


@dataclass(frozen=True)
class Plan:
    description: str
    ops: tuple[Op, ...]


# Field kinds for the parser: (python type check, description)
_STR, _STRS, _NUM, _INT, _OPT_STR, _OPT_OBJ, _BOOL = "str", "list[str]", "number", "int", "str?", "object|null", "bool"
_OP_FIELDS: dict[str, tuple[type, dict[str, str], set[str]]] = {
    # op name: (dataclass, {field: kind}, required fields)
    "create_tag": (CreateTag, {"name": _STR, "color": _OPT_STR}, {"name"}),
    "tag_snippet": (TagSnippet, {"snippet_id": _STR, "add": _STRS, "remove": _STRS}, {"snippet_id"}),
    "tag_word": (TagWord, {"word_id": _STR, "add": _STRS, "remove": _STRS}, {"word_id"}),
    "move": (Move, {"snippet_id": _STR, "from_canvas": _STR, "to_canvas": _STR}, {"snippet_id", "from_canvas", "to_canvas"}),
    "show_on": (ShowOn, {"snippet_id": _STR, "canvas": _STR}, {"snippet_id", "canvas"}),
    "remove_from": (RemoveFrom, {"snippet_id": _STR, "canvas": _STR}, {"snippet_id", "canvas"}),
    "set_layout": (
        SetLayout,
        {"snippet_id": _STR, "canvas": _STR, "position_x": _NUM, "position_y": _NUM, "width": _NUM,
         "height": _NUM, "z_index": _INT, "list_rank": _STR},
        {"snippet_id", "canvas"},
    ),
    "reorder_list": (ReorderList, {"canvas": _STR, "snippet_ids": _STRS}, {"canvas", "snippet_ids"}),
    "create_canvas": (CreateCanvas, {"title": _STR, "ref": _STR}, {"title", "ref"}),
    "rename_canvas": (RenameCanvas, {"canvas": _STR, "title": _STR}, {"canvas", "title"}),
    "add_bank_word": (AddBankWord, {"text": _STR, "tags": _STRS}, {"text"}),
    "edit_text": (EditText, {"snippet_id": _STR, "raw_text": _STR, "base_version": _INT}, {"snippet_id", "raw_text", "base_version"}),
    "set_score": (SetScore, {"snippet_id": _STR, "score": _OPT_OBJ, "score_view": _BOOL}, {"snippet_id", "score"}),
}
LAYOUT_FIELDS = ("position_x", "position_y", "width", "height", "z_index", "list_rank")
_REF_RE = re.compile(r"^\$[A-Za-z0-9_-]{1,64}$")


def _field_ok(kind: str, value: object) -> bool:
    match kind:
        case "str":
            return isinstance(value, str)
        case "str?":
            return value is None or isinstance(value, str)
        case "list[str]":
            return isinstance(value, list) and all(isinstance(v, str) for v in value)
        case "number":
            return isinstance(value, int | float) and not isinstance(value, bool)
        case "int":
            return isinstance(value, int) and not isinstance(value, bool)
        case "object|null":
            return value is None or isinstance(value, dict)
        case "bool":
            return isinstance(value, bool)
    return False


def _parse_op(index: int, raw: object) -> tuple[Op | None, list[Issue]]:
    if not isinstance(raw, dict):
        return None, [Issue(index, IssueCode.INVALID_PLAN, "an op must be an object")]
    name = raw.get("op")
    if name not in _OP_FIELDS:
        return None, [Issue(index, IssueCode.UNKNOWN_OP, f"unknown op {name!r}; known: {', '.join(_OP_FIELDS)}")]
    cls, kinds, required = _OP_FIELDS[name]
    issues = [Issue(index, IssueCode.INVALID_FIELD, f"{name}: unknown field {k!r}") for k in raw if k != "op" and k not in kinds]
    issues += [Issue(index, IssueCode.INVALID_FIELD, f"{name}: missing field {k!r}") for k in sorted(required) if k not in raw]
    issues += [
        Issue(index, IssueCode.INVALID_FIELD, f"{name}: {k} must be {kinds[k]}")
        for k, v in raw.items() if k in kinds and k in raw and not _field_ok(kinds[k], v)
    ]
    if issues:
        return None, issues
    values = {k: tuple(v) if kinds[k] == _STRS else v for k, v in raw.items() if k != "op"}
    op = cls(**values)
    match op:
        case SetLayout() if all(getattr(op, f) is None for f in LAYOUT_FIELDS):
            issues.append(Issue(index, IssueCode.INVALID_FIELD, f"set_layout: give at least one of {', '.join(LAYOUT_FIELDS)}"))
        case EditText(base_version=v) if v < 1:
            issues.append(Issue(index, IssueCode.INVALID_FIELD, "edit_text: base_version must be >= 1"))
        case CreateCanvas(ref=ref) if not _REF_RE.match(ref):
            issues.append(Issue(index, IssueCode.INVALID_FIELD, f"create_canvas: ref must look like $name (got {ref!r})"))
        case _:
            pass
    return (None if issues else op), issues


def parse_plan(raw: object) -> Plan:
    """A typed plan, or ``PlanError`` listing every problem (unknown ops and fields are rejected)."""
    if not isinstance(raw, dict):
        raise PlanError([Issue(0, IssueCode.INVALID_PLAN, "the plan must be a JSON object")])
    issues = [Issue(0, IssueCode.INVALID_PLAN, f"unknown top-level field {k!r}") for k in raw if k not in ("version", "description", "ops")]
    if raw.get("version") != PLAN_VERSION:
        issues.append(Issue(0, IssueCode.INVALID_PLAN, f"version must be {PLAN_VERSION}"))
    description = raw.get("description", "")
    if not isinstance(description, str):
        issues.append(Issue(0, IssueCode.INVALID_PLAN, "description must be a string"))
    ops_raw = raw.get("ops")
    if not isinstance(ops_raw, list) or not ops_raw:
        issues.append(Issue(0, IssueCode.INVALID_PLAN, "ops must be a non-empty list"))
        ops_raw = []
    ops: list[Op] = []
    for i, item in enumerate(ops_raw, start=1):
        op, op_issues = _parse_op(i, item)
        issues += op_issues
        if op is not None:
            ops.append(op)
    if issues:
        raise PlanError(issues)
    return Plan(description=str(description), ops=tuple(ops))


def load_plan(path: str) -> Plan:
    try:
        text = sys.stdin.read() if path == "-" else Path(path).read_text(encoding="utf-8")
    except OSError as exc:
        raise UsageError(f"cannot read plan {path}: {exc.strerror}") from exc
    try:
        raw = json.loads(text)
    except json.JSONDecodeError as exc:
        raise PlanError([Issue(0, IssueCode.INVALID_PLAN, f"not JSON: {exc}")]) from exc
    return parse_plan(raw)
