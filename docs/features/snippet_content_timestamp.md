# Snippet Content Timestamp

`snippets.content_updated_at` records when a snippet's **content** last changed. It exists so list
views (web and Android "list mode") can sort snippets by "last edited": `updated_at` is unusable for
that because it moves on every write, including drags, resizes and batch repositioning.

## Scope

- The column, its migration and backfill.
- When it is bumped (content edits) and when it is not (layout, grouping, tags).
- Exposing it as `content_updated_at: str` on every `SnippetResponse` (canvas detail and shared canvas
  snippet lists, create, update, merge result).

## Non-scope

- Client sorting and list UI (sibling branches `feat/web-list-mode`, `feat/android-list-mode`).
- Changing `updated_at` semantics; it still moves on every write.
- An edit history or per-field timestamps.

## Contract

| Event | `content_updated_at` |
|-------|----------------------|
| Create (`POST /api/canvases/{id}/snippets`) | now (server default) |
| Merge (`POST /api/canvases/{id}/merge`), merged snippet | now (server default; not copied from the originals) |
| `PUT /api/snippets/{id}` with `raw_text`, `snippet_type`, `grid_mode`, `beats_per_line` or `grid_data` that **differs** from the stored value | now (SQL `now()`, equal to the new `updated_at`) |
| `PUT` whose content fields all equal the stored values (e.g. identical text) | unchanged |
| `PUT` with only `position_x/y`, `width/height`, `z_index`, `group_id` | unchanged |
| `PATCH /api/snippets/batch-position` | unchanged |
| Tag link/unlink, group delete (unassigns `group_id`) | unchanged |
| Fork (`POST /api/canvases/{id}/fork`) | copied from the source snippet |
| Migration of existing rows | `COALESCE(updated_at, created_at, now())` |

Wire format: `str(datetime)`, the same as `created_at`/`updated_at`
(e.g. `2026-09-30 12:00:00.123456+00:00`). Required in the OpenAPI `SnippetResponse` schema.

## Data and Control Flow

### Create and merge

Neither route sets the column. `snippet_service.create_snippet` and
`snippet_service.replace_with_merged` build a `Snippet` without it, so the INSERT takes the
`server_default now()`; the re-select after commit loads the value, and `_snippet_response` emits it.
`created_at` and `content_updated_at` of a new snippet are therefore equal (same transaction clock).

### Update (`PUT /api/snippets/{id}`)

1. `app/api/snippets.py::update_snippet` dumps the request (`exclude_none=True`) and keeps the keys in
   `snippet_service.CONTENT_FIELDS` as `content_edits`, before it derives anything.
2. It then derives the rest as before: a re-syllabified `grid_data` when only `raw_text` was sent,
   `phonetics`, and `width`/`height` when a grid is written. These derived values are **not** content
   edits, so an identical-text PUT that regenerates the grid does not bump the clock.
3. `snippet_service.update_snippet(db, id, owner, content_edits=..., **changes)` loads the owned
   snippet, evaluates `content_edits_differ(snippet, content_edits)` against the stored values
   **before** applying `changes`, applies the non-`None` changes, and if content differed assigns
   `snippet.content_updated_at = func.now()`. One commit writes everything; the `onupdate` of
   `updated_at` and this assignment both use the transaction's `now()`, so they are equal.
4. The route re-selects and returns `_snippet_response(snippet)`.

`content_edits_differ` compares with `!=`: enums compare equal to their string values, grids compare
as stored JSON dicts (the same `model_dump` shape the route writes).

### Batch position and tags

`batch_update_positions` only sets `position_x/y`; `tag_service.add_tag_to_snippet` /
`remove_tag_from_snippet` only touch `snippet_tags`. Neither references the column.

## Files

| File | Role | Key exports |
|------|------|-------------|
| `backend/app/models/snippet.py` | Column definition: `DateTime(timezone=True)`, `server_default=now()`, NOT NULL, no `onupdate` | `Snippet.content_updated_at` |
| `backend/alembic/versions/003_snippet_content_updated_at.py` | Add column, backfill, set NOT NULL; downgrade drops it | revision `003` (down `002`) |
| `backend/app/services/snippet_service.py` | Bump decision and write | `CONTENT_FIELDS`, `content_edits_differ(snippet, edits) -> bool`, `update_snippet(db, snippet_id, owner_id, *, content_edits, **kwargs)` |
| `backend/app/api/snippets.py` | Splits client-sent content fields from derived values on PUT | `update_snippet` route |
| `backend/app/services/canvas_service.py` | Fork copies the source value | `fork_canvas` |
| `backend/app/schemas/snippet.py` | Response field | `SnippetResponse.content_updated_at: str` |
| `backend/app/api/canvases.py` | Emits it | `_snippet_response` |
| `docs/api/openapi.json` | Regenerated snapshot (`uv run python -m scripts.export_openapi` from `backend/`) | |
| `backend/tests/test_snippet_content_timestamp.py` | Decision, service, route, model, fork, merge and migration (offline SQL) tests | |

## Invariants and Constraints

- `content_updated_at` is never NULL and never later than `updated_at` written by the same request.
- Only a differing value in `CONTENT_FIELDS` moves it; adding a content column means adding it to
  `CONTENT_FIELDS`.
- Derived values (re-syllabified grid, phonetics, auto-size) never count as edits on their own.
- No `onupdate` on the column: an ORM write that does not go through `update_snippet` leaves it alone.
- Deploying requires `alembic upgrade head` on existing databases; startup `create_all` does not add
  columns to existing tables.
