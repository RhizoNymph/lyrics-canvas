"""ORM-shaped stand-ins (SimpleNamespace) for route tests that fake the service layer."""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from types import SimpleNamespace

from app.models.snippet import GridMode, SnippetType
from app.services.nlp.syllabifier import syllabify_text
from app.services.phonetics_service import compute_snippet_phonetics

CREATED = datetime(2026, 9, 30, 12, 0, 0, 123456, tzinfo=timezone.utc)
UPDATED = datetime(2026, 9, 30, 12, 5, 0, tzinfo=timezone.utc)


def fake_tag(name: str, canvas_id: uuid.UUID, color: str = "#6366f1") -> SimpleNamespace:
    return SimpleNamespace(id=uuid.uuid4(), canvas_id=canvas_id, name=name, color=color)


def fake_snippet(canvas_id: uuid.UUID, text: str, **overrides) -> SimpleNamespace:
    grid = overrides.pop("grid_data", syllabify_text(text))
    fields = dict(
        id=uuid.uuid4(),
        canvas_id=canvas_id,
        snippet_type=SnippetType.verse,
        grid_mode=GridMode.auto,
        beats_per_line=None,
        grid_data=grid,
        raw_text=text,
        position_x=0.0,
        position_y=0.0,
        width=200.0,
        height=80.0,
        z_index=0,
        group_id=None,
        tags=[],
        phonetics=compute_snippet_phonetics(text, grid, None).model_dump(mode="json") if grid is not None else None,
        derived_from=None,
        created_at=CREATED,
        updated_at=UPDATED,
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


def fake_group(canvas_id: uuid.UUID, name: str, snippets: list) -> SimpleNamespace:
    return SimpleNamespace(
        id=uuid.uuid4(), canvas_id=canvas_id, name=name, position_x=10.0, position_y=20.5, snippets=snippets, created_at=CREATED
    )


def fake_canvas(owner_id: uuid.UUID, snippets: list, groups: list, tags: list, **overrides) -> SimpleNamespace:
    fields = dict(
        id=uuid.uuid4(),
        owner_id=owner_id,
        title="Song",
        is_public=False,
        share_token=None,
        viewport_state={"x": 1.5, "y": -2, "zoom": 1},
        created_at=CREATED,
        updated_at=UPDATED,
        snippets=snippets,
        groups=groups,
        tags=tags,
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)
