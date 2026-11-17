"""Checking: simulate the plan over the snapshot, producing resolved steps and issues."""

from __future__ import annotations

import json
import re
import unicodedata
import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

from lcxlib.errors import ApiError, Issue, IssueCode
from lcxlib.client import Api
from lcxlib.plan import (
    LAYOUT_FIELDS, AddBankWord, CreateCanvas, CreateTag, EditText, Move, Op, Plan, RemoveFrom, RenameCanvas,
    ReorderList, SetLayout, ShowOn, TagSnippet, TagWord,
)
from lcxlib.rank import RankError, n_keys_between, validate_rank
from lcxlib.world import World, first_line, list_order


@dataclass
class ApplyContext:
    """What later steps need from earlier ones while applying."""

    canvas_refs: dict[str, str]  # $ref -> canvas id
    tag_ids: dict[str, str]  # lower(name) -> tag id
    versions: dict[str, int]  # snippet id -> latest known version

    def canvas(self, key: str) -> str:
        return self.canvas_refs[key] if key.startswith("$") else key


Runner = Callable[[Api, ApplyContext], object]


@dataclass
class Step:
    index: int
    op: str
    summary: str
    details: list[str] = field(default_factory=list)
    noop: str | None = None  # why nothing changes
    run: Runner | None = None

    def as_json(self) -> dict[str, object]:
        return {"index": self.index, "op": self.op, "change": self.noop is None, "summary": self.summary,
                "details": self.details, **({"no_change": self.noop} if self.noop else {})}


class _Skip(Exception):
    """Raised inside a check to record an issue and move on to the next op."""

    def __init__(self, code: IssueCode, message: str):
        super().__init__(message)
        self.code, self.message = code, message


def _is_uuid(s: str) -> bool:
    try:
        uuid.UUID(s)
    except ValueError:
        return False
    return True


def _clean_title(title: str) -> str:
    t = title.strip()
    if not 1 <= len(t) <= 255:
        raise _Skip(IssueCode.INVALID_FIELD, "canvas titles are 1-255 characters after trimming")
    return t


def _clean_tag_name(name: str) -> str:
    n = name.strip()
    if not 1 <= len(n) <= 100 or any(unicodedata.category(c) == "Cc" for c in n):
        raise _Skip(IssueCode.INVALID_FIELD, f"tag names are 1-100 characters without control characters (got {name!r})")
    return n


def _clean_word(text: str) -> str:
    t = " ".join(unicodedata.normalize("NFC", text).split())
    if not 1 <= len(t) <= 64:
        raise _Skip(IssueCode.INVALID_FIELD, f"word bank texts are 1-64 characters (got {text!r})")
    return t


class Checker:
    """Walks the plan in order over a mutable copy of the account."""

    def __init__(self, world: World):
        self.w = world
        self.titles: dict[str, str] = dict(world.canvases)  # canvas key -> title (keys: ids and $refs)
        self.order: dict[str, list[str]] = {cid: list_order(p) for cid, p in world.placements.items()}
        self.tag_names: dict[str, str] = {low: world.tag_names[tid] for low, tid in world.tags.items()}  # lower -> name
        self.snippet_tags = {sid: {world.tag_names[t].lower() for t in s["tag_ids"] if t in world.tag_names} for sid, s in world.snippets.items()}
        self.word_tags = {wid: {t["name"].lower() for t in w["tags"]} for wid, w in world.words.items()}
        self.bank = {w["text"].lower(): self.word_tags[wid] for wid, w in world.words.items()}  # lower text -> tags
        self.refs: dict[str, str] = {}  # $ref -> canvas key (the ref itself, or an existing id it reuses)
        self.edited: set[str] = set()
        self.steps: list[Step] = []
        self.issues: list[Issue] = []

    # -- resolution --------------------------------------------------------------------------
    def canvas(self, name: str) -> str:
        if name.startswith("$"):
            if name not in self.refs:
                raise _Skip(IssueCode.UNKNOWN_REF, f"{name} is not created by an earlier create_canvas op")
            return self.refs[name]
        if name in self.titles and _is_uuid(name):
            return name
        hits = [key for key, title in self.titles.items() if title == name]
        match hits:
            case [key]:
                return key
            case []:
                raise _Skip(IssueCode.UNKNOWN_CANVAS, f"no canvas with id or title {name!r}")
            case _:
                raise _Skip(IssueCode.AMBIGUOUS_CANVAS, f"{len(hits)} canvases are titled {name!r}; use the id")

    def snippet(self, sid: str) -> dict[str, Any]:
        if sid not in self.w.snippets:
            raise _Skip(IssueCode.UNKNOWN_SNIPPET, f"no snippet {sid}")
        return self.w.snippets[sid]

    def tag(self, name: str) -> str:
        low = name.strip().lower()
        if low not in self.tag_names:
            raise _Skip(IssueCode.UNKNOWN_TAG, f"no tag {name!r} (add a create_tag op first)")
        return low

    def canvases_of(self, sid: str) -> list[str]:
        return [key for key, ids in self.order.items() if sid in ids]

    # -- labels ------------------------------------------------------------------------------
    def ct(self, key: str) -> str:
        return f'"{self.titles[key]}"' + (f" ({key})" if key.startswith("$") else "")

    def sl(self, sid: str) -> str:
        s = self.w.snippets[sid]
        return f'{sid[:8]} [{s["snippet_type"]}] "{first_line(s["raw_text"])}"'

    # -- driver ------------------------------------------------------------------------------
    def check(self, plan: Plan) -> None:
        for index, op in enumerate(plan.ops, start=1):
            try:
                self.steps.append(self.step(index, op))
            except _Skip as skip:
                self.issues.append(Issue(index, skip.code, skip.message))

    def step(self, i: int, op: Op) -> Step:
        match op:
            case CreateTag():
                return self.create_tag(i, op)
            case TagSnippet():
                return self.tag_snippet(i, op)
            case TagWord():
                return self.tag_word(i, op)
            case Move():
                return self.move(i, op)
            case ShowOn():
                return self.show_on(i, op)
            case RemoveFrom():
                return self.remove_from(i, op)
            case SetLayout():
                return self.set_layout(i, op)
            case ReorderList():
                return self.reorder_list(i, op)
            case CreateCanvas():
                return self.create_canvas(i, op)
            case RenameCanvas():
                return self.rename_canvas(i, op)
            case AddBankWord():
                return self.add_bank_word(i, op)
            case EditText():
                return self.edit_text(i, op)

    # -- ops ---------------------------------------------------------------------------------
    def create_tag(self, i: int, op: CreateTag) -> Step:
        name = _clean_tag_name(op.name)
        if op.color is not None and not re.fullmatch(r"#[0-9a-fA-F]{6}", op.color):
            raise _Skip(IssueCode.INVALID_FIELD, f"color must be #rrggbb (got {op.color!r})")
        summary = f'create tag "{name}"'
        if name.lower() in self.tag_names:
            return Step(i, "create_tag", summary, noop=f'tag "{self.tag_names[name.lower()]}" already exists')
        self.tag_names[name.lower()] = name
        body: dict[str, object] = {"name": name, **({"color": op.color} if op.color else {})}

        def run(api: Api, ctx: ApplyContext) -> object:
            tag = api.request("POST", "/api/tags", body=body)
            ctx.tag_ids[name.lower()] = tag["id"]
            return {"tag_id": tag["id"]}

        return Step(i, "create_tag", summary, run=run)

    def _tag_changes(self, current: set[str], add: tuple[str, ...], remove: tuple[str, ...]) -> tuple[list[str], list[str]]:
        adds, removes = [self.tag(n) for n in add], [self.tag(n) for n in remove]
        if both := set(adds) & set(removes):
            raise _Skip(IssueCode.INVALID_FIELD, f"tags both added and removed: {', '.join(sorted(both))}")
        if not adds and not removes:
            raise _Skip(IssueCode.INVALID_FIELD, "give at least one tag in add or remove")
        return (list(dict.fromkeys(t for t in adds if t not in current)),
                list(dict.fromkeys(t for t in removes if t in current)))

    def _tag_summary(self, adds: list[str], removes: list[str]) -> str:
        return " ".join([*(f"+{self.tag_names[t]}" for t in adds), *(f"-{self.tag_names[t]}" for t in removes)])

    def tag_snippet(self, i: int, op: TagSnippet) -> Step:
        self.snippet(op.snippet_id)
        sid, current = op.snippet_id, self.snippet_tags[op.snippet_id]
        adds, removes = self._tag_changes(current, op.add, op.remove)
        summary = f"tag snippet {self.sl(sid)}: {self._tag_summary(adds, removes)}"
        if not adds and not removes:
            return Step(i, "tag_snippet", f"tag snippet {self.sl(sid)}", noop="its tags already match")
        current |= set(adds)
        current -= set(removes)

        def run(api: Api, ctx: ApplyContext) -> object:
            for low in adds:
                r = api.request("POST", f"/api/snippets/{sid}/tags", query={"tag_id": ctx.tag_ids[low]})
                ctx.versions[sid] = r["version"]
            for low in removes:
                r = api.request("DELETE", f"/api/snippets/{sid}/tags/{ctx.tag_ids[low]}")
                ctx.versions[sid] = r["version"]
            return {"version": ctx.versions.get(sid)}

        return Step(i, "tag_snippet", summary, run=run)

    def tag_word(self, i: int, op: TagWord) -> Step:
        if op.word_id not in self.w.words:
            raise _Skip(IssueCode.UNKNOWN_WORD, f"no word bank word {op.word_id}")
        wid, current = op.word_id, self.word_tags[op.word_id]
        adds, removes = self._tag_changes(current, op.add, op.remove)
        label = f'tag word "{self.w.words[wid]["text"]}"'
        if not adds and not removes:
            return Step(i, "tag_word", label, noop="its tags already match")
        current |= set(adds)
        current -= set(removes)

        def run(api: Api, ctx: ApplyContext) -> object:
            for low, verb in [*((t, "link") for t in adds), *((t, "unlink") for t in removes)]:
                api.request("POST", "/api/word-bank/tags", body={"word_ids": [wid], "tag_id": ctx.tag_ids[low], "op": verb})
            return None

        return Step(i, "tag_word", f"{label}: {self._tag_summary(adds, removes)}", run=run)

    def _place_on_top(self, sid: str, key: str) -> None:
        self.order[key].insert(0, sid)

    def _transfer(self, sid: str, source: str, target: str, mode: str) -> Runner:
        def run(api: Api, ctx: ApplyContext) -> object:
            body = {"snippet_ids": [sid], "target_canvas_id": ctx.canvas(target), "mode": mode}
            return api.request("POST", f"/api/canvases/{ctx.canvas(source)}/snippets/transfer", body=body)

        return run

    def move(self, i: int, op: Move) -> Step:
        self.snippet(op.snippet_id)
        sid, src, dst = op.snippet_id, self.canvas(op.from_canvas), self.canvas(op.to_canvas)
        if src == dst:
            raise _Skip(IssueCode.SAME_CANVAS, "from_canvas and to_canvas are the same canvas")
        summary = f"move snippet {self.sl(sid)} from {self.ct(src)} to {self.ct(dst)}"
        on = self.canvases_of(sid)
        if src not in on:
            if dst in on:
                return Step(i, "move", summary, noop=f"already on {self.ct(dst)} and not on {self.ct(src)}")
            raise _Skip(IssueCode.NOT_ON_CANVAS, f"snippet {sid[:8]} is not on {self.ct(src)}")
        self.order[src].remove(sid)
        details = []
        if dst in on:
            details.append(f"already on {self.ct(dst)}: only removed from {self.ct(src)}")
        else:
            self._place_on_top(sid, dst)
        return Step(i, "move", summary, details, run=self._transfer(sid, src, dst, "move"))

    def show_on(self, i: int, op: ShowOn) -> Step:
        self.snippet(op.snippet_id)
        sid, dst = op.snippet_id, self.canvas(op.canvas)
        summary = f"also show snippet {self.sl(sid)} on {self.ct(dst)}"
        on = self.canvases_of(sid)
        if dst in on:
            return Step(i, "show_on", summary, noop=f"already on {self.ct(dst)}")
        src = on[0]
        self._place_on_top(sid, dst)
        return Step(i, "show_on", summary, [f"stays on {', '.join(self.ct(k) for k in on)}"],
                    run=self._transfer(sid, src, dst, "show"))

    def remove_from(self, i: int, op: RemoveFrom) -> Step:
        self.snippet(op.snippet_id)
        sid, key = op.snippet_id, self.canvas(op.canvas)
        summary = f"remove snippet {self.sl(sid)} from {self.ct(key)}"
        on = self.canvases_of(sid)
        if key not in on:
            return Step(i, "remove_from", summary, noop=f"not on {self.ct(key)}")
        if on == [key]:
            raise _Skip(IssueCode.LAST_CANVAS, f"{self.ct(key)} is the only canvas of snippet {sid[:8]}; removing it would delete the snippet")
        self.order[key].remove(sid)

        def run(api: Api, ctx: ApplyContext) -> object:
            result = api.request("DELETE", f"/api/canvases/{ctx.canvas(key)}/snippets/{sid}")
            if result and result.get("snippet_deleted"):
                raise ApiError("DELETE", f"/api/canvases/{ctx.canvas(key)}/snippets/{sid}", 200, "snippet_deleted",
                               "the server deleted the snippet: it was on no other canvas")
            return result

        return Step(i, "remove_from", summary, [f"stays on {', '.join(self.ct(k) for k in on if k != key)}"], run=run)

    def set_layout(self, i: int, op: SetLayout) -> Step:
        self.snippet(op.snippet_id)
        sid, key = op.snippet_id, self.canvas(op.canvas)
        if key not in self.canvases_of(sid):
            raise _Skip(IssueCode.NOT_ON_CANVAS, f"snippet {sid[:8]} is not on {self.ct(key)}")
        if op.list_rank is not None:
            try:
                validate_rank(op.list_rank)
            except RankError as exc:
                raise _Skip(IssueCode.INVALID_RANK, f"list_rank {op.list_rank!r}: {exc}") from exc
        fields = {f: getattr(op, f) for f in LAYOUT_FIELDS if getattr(op, f) is not None}
        current = self.w.placements.get(key, {}).get(sid, {})
        before = {f: current.get(f) for f in fields}
        summary = f"set layout of snippet {self.sl(sid)} on {self.ct(key)}"
        if before == fields:
            return Step(i, "set_layout", summary, noop="layout already matches")
        details = [f"{f}: {json.dumps(before[f])} -> {json.dumps(v)}" for f, v in fields.items()]

        def run(api: Api, ctx: ApplyContext) -> object:
            r = api.request("PUT", f"/api/snippets/{sid}", query={"canvas_id": ctx.canvas(key)}, body=fields)
            return {f: r.get(f) for f in fields}

        return Step(i, "set_layout", summary, details, run=run)

    def reorder_list(self, i: int, op: ReorderList) -> Step:
        key = self.canvas(op.canvas)
        wanted = list(op.snippet_ids)
        if dupes := sorted({s for s in wanted if wanted.count(s) > 1}):
            raise _Skip(IssueCode.DUPLICATE_SNIPPET, f"listed more than once: {', '.join(dupes)}")
        current = self.order[key]
        if missing := [s for s in wanted if s not in current]:
            raise _Skip(IssueCode.NOT_ON_CANVAS, f"not on {self.ct(key)}: {', '.join(missing)}")
        new = wanted + [s for s in current if s not in wanted]
        summary = f"reorder the list of {self.ct(key)} ({len(new)} snippets, {len(wanted)} placed at the top)"
        if new == current:
            return Step(i, "reorder_list", summary, noop="the list is already in this order")
        was = {s: n for n, s in enumerate(current, start=1)}
        details = [f"{n:>3}. {self.sl(s)}" + ("" if was[s] == n else f"  (was {was[s]})") for n, s in enumerate(new, start=1)]
        self.order[key] = new

        def run(api: Api, ctx: ApplyContext) -> object:
            cid = ctx.canvas(key)
            detail = api.get(f"/api/canvases/{cid}")
            placed = {s["id"]: s for s in detail["snippets"]}
            if gone := [s for s in wanted if s not in placed]:
                raise ApiError("GET", f"/api/canvases/{cid}", 200, "not_on_canvas", f"no longer on the canvas: {', '.join(gone)}")
            full = wanted + [s for s in list_order(placed) if s not in wanted]
            ranks = n_keys_between(None, None, len(full))
            updates = [{"id": s, "list_rank": r} for s, r in zip(full, ranks, strict=True)]
            result = api.request("PATCH", "/api/snippets/batch-position", body={"canvas_id": cid, "updates": updates})
            if result.get("updated") != len(updates):
                raise ApiError("PATCH", "/api/snippets/batch-position", 200, "partial_update",
                               f"updated {result.get('updated')} of {len(updates)} placements")
            return result

        return Step(i, "reorder_list", summary, details, run=run)

    def create_canvas(self, i: int, op: CreateCanvas) -> Step:
        if op.ref in self.refs:
            raise _Skip(IssueCode.DUPLICATE_REF, f"{op.ref} is already defined")
        title = _clean_title(op.title)
        summary = f'create canvas "{title}" as {op.ref}'
        existing = [k for k, t in self.titles.items() if t == title]
        match existing:
            case [key] if not key.startswith("$"):
                self.refs[op.ref] = key
                return Step(i, "create_canvas", summary, noop=f"a canvas titled \"{title}\" exists ({key}); {op.ref} uses it")
            case [_, *_]:
                raise _Skip(IssueCode.AMBIGUOUS_CANVAS, f"canvas title {title!r} is already used; pick another title")
            case _:
                pass
        self.refs[op.ref] = op.ref
        self.titles[op.ref] = title
        self.order[op.ref] = []
        ref = op.ref

        def run(api: Api, ctx: ApplyContext) -> object:
            canvas = api.request("POST", "/api/canvases", body={"title": title})
            ctx.canvas_refs[ref] = canvas["id"]
            return {"canvas_id": canvas["id"]}

        return Step(i, "create_canvas", summary, run=run)

    def rename_canvas(self, i: int, op: RenameCanvas) -> Step:
        key, title = self.canvas(op.canvas), _clean_title(op.title)
        summary = f'rename canvas {self.ct(key)} to "{title}"'
        if self.titles[key] == title:
            return Step(i, "rename_canvas", summary, noop="it already has this title")
        self.titles[key] = title

        def run(api: Api, ctx: ApplyContext) -> object:
            api.request("PUT", f"/api/canvases/{ctx.canvas(key)}", body={"title": title})
            return None

        return Step(i, "rename_canvas", summary, run=run)

    def add_bank_word(self, i: int, op: AddBankWord) -> Step:
        text = _clean_word(op.text)
        tags = list(dict.fromkeys(self.tag(n) for n in op.tags))
        names = ", ".join(self.tag_names[t] for t in tags)
        summary = f'add word "{text}" to the word bank' + (f" tagged {names}" if tags else "")
        existing = next((w for w in self.w.words.values() if w["text"].lower() == text.lower()), None)
        have = self.bank.get(text.lower())
        if have is not None and set(tags) <= have:
            return Step(i, "add_bank_word", summary, noop="the word bank already has it" + (" with these tags" if tags else ""))
        details = [f'already in the bank as "{existing["text"]}": only links the tags'] if existing else []
        self.bank.setdefault(text.lower(), set()).update(tags)

        def run(api: Api, ctx: ApplyContext) -> object:
            return api.request("POST", "/api/word-bank", body={"words": [text], "tag_ids": [ctx.tag_ids[t] for t in tags]})

        return Step(i, "add_bank_word", summary, details, run=run)

    def edit_text(self, i: int, op: EditText) -> Step:
        s = self.snippet(op.snippet_id)
        sid = op.snippet_id
        if sid in self.edited:
            raise _Skip(IssueCode.INVALID_FIELD, f"snippet {sid[:8]} is edited by an earlier edit_text op; one per snippet")
        if s["version"] != op.base_version:
            raise _Skip(IssueCode.VERSION_CONFLICT,
                        f"snippet {sid[:8]} is at version {s['version']}, the plan is based on {op.base_version}; re-read it and rebuild the edit")
        on = self.canvases_of(sid)
        if not on:
            raise _Skip(IssueCode.NOT_ON_CANVAS, f"snippet {sid[:8]} is on no canvas at this point of the plan")
        self.edited.add(sid)
        summary = f"edit text of snippet {self.sl(sid)} (version {op.base_version})"
        if s["raw_text"] == op.raw_text:
            return Step(i, "edit_text", summary, noop="the text is already this")
        details = [*(f"- {ln}" for ln in s["raw_text"].splitlines()), *(f"+ {ln}" for ln in op.raw_text.splitlines())]
        key, text = on[0], op.raw_text

        def run(api: Api, ctx: ApplyContext) -> object:
            body = {"raw_text": text, "base_version": ctx.versions[sid]}
            r = api.request("PUT", f"/api/snippets/{sid}", query={"canvas_id": ctx.canvas(key)}, body=body)
            ctx.versions[sid] = r["version"]
            return {"version": r["version"]}

        return Step(i, "edit_text", summary, details, run=run)


@dataclass
class CheckResult:
    plan: Plan
    world: World
    steps: list[Step]
    issues: list[Issue]

    @property
    def ok(self) -> bool:
        return not self.issues

    def as_json(self) -> dict[str, object]:
        return {"ok": self.ok, "description": self.plan.description, "steps": [s.as_json() for s in self.steps],
                "issues": [i.as_json() for i in self.issues]}


def check_plan(plan: Plan, world: World) -> CheckResult:
    checker = Checker(world)
    checker.check(plan)
    return CheckResult(plan, world, checker.steps, checker.issues)


def render_check(result: CheckResult) -> str:
    changes = sum(1 for s in result.steps if s.noop is None)
    lines = [f"Plan: {result.plan.description or '(no description)'}",
             f"{len(result.plan.ops)} ops: {changes} changes, {len(result.steps) - changes} already done"
             + (f", {len(result.issues)} problems" if result.issues else ""), ""]
    for step in result.steps:
        if step.noop:
            lines.append(f"{step.index:>3}. = {step.summary}  (no change: {step.noop})")
            continue
        lines.append(f"{step.index:>3}. * {step.summary}")
        lines += [f"         {d}" for d in step.details]
    if result.issues:
        lines += ["", "Problems (nothing will be applied until they are fixed):"]
        lines += [f"{i.index:>3}. ! {i.code.value}: {i.message}" for i in result.issues]
    return "\n".join(lines)
