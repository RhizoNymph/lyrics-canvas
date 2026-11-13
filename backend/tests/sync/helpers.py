"""Small HTTP helpers for the sync DB tests: create entities through the API and read them back."""

from __future__ import annotations

import httpx


def ok(res: httpx.Response, status: int = 200) -> dict | list | None:
    assert res.status_code == status, res.text
    return res.json() if res.content else None


def conflict(res: httpx.Response, code: str = "version_conflict") -> dict:
    """The 409 ``detail``: ``{"error": code, "message": str, ["current": ...]}``."""
    assert res.status_code == 409, res.text
    detail = res.json()["detail"]
    assert detail["error"] == code
    assert isinstance(detail["message"], str) and detail["message"]
    return detail


async def new_canvas(c: httpx.AsyncClient, title: str = "Song") -> dict:
    return ok(await c.post("/api/canvases", json={"title": title}), 201)


async def new_snippet(c: httpx.AsyncClient, canvas_id: str, text: str = "hello world", **fields) -> dict:
    return ok(await c.post(f"/api/canvases/{canvas_id}/snippets", json={"raw_text": text, **fields}), 201)


async def new_tag(c: httpx.AsyncClient, name: str = "love", **fields) -> dict:
    return ok(await c.post("/api/tags", json={"name": name, **fields}), 201)


async def new_words(c: httpx.AsyncClient, *words, tag_ids: list[str] | None = None) -> dict:
    return ok(await c.post("/api/word-bank", json={"words": list(words), "tag_ids": tag_ids or []}), 201)


async def snippet_on(c: httpx.AsyncClient, canvas_id: str, snippet_id: str) -> dict | None:
    detail = ok(await c.get(f"/api/canvases/{canvas_id}"))
    return next((s for s in detail["snippets"] if s["id"] == snippet_id), None)


async def canvas(c: httpx.AsyncClient, canvas_id: str) -> dict:
    return ok(await c.get(f"/api/canvases/{canvas_id}"))


async def tag(c: httpx.AsyncClient, tag_id: str) -> dict | None:
    return next((t for t in ok(await c.get("/api/tags")) if t["id"] == tag_id), None)


async def word(c: httpx.AsyncClient, word_id: str) -> dict | None:
    return next((w for w in ok(await c.get("/api/word-bank")) if w["id"] == word_id), None)


async def put_snippet(c: httpx.AsyncClient, snippet_id: str, canvas_id: str, **body) -> httpx.Response:
    return await c.put(f"/api/snippets/{snippet_id}", params={"canvas_id": canvas_id}, json=body)
