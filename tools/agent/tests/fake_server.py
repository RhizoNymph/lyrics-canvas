"""An in-memory fake of the Lyrics Canvas API routes lcx uses, served by stdlib http.server in a thread.

Shapes follow docs/api/openapi.json plus the round-3 additions (``list_rank`` on placements and
snippet responses, ``list_rank`` items in batch-position). Every request is recorded in ``requests``;
``fail_next(method, pattern, status, detail)`` makes the next matching request fail once.
"""

from __future__ import annotations

import json
import re
import threading
import uuid
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlsplit

TOKEN = "lct_" + "S3cr3tT0kenValueForTests_abcdefghijklmnopq"[:43]
NOW = "2026-11-14T10:00:00+00:00"


def uid(n: int) -> str:
    """Distinct 8-char prefixes, so short labels differ."""
    return str(uuid.UUID(f"{n:08x}-0000-4000-8000-{n:012x}"))


@dataclass
class Recorded:
    method: str
    path: str
    query: dict[str, str]
    body: Any


@dataclass
class Failure:
    method: str
    pattern: re.Pattern[str]
    status: int
    detail: Any


class ApiFailure(Exception):
    def __init__(self, status: int, detail: Any):
        super().__init__(status)
        self.status, self.detail = status, detail


@dataclass
class FakeBackend:
    canvases: list[dict[str, Any]] = field(default_factory=list)
    snippets: dict[str, dict[str, Any]] = field(default_factory=dict)
    placements: list[dict[str, Any]] = field(default_factory=list)
    tags: list[dict[str, Any]] = field(default_factory=list)
    words: list[dict[str, Any]] = field(default_factory=list)
    presets: list[dict[str, Any]] = field(default_factory=list)
    requests: list[Recorded] = field(default_factory=list)
    failures: list[Failure] = field(default_factory=list)
    auth_headers: list[str] = field(default_factory=list)
    _next_id: int = 10_000

    # -- seeding -------------------------------------------------------------------------------
    def new_id(self) -> str:
        self._next_id += 1
        return uid(self._next_id)

    def add_canvas(self, cid: str, title: str) -> str:
        self.canvases.append({"id": cid, "owner_id": uid(1), "title": title, "is_public": False, "share_token": None,
                              "viewport_state": None, "created_at": NOW, "updated_at": NOW, "version": 1})
        return cid

    def add_tag(self, tid: str, name: str) -> str:
        self.tags.append({"id": tid, "name": name, "color": "#6366f1", "snippet_count": 0, "word_count": 0,
                          "created_at": NOW, "version": 1})
        return tid

    def add_snippet(self, sid: str, text: str, canvases: list[str], *, version: int = 1, tags: list[str] = (),
                    ranks: list[str] | None = None, snippet_type: str = "verse", grid: dict | None = None,
                    score: dict | None = None, score_view: bool = False) -> str:
        self.snippets[sid] = {"id": sid, "snippet_type": snippet_type, "grid_mode": "auto", "beats_per_line": None,
                              "grid_data": grid, "raw_text": text, "score": score, "score_view": score_view and score is not None,
                              "phonetics": None, "derived_from": None,
                              "created_at": NOW, "updated_at": NOW, "content_updated_at": NOW, "version": version,
                              "tag_ids": list(tags)}
        for n, cid in enumerate(canvases):
            self._place(sid, cid, (ranks or ["a0"] * len(canvases))[n])
        return sid

    def add_word(self, wid: str, text: str, tags: list[str] = ()) -> str:
        self.words.append({"id": wid, "text": text, "tags": [self._tag_ref(t) for t in tags], "created_at": NOW, "version": 1})
        return wid

    def fail_next(self, method: str, pattern: str, status: int, detail: Any) -> None:
        self.failures.append(Failure(method, re.compile(pattern), status, detail))

    def writes(self) -> list[Recorded]:
        return [r for r in self.requests if r.method != "GET"]

    # -- helpers -------------------------------------------------------------------------------
    def _place(self, sid: str, cid: str, rank: str) -> None:
        self.placements.append({"snippet_id": sid, "canvas_id": cid, "placement_id": self.new_id(), "position_x": 0.0,
                                "position_y": 0.0, "width": 300.0, "height": 200.0, "z_index": 0, "group_id": None,
                                "list_rank": rank})

    def _tag_ref(self, tid: str) -> dict[str, Any]:
        t = next(t for t in self.tags if t["id"] == tid)
        return {"id": t["id"], "name": t["name"], "color": t["color"]}

    def _canvas(self, cid: str) -> dict[str, Any]:
        for c in self.canvases:
            if c["id"] == cid:
                return c
        raise ApiFailure(404, "Canvas not found")

    def _placement(self, sid: str, cid: str) -> dict[str, Any] | None:
        return next((p for p in self.placements if p["snippet_id"] == sid and p["canvas_id"] == cid), None)

    def _snippet_response(self, sid: str, cid: str) -> dict[str, Any]:
        s, p = self.snippets[sid], self._placement(sid, cid)
        assert p is not None
        on = [c for c in self.canvases if self._placement(sid, c["id"])]
        out = {k: v for k, v in s.items() if k != "tag_ids"}
        out.update({k: p[k] for k in ("placement_id", "canvas_id", "position_x", "position_y", "width", "height",
                                       "z_index", "group_id", "list_rank")})
        out["tags"] = [self._tag_ref(t) for t in s["tag_ids"]]
        out["canvases"] = [{"id": c["id"], "title": c["title"]} for c in on]
        return out

    def _put_score(self, s: dict[str, Any], body: dict[str, Any]) -> None:
        """The server's score save flow for a score sent without text or grid (score_backend.md):
        validate against the stored grid, store canonical JSON, rest-link the grid and text."""
        from lcxlib.score_ref import (
            ScoreInvalid, ScoreTextMismatch, apply_rest_link, changed_line_texts, check_score, score_to_json, splice_line_texts,
        )

        score = s["score"]
        if "score" in body:
            if body["score"] is None:
                score = None
            else:
                try:
                    typed = check_score(body["score"], s["grid_data"])
                except ScoreInvalid as exc:
                    raise ApiFailure(422, exc.as_dict()) from exc
                score = score_to_json(typed)
                linked = apply_rest_link(typed, s["grid_data"])
                if s["grid_data"] is not None:
                    try:
                        s["raw_text"] = splice_line_texts(s["raw_text"], changed_line_texts(s["grid_data"], linked))
                    except ScoreTextMismatch:
                        pass  # the server keeps the text
                    s["grid_data"] = linked
        view = s["score_view"] if body.get("score_view") is None else body["score_view"]
        view = bool(view) and score is not None
        if (score, view) != (s["score"], s["score_view"]):
            s["score"], s["score_view"], s["version"] = score, view, s["version"] + 1

    def _top_rank(self, cid: str) -> str:
        ranks = [p["list_rank"] for p in self.placements if p["canvas_id"] == cid]
        return "Zz" if ranks else "a0"  # good enough for a fake: "before everything"

    # -- dispatch ------------------------------------------------------------------------------
    def handle(self, method: str, path: str, query: dict[str, str], body: Any) -> tuple[int, Any]:
        self.requests.append(Recorded(method, path, query, body))
        for f in self.failures:
            if f.method == method and f.pattern.fullmatch(path):
                self.failures.remove(f)
                return f.status, {"detail": f.detail}
        try:
            return self._route(method, path, query, body)
        except ApiFailure as exc:
            return exc.status, {"detail": exc.detail}

    def _route(self, method: str, path: str, q: dict[str, str], body: Any) -> tuple[int, Any]:
        seg = path.strip("/").split("/")
        match method, seg:
            case "GET", ["api", "auth", "me"]:
                return 200, {"id": uid(1), "email": "me@example.com", "display_name": "Me", "oauth_provider": None, "created_at": NOW}
            case "GET", ["api", "canvases"]:
                return 200, self.canvases
            case "POST", ["api", "canvases"]:
                cid = self.new_id()
                self.add_canvas(cid, body.get("title") or "Untitled Canvas")
                return 201, self._canvas(cid)
            case "GET", ["api", "canvases", cid]:
                c = self._canvas(cid)
                ids = [p["snippet_id"] for p in self.placements if p["canvas_id"] == cid]
                return 200, {**c, "snippets": [self._snippet_response(s, cid) for s in ids], "groups": []}
            case "PUT", ["api", "canvases", cid]:
                c = self._canvas(cid)
                c["title"], c["version"] = body["title"], c["version"] + 1
                return 200, c
            case "GET", ["api", "tags"]:
                return 200, self.tags
            case "POST", ["api", "tags"]:
                if any(t["name"].lower() == body["name"].lower() for t in self.tags):
                    raise ApiFailure(409, {"error": "tag_name_taken", "message": "taken"})
                tid = self.add_tag(self.new_id(), body["name"])
                return 201, next(t for t in self.tags if t["id"] == tid)
            case "GET", ["api", "tag-presets"]:
                return 200, self.presets
            case "GET", ["api", "word-bank"]:
                return 200, self.words
            case "POST", ["api", "word-bank"]:
                created, existing = [], []
                for text in body["words"]:
                    w = next((w for w in self.words if w["text"].lower() == text.lower()), None)
                    if w is None:
                        wid = self.add_word(self.new_id(), text)
                        w = next(x for x in self.words if x["id"] == wid)
                        created.append(w)
                    else:
                        existing.append(w)
                    for t in body.get("tag_ids", []):
                        if all(x["id"] != t for x in w["tags"]):
                            w["tags"].append(self._tag_ref(t))
                return 201, {"created": created, "existing": existing}
            case "POST", ["api", "word-bank", "tags"]:
                out = []
                for wid in body["word_ids"]:
                    w = next((w for w in self.words if w["id"] == wid), None)
                    if w is None:
                        raise ApiFailure(404, {"error": "word_not_found", "message": wid})
                    if body["op"] == "link" and all(x["id"] != body["tag_id"] for x in w["tags"]):
                        w["tags"].append(self._tag_ref(body["tag_id"]))
                    if body["op"] == "unlink":
                        w["tags"] = [x for x in w["tags"] if x["id"] != body["tag_id"]]
                    out.append(w)
                return 200, {"words": out}
            case "GET", ["api", "search"]:
                hits = [{"snippet_id": s["id"], "snippet_type": s["snippet_type"], "content_updated_at": NOW,
                         "excerpt": s["raw_text"], "canvases": []}
                        for s in self.snippets.values() if q["q"].lower() in s["raw_text"].lower()]
                return 200, {"canvases": [], "snippets": hits}
            case "GET", ["api", "sync", "snapshot"]:
                return 200, {"server_time": NOW, "canvases": self.canvases, "placements": self.placements,
                             "snippets": list(self.snippets.values()), "tags": self.tags, "tag_presets": self.presets,
                             "word_bank": self.words}
            case "POST", ["api", "snippets", sid, "tags"]:
                s = self.snippets[sid]
                if q["tag_id"] not in s["tag_ids"]:
                    s["tag_ids"].append(q["tag_id"])
                    s["version"] += 1
                return 200, {"ok": True, "version": s["version"]}
            case "DELETE", ["api", "snippets", sid, "tags", tid]:
                s = self.snippets[sid]
                if tid in s["tag_ids"]:
                    s["tag_ids"].remove(tid)
                    s["version"] += 1
                return 200, {"ok": True, "version": s["version"]}
            case "POST", ["api", "canvases", cid, "snippets", "transfer"]:
                target = body["target_canvas_id"]
                self._canvas(cid), self._canvas(target)
                placed, already = [], []
                for sid in body["snippet_ids"]:
                    if self._placement(sid, cid) is None:
                        raise ApiFailure(404, {"error": "placement_not_found", "message": sid})
                for sid in body["snippet_ids"]:
                    if self._placement(sid, target):
                        already.append(sid)
                    else:
                        self._place(sid, target, self._top_rank(target))
                        placed.append(sid)
                    if body["mode"] == "move":
                        self.placements.remove(self._placement(sid, cid))
                return 200, {"target_canvas_id": target, "placed": placed, "already_on_target": already,
                             "removed_from_source": body["mode"] == "move"}
            case "DELETE", ["api", "canvases", cid, "snippets", sid]:
                p = self._placement(sid, cid)
                if p is None:
                    raise ApiFailure(404, {"error": "placement_not_found", "message": sid})
                self.placements.remove(p)
                gone = not any(x["snippet_id"] == sid for x in self.placements)
                if gone:
                    del self.snippets[sid]
                return 200, {"snippet_deleted": gone}
            case "PUT", ["api", "snippets", sid]:
                cid, s = q["canvas_id"], self.snippets[sid]
                p = self._placement(sid, cid)
                if p is None:
                    raise ApiFailure(404, {"error": "placement_not_found", "message": sid})
                if {"raw_text", "score", "score_view"} & set(body):
                    if body.get("base_version") is not None and body["base_version"] != s["version"]:
                        raise ApiFailure(409, {"error": "version_conflict", "message": "stale",
                                               "current": self._snippet_response(sid, cid)})
                if "raw_text" in body:
                    s["raw_text"], s["version"] = body["raw_text"], s["version"] + 1
                if "score" in body or "score_view" in body:
                    self._put_score(s, body)
                for k in ("position_x", "position_y", "width", "height", "z_index", "list_rank"):
                    if k in body:
                        p[k] = body[k]
                return 200, self._snippet_response(sid, cid)
            case "PATCH", ["api", "snippets", "batch-position"]:
                n = 0
                for u in body["updates"]:
                    p = self._placement(u["id"], body["canvas_id"])
                    if p is None:
                        continue
                    for k in ("position_x", "position_y", "list_rank"):
                        if u.get(k) is not None:
                            p[k] = u[k]
                    n += 1
                return 200, {"updated": n}
        return 404, {"detail": "Not Found"}


def make_handler(backend: FakeBackend) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args: object) -> None:
            pass

        def _serve(self) -> None:
            parts = urlsplit(self.path)
            query = {k: v[0] for k, v in parse_qs(parts.query).items()}
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length)) if length else None
            auth = self.headers.get("Authorization", "")
            backend.auth_headers.append(auth)
            if auth != f"Bearer {TOKEN}":
                status, payload = 401, {"detail": "Invalid token"}
            else:
                status, payload = backend.handle(self.command, parts.path, query, body)
            raw = b"" if status == 204 else json.dumps(payload).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = _serve

    return Handler


class FakeServer:
    def __init__(self, backend: FakeBackend):
        self.backend = backend
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(backend))
        self.url = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        self.thread = threading.Thread(target=self.httpd.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True)

    def __enter__(self) -> FakeServer:
        self.thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
