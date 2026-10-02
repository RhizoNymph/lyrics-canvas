# Canvas & Snippet CRUD

Canvas workspace management and snippet lifecycle operations.

## Canvas Service

**File:** `app/services/canvas_service.py`

### Operations

```python
async def create_canvas(db, owner_id, title="Untitled Canvas") -> Canvas
async def list_canvases(db, owner_id) -> list[Canvas]       # Ordered by updated_at DESC
async def get_canvas(db, canvas_id, owner_id=None) -> Canvas | None
async def update_canvas(db, canvas_id, owner_id, **kwargs) -> Canvas | None
async def delete_canvas(db, canvas_id, owner_id) -> bool
async def generate_share_token(db, canvas_id, owner_id) -> str | None
async def get_canvas_by_share_token(db, token) -> Canvas | None
async def fork_canvas(db, canvas_id, new_owner_id) -> Canvas | None
```

### Eager Loading

`get_canvas` uses `selectinload` to prevent N+1 queries:
- `Canvas.snippets` -> `Snippet.tags`
- `Canvas.groups` -> `Group.snippets`
- `Canvas.tags`

### Sharing

`generate_share_token`: Creates URL-safe token (`secrets.token_urlsafe(32)`), sets `is_public=True`.

### Forking

`fork_canvas`: Clones a public canvas. Copies all snippets but NOT groups or tags. New title: `"{original.title} (fork)"`.

## Canvas Schemas

**File:** `app/schemas/canvas.py`

| Schema | Fields |
|--------|--------|
| `CanvasCreate` | title (default: "Untitled Canvas") |
| `CanvasUpdate` | title?, is_public?, viewport_state? |
| `CanvasResponse` | id, owner_id, title, is_public, share_token, viewport_state, timestamps |
| `CanvasDetailResponse` | extends CanvasResponse + `snippets: list[SnippetResponse]`, `groups: list[GroupResponse]`, `tags: list[TagResponse]` |
| `ShareResponse` | share_token |

## Canvas Routes

**File:** `app/api/canvases.py`
**Prefix:** `/api/canvases`

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| GET | `/` | Bearer | List user's canvases | 200 |
| POST | `/` | Bearer | Create canvas | 201 |
| GET | `/shared/{token}` | - | Get public canvas by share token (`CanvasDetailResponse`) | 200/404 |
| GET | `/{id}` | Bearer | Get canvas with all children (`CanvasDetailResponse`) | 200/404 |
| PUT | `/{id}` | Bearer | Update canvas metadata | 200/404 |
| DELETE | `/{id}` | Bearer | Delete canvas (cascades all) | 204/404 |
| POST | `/{id}/share` | Bearer | Generate share token (`ShareResponse`) | 200/404 |
| POST | `/{id}/fork` | Bearer | Fork public canvas | 201/404 |

### Typed Responses

Every route the web and Android clients call declares a pydantic `response_model`; the committed
OpenAPI snapshot ([docs/api/openapi.json](../api/README.md)) is generated from them. The builders in
`app/api/canvases.py` turn ORM rows into those models:

```python
_canvas_response(canvas) -> CanvasResponse
_canvas_detail_response(canvas) -> CanvasDetailResponse   # snippets, groups, tags
_snippet_response(s) -> SnippetResponse   # grid_data, phonetics, derived_from validated from JSONB; nested TagResponse list
_group_response(g) -> GroupResponse       # includes snippet_ids
_tag_response(t) -> TagResponse
```

Wire shapes are the ones the old dict builders produced (same field names and nesting;
`tests/test_response_shapes.py` keeps verbatim copies of the old builders and compares every route's
JSON with them):

- Datetimes stay strings, `str(created_at)` (e.g. `2026-09-30 12:00:00.123456+00:00`), not ISO `T` form.
- `snippet_type` / `grid_mode` serialize as their enum values.
- Grids are returned exactly as stored. `GridCellModel`, `GridLineModel` and `GridDataModel` derive from
  `FaithfulModel` (`app/schemas/snippet.py`): extra keys are kept and only keys that were present are
  emitted, so a rest cell stays `{"rest": true}` rather than gaining `"syllable": null` etc. Their JSON
  schema mode is pinned to validation, so each is a single OpenAPI component.
- Response models set `json_schema_serialization_defaults_required`, so every field they always emit is
  `required` in the OpenAPI output schema.
- Only JSON key order may differ from before (object keys follow model field order; JSONB already
  reordered stored keys, and nested snippet tags are now `id, canvas_id, name, color`).
- `_compute_size` returns floats, so a just-created snippet's `width`/`height` read `284.0` like a
  reloaded one (previously `284` until reload).

---

## Snippet Operations

Snippet creation, updates, and batch positioning are handled in the canvases router.

### Snippet Schemas

**File:** `app/schemas/snippet.py`

| Schema | Fields |
|--------|--------|
| `SnippetCreate` | snippet_type, grid_mode, beats_per_line?, raw_text, grid_data?, position_x/y, width/height, group_id?, derived_from? |
| `GridDataModel` | `{ lines: [{ cells: [{ syllable?, stress?, word_index?, rest? }], syllable_count, raw_text }] }`; extra keys are kept |
| `SnippetUpdate` | All fields optional; `grid_data: GridDataModel | None` (a malformed grid is a 422) |
| `BatchPositionUpdate` | `updates: [{id, position_x, position_y}]` |
| `BatchPositionResult` | `updated: int` |
| `SnippetResponse` | All model fields; `grid_data: GridDataModel | None`, `tags: list[TagResponse]`, `phonetics: SnippetPhonetics | None`, `derived_from: DerivedFromModel | None`, timestamps as strings |
| `MergeRequest` / `MergeResponse` | see [Merge](#merge) |

### Grid on Create

Without `grid_data`, `raw_text` is syllabified (`syllabify_text`). With `grid_data`, the grid is stored as-is, so rests and stress markers survive; `model_dump(exclude_unset=True)`
keeps the cells exactly as sent. Either way `phonetics` is computed from `raw_text` and the stored grid
(`compute_snippet_phonetics`; grid stress wins via overrides). A malformed grid is a 422. `group_id`, when
given, is converted to a UUID so the new snippet joins that group.

### Auto-Sizing on Create

When a snippet is created, dimensions are auto-calculated from grid data (the stored one, supplied or
syllabified):

```
width  = (max_cells + 1) * 60 + 44   (min: 200)
height = lines * 22 + 52              (min: 80)
```

### Snippet Routes

**Prefix:** `/api/snippets` (and `/api/canvases/{id}/snippets`)

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| POST | `/api/canvases/{id}/snippets` | Bearer | Create (syllabifies unless `grid_data` is given; auto-sizes) | 201/422 |
| PUT | `/api/snippets/{id}` | Bearer | Update (re-syllabifies if text changes) | 200/404 |
| DELETE | `/api/snippets/{id}` | Bearer | Delete | 204/404 |
| PATCH | `/api/snippets/batch-position` | Bearer | Batch update positions (`BatchPositionResult`) | 200 |
| POST | `/api/canvases/{id}/merge` | Bearer | Replace 2+ snippets with one merged snippet (`MergeResponse`) | 200/404/422/500 |

All of them return `SnippetResponse` except DELETE (no body), batch-position and merge.

### Merge

**Files:** `app/api/merge.py` (route), `app/schemas/merge.py` (`MergeMode`, `MergeRequest`,
`MergeResponse`), `app/services/merge_service.py` (pure merge rules, typed errors),
`app/services/snippet_service.py` (`get_canvas_snippets`, `replace_with_merged`).

```jsonc
// POST /api/canvases/{canvas_id}/merge
{ "snippet_ids": ["uuid", "uuid"], "mode": "vertical" | "horizontal" }
// 200
{ "snippet": SnippetResponse, "deleted_ids": ["uuid", ...] }   // deleted_ids in merge order
```

- `snippet_ids` are UUIDs, de-duplicated (first occurrence kept); fewer than two distinct ids, a bad
  `mode`, or a malformed id is a 422 (request validation; nothing is read).
- All ids must be snippets of `canvas_id` owned by the caller; otherwise 404 `Snippet not found` and
  nothing changes.
- The snippets are ordered by canvas position and merged by `merge_service.merge_snippets`; the
  merged snippet gets the first block's position, type, grid mode and group, the union of the tags,
  phonetics computed from the merged `raw_text` + grid (grid stress wins, as on create) and a size
  from `_compute_size`.
- `replace_with_merged` inserts the merged snippet with its tag links and deletes the originals in
  one transaction with a single commit; any database error rolls everything back and the route
  answers 500 `could not store the merge; nothing changed`.

Rules and flow: [merge_blocks.md](../features/merge_blocks.md).

### Ownership Verification

All operations verify ownership by joining Snippet -> Canvas -> User. Returns 404 if the canvas doesn't belong to the current user.
