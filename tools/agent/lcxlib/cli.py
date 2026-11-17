"""Command line: read commands and plan check / apply."""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from collections.abc import Mapping
from typing import Any, TextIO

from lcxlib.apply import apply_steps, render_apply
from lcxlib.checker import check_plan, render_check
from lcxlib.config import load_config
from lcxlib.errors import ExitCode, LcxError, PlanError, UsageError
from lcxlib.client import Api
from lcxlib.logs import log
from lcxlib.plan import load_plan
from lcxlib.world import World, list_order


def _compact_canvas(detail: Mapping[str, Any]) -> dict[str, Any]:
    placed = {s["id"]: s for s in detail["snippets"]}
    snippets = []
    for sid in list_order(placed):
        s = placed[sid]
        snippets.append({
            "id": s["id"], "placement_id": s["placement_id"], "type": s["snippet_type"], "text": s["raw_text"],
            "tags": [t["name"] for t in s["tags"]], "version": s["version"],
            "content_updated_at": s["content_updated_at"], "list_rank": s.get("list_rank"),
            "layout": {k: s.get(k) for k in ("position_x", "position_y", "width", "height", "z_index", "group_id")},
            "canvases": [{"id": c["id"], "title": c["title"]} for c in s["canvases"]],
        })
    return {"id": detail["id"], "title": detail["title"], "version": detail["version"],
            "updated_at": detail["updated_at"], "snippets": snippets}


def resolve_canvas_id(api: Api, name: str) -> str:
    canvases = api.get("/api/canvases")
    if any(c["id"] == name for c in canvases):
        return name
    hits = [c["id"] for c in canvases if c["title"] == name]
    match hits:
        case [cid]:
            return cid
        case []:
            raise UsageError(f"no canvas with id or title {name!r}")
        case _:
            raise UsageError(f"{len(hits)} canvases are titled {name!r}; use the id: {', '.join(hits)}")


def read_command(api: Api, args: argparse.Namespace) -> object:
    match args.command:
        case "whoami":
            return api.get("/api/auth/me")
        case "canvases":
            return api.get("/api/canvases")
        case "canvas":
            detail = api.get(f"/api/canvases/{resolve_canvas_id(api, args.canvas)}")
            return detail if args.full else _compact_canvas(detail)
        case "tags":
            return api.get("/api/tags")
        case "presets":
            return api.get("/api/tag-presets")
        case "bank":
            return api.get("/api/word-bank")
        case "search":
            return api.get("/api/search", q=args.query, limit=args.limit)
        case "dump":
            return api.get("/api/sync/snapshot")
    raise UsageError(f"unknown command {args.command}")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="lcx", description="Read and change a Lyrics Canvas account through plans.")
    p.add_argument("-v", "--verbose", action="store_true", help="log requests (key=value) to stderr")
    sub = p.add_subparsers(dest="command", required=True)

    def read(name: str, help_text: str) -> argparse.ArgumentParser:
        sp = sub.add_parser(name, help=help_text)
        sp.add_argument("--pretty", action="store_true", help="indent the JSON")
        return sp

    read("whoami", "the account the token belongs to")
    read("canvases", "all canvases")
    sp = read("canvas", "one canvas with its snippets in list order")
    sp.add_argument("canvas", help="canvas id or exact title")
    sp.add_argument("--full", action="store_true", help="the API's canvas detail unchanged")
    read("tags", "all tags")
    read("presets", "saved tag filter presets")
    read("bank", "the word bank")
    sp = read("search", "search canvas titles and snippet text across canvases")
    sp.add_argument("query")
    sp.add_argument("--limit", type=int, default=50)
    read("dump", "the whole account (GET /api/sync/snapshot)")

    plan = sub.add_parser("plan", help="check or apply a change plan").add_subparsers(dest="plan_command", required=True)
    for name, help_text in (("check", "validate and show what would change; writes nothing"),
                            ("apply", "show the changes, then apply them with --yes")):
        sp = plan.add_parser(name, help=help_text)
        sp.add_argument("plan_file", help="plan JSON file, or - for stdin")
        sp.add_argument("--json", action="store_true", help="machine-readable output")
        if name == "apply":
            sp.add_argument("--yes", action="store_true", help="really apply (only after the user approved the diff)")
    return p


def _write_json(stream: TextIO, value: object, pretty: bool) -> None:
    stream.write(json.dumps(value, indent=2 if pretty else None, ensure_ascii=False) + "\n")


def run_plan(api: Api, args: argparse.Namespace, out: TextIO) -> ExitCode:
    plan = load_plan(args.plan_file)
    result = check_plan(plan, World.from_snapshot(api.get("/api/sync/snapshot")))
    apply_now = args.plan_command == "apply" and args.yes and result.ok
    if not apply_now:
        if args.json:
            _write_json(out, {"check": result.as_json(), "applied": False}, True)
        else:
            out.write(render_check(result) + "\n")
            if result.ok and args.plan_command == "apply":
                out.write("\nNot applied: show this to the user and re-run with --yes once they approve.\n")
        return ExitCode.OK if result.ok else ExitCode.PLAN_INVALID
    applied = apply_steps(api, result)
    if args.json:
        _write_json(out, {"check": result.as_json(), "applied": True, "apply": applied.as_json()}, True)
    else:
        out.write(render_check(result) + "\n\n" + render_apply(applied) + "\n")
    return ExitCode.OK if applied.failed is None else ExitCode.APPLY_STOPPED


def main(argv: list[str] | None = None, *, env: Mapping[str, str] | None = None,
         stdout: TextIO | None = None, stderr: TextIO | None = None) -> int:
    out, err = stdout or sys.stdout, stderr or sys.stderr
    try:
        args = build_parser().parse_args(argv)
    except SystemExit as exc:  # argparse already printed the usage error
        return int(exc.code or 0) and ExitCode.USAGE
    handler = logging.StreamHandler(err)
    log.handlers[:] = [handler]
    log.setLevel(logging.DEBUG if args.verbose else logging.WARNING)
    try:
        api = Api(load_config(os.environ if env is None else env))
        if args.command == "plan":
            return run_plan(api, args, out)
        _write_json(out, read_command(api, args), args.pretty)
        return ExitCode.OK
    except PlanError as exc:
        err.write("invalid plan:\n" + "\n".join(f"  op {i.index}: {i.code.value}: {i.message}" for i in exc.issues) + "\n")
        return exc.exit_code
    except LcxError as exc:
        err.write(f"lcx: {exc}\n")
        return exc.exit_code

