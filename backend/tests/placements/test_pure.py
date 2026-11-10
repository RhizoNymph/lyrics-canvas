"""Pure parts of placements: canvas titles, transfer stacking, response wiring, error mapping."""

from __future__ import annotations

import uuid

import pytest

from app.api.placement_errors import placement_http_error
from app.models.placement import SnippetPlacement
from app.models.snippet import Snippet
from app.services.canvas_title import DEFAULT_CANVAS_TITLE, fork_title, new_canvas_title, rename_title
from app.services.placements.errors import (
    CanvasNotFoundError,
    GroupNotFoundError,
    InvalidCanvasTitleError,
    PlacementNotFoundError,
    SameCanvasError,
)
from app.services.placements.transfer import stacking_order, target_placements
from app.services.placements.view import LAYOUT_FIELDS, CanvasRef, PlacedSnippet, sort_canvas_refs
from app.services.snippet_service import placed_elsewhere, split_fields

CANVAS = uuid.uuid4()


def placed(z: int = 0, canvases: tuple[CanvasRef, ...] = (), **layout) -> PlacedSnippet:
    snippet = Snippet(id=uuid.uuid4(), owner_id=uuid.uuid4(), raw_text="x")
    placement = SnippetPlacement(id=uuid.uuid4(), snippet_id=snippet.id, canvas_id=CANVAS, z_index=z, **layout)
    return PlacedSnippet(snippet=snippet, placement=placement, canvases=canvases or (CanvasRef(CANVAS, "here"),))


# ---- titles ----


@pytest.mark.parametrize("raw, title", [("  Song ", "Song"), ("\tA\n", "A"), ("é" * 255, "é" * 255), ("x" * 255, "x" * 255)])
def test_titles_are_stripped(raw, title):
    assert rename_title(raw) == title
    assert new_canvas_title(raw) == title


@pytest.mark.parametrize("raw", ["", "   ", "\n\t"])
def test_an_empty_rename_is_invalid_but_an_empty_create_defaults(raw):
    with pytest.raises(InvalidCanvasTitleError):
        rename_title(raw)
    assert new_canvas_title(raw) == DEFAULT_CANVAS_TITLE
    assert new_canvas_title(None) == DEFAULT_CANVAS_TITLE


def test_titles_over_255_code_points_are_invalid():
    for check in (rename_title, new_canvas_title):
        with pytest.raises(InvalidCanvasTitleError):
            check("x" * 256)
        assert check("  " + "x" * 255 + "  ") == "x" * 255  # length counts after the strip


def test_fork_title_fits():
    assert fork_title("Song") == "Song (fork)"
    long = fork_title("y" * 255)
    assert len(long) == 255 and long.endswith(" (fork)")


# ---- transfer stacking ----


def test_target_placements_copy_layout_and_stack_by_source_z():
    top = placed(z=9, position_x=1.0, position_y=2.0, width=3.0, height=4.0)
    bottom = placed(z=-2, position_x=5.0, position_y=6.0, width=7.0, height=8.0)
    target = uuid.uuid4()
    made = target_placements([top, bottom], target, target_max_z=10)
    by_snippet = {p.snippet_id: p for p in made}
    assert (by_snippet[bottom.id].z_index, by_snippet[top.id].z_index) == (11, 12)
    t = by_snippet[top.id]
    assert (t.canvas_id, t.position_x, t.position_y, t.width, t.height, t.group_id) == (target, 1.0, 2.0, 3.0, 4.0, None)
    assert [p.z_index for p in target_placements([top], target, None)] == [0]


def test_equal_z_ranks_by_id():
    a, b = placed(z=1), placed(z=1)
    assert [s.id for s in stacking_order([a, b])] == sorted([a.id, b.id], key=str)


# ---- views ----


def test_placed_snippet_reads_content_from_the_snippet_and_layout_from_the_placement():
    p = placed(z=3, position_x=7.0)
    assert (p.id, p.raw_text, p.owner_id) == (p.snippet.id, "x", p.snippet.owner_id)
    assert (p.placement_id, p.canvas_id, p.position_x, p.z_index) == (p.placement.id, CANVAS, 7.0, 3)


def test_split_fields_and_layout_fields():
    assert LAYOUT_FIELDS == {"position_x", "position_y", "width", "height", "z_index", "group_id"}
    content, layout = split_fields({"raw_text": "a", "position_x": 1.0, "group_id": None, "phonetics": {}})
    assert content == {"raw_text": "a", "phonetics": {}}
    assert layout == {"position_x": 1.0, "group_id": None}


def test_canvas_refs_sort_by_lower_title_then_id():
    ids = sorted([uuid.uuid4(), uuid.uuid4()], key=str)
    refs = [CanvasRef(ids[1], "b"), CanvasRef(uuid.uuid4(), "C"), CanvasRef(ids[0], "B"), CanvasRef(uuid.uuid4(), "a")]
    assert [r.title for r in sort_canvas_refs(refs)] == ["a", "B", "b", "C"]


def test_placed_elsewhere():
    assert not placed_elsewhere(placed())
    assert placed_elsewhere(placed(canvases=(CanvasRef(CANVAS, "here"), CanvasRef(uuid.uuid4(), "there"))))


# ---- errors ----


@pytest.mark.parametrize(
    "exc, status, code",
    [
        (InvalidCanvasTitleError("t"), 422, "invalid_canvas_title"),
        (PlacementNotFoundError("p"), 404, "placement_not_found"),
        (CanvasNotFoundError("c"), 404, "canvas_not_found"),
        (SameCanvasError("s"), 422, "same_canvas"),
        (GroupNotFoundError("g"), 404, "group_not_found"),
    ],
)
def test_errors_map_to_typed_bodies(exc, status, code):
    http = placement_http_error(exc)
    assert http.status_code == status
    assert http.detail == {"error": code, "message": exc.message}
