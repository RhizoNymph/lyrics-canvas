"""The account as read from GET /api/sync/snapshot."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any


@dataclass
class World:
    canvases: dict[str, str]  # id -> title
    snippets: dict[str, dict[str, Any]]  # id -> SyncSnippet
    placements: dict[str, dict[str, dict[str, Any]]]  # canvas id -> snippet id -> SyncPlacement
    tags: dict[str, str]  # lower(name) -> id
    tag_names: dict[str, str]  # id -> name
    words: dict[str, dict[str, Any]]  # id -> BankWordResponse

    @classmethod
    def from_snapshot(cls, snap: Mapping[str, Any]) -> World:
        placements: dict[str, dict[str, dict[str, Any]]] = {c["id"]: {} for c in snap["canvases"]}
        for p in snap["placements"]:
            placements.setdefault(p["canvas_id"], {})[p["snippet_id"]] = p
        return cls(
            canvases={c["id"]: c["title"] for c in snap["canvases"]},
            snippets={s["id"]: s for s in snap["snippets"]},
            placements=placements,
            tags={t["name"].lower(): t["id"] for t in snap["tags"]},
            tag_names={t["id"]: t["name"] for t in snap["tags"]},
            words={w["id"]: w for w in snap["word_bank"]},
        )


def list_order(placed: Mapping[str, Mapping[str, Any]]) -> list[str]:
    """Snippet ids in custom list order: ``list_rank`` ascending, then ``placement_id``."""
    return sorted(placed, key=lambda sid: (placed[sid].get("list_rank") or "", placed[sid]["placement_id"]))


def first_line(text: str, width: int = 40) -> str:
    line = next((ln.strip() for ln in text.splitlines() if ln.strip()), "")
    return line if len(line) <= width else line[: width - 1] + "…"
