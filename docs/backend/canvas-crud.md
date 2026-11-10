# Canvas & Snippet CRUD

Canvas workspace management and snippet lifecycle operations. A snippet's content is shared; its
layout on each canvas is a placement, so one snippet can be on several canvases
([snippet_placements.md](../features/snippet_placements.md)).

## Canvas Service

**File:** `app/services/canvas_service.py`

### Operations

```python
async def create_canvas(db, owner_id, title: CanvasTitle) -> Canvas   # title already validated
async def list_canvases(db, owner_id) -> list[Canvas]       # Ordered by updated_at DESC
async def get_canvas(db, canvas_id, owner_id=None) -> CanvasDetail | None
async def update_canvas(db, canvas_id, owner_id, **kwargs) -> Canvas | None
async def delete_canvas(db, canvas_id, owner_id) -> bool
async def generate_share_token(db, canvas_id, owner_id) -> str | None
async def get_canvas_by_share_token(db, token) -> CanvasDetail | None
async def fork_canvas(db, canvas_id, new_owner_id) -> Canvas | None
```

### Canvas Detail

`get_canvas` returns a `CanvasDetail` (the canvas's attributes plus `snippets` and `groups`):
- `snippets`: `placements.queries.placed_on_canvas`, one query joining placement → snippet (tags by
  `selectinload`) and one for every snippet's `canvases`; each is a `PlacedSnippet` in this canvas's
  context, oldest placement first.
- `groups`: the canvas's groups with `Group.placements` loaded (`GroupResponse.snippet_ids`).

### Titles

`app/services/canvas_title.py`: `new_canvas_title` (create: stripped, empty or missing →
"Untitled Canvas") and `rename_title` (rename: stripped, empty is an error); over 255 code points is
`InvalidCanvasTitleError` → 422 `{"error": "invalid_canvas_title", "message"}`. The routes validate
before calling the service.

### Delete

`delete_canvas` locks the snippets placed on the canvas, deletes its placements, groups and the canvas,
then the snippets left with no placement, in one transaction. A snippet also on another canvas
survives there.

Tags are user-wide and not part of the canvas detail; clients load `GET /api/tags`
(see [tag_expressions.md](../features/tag_expressions.md)).

### Sharing

`generate_share_token`: Creates URL-safe token (`secrets.token_urlsafe(32)`), sets `is_public=True`.

### Forking

`fork_canvas`: Clones a public canvas. Each placed snippet's content is copied into a **new** snippet owned by the forking user (`fork_copy`), with one placement on the new canvas at the same position, size and z. Groups, tags and recordings are NOT copied. New title: `"{original.title} (fork)"`, the original cut so it fits 255 code points (`canvas_title.fork_title`). Copies keep the source snippet's `content_updated_at` (forking does not edit the words), `phonetics` and `derived_from`.

### Shared View

`get_canvas_by_share_token` is the detail view with every snippet's `canvases` limited to the shared
canvas, so the owner's other canvas titles are not exposed through the link.

## Canvas Schemas

**File:** `app/schemas/canvas.py`

| Schema | Fields |
|--------|--------|
| `CanvasCreate` | title (default: "Untitled Canvas"; stripped, empty → default, > 255 → 422 `invalid_canvas_title`) |
| `CanvasUpdate` | title? (stripped, 1-255 code points else 422 `invalid_canvas_title`), is_public?, viewport_state? |
| `CanvasResponse` | id, owner_id, title, is_public, share_token, viewport_state, timestamps |
| `CanvasDetailResponse` | extends CanvasResponse + `snippets: list[SnippetResponse]`, `groups: list[GroupResponse]` (no `tags` list since tags became user-wide) |
| `ShareResponse` | share_token |

## Canvas Routes

**File:** `app/api/canvases.py`
**Prefix:** `/api/canvases`

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| GET | `/` | Bearer | List user's canvases | 200 |
| POST | `/` | Bearer | Create canvas (title validated) | 201/422 |
| GET | `/shared/{token}` | - | Get public canvas by share token (`CanvasDetailResponse`) | 200/404 |
| GET | `/{id}` | Bearer | Get canvas with all children (`CanvasDetailResponse`) | 200/404 |
| PUT | `/{id}` | Bearer | Update canvas metadata / rename (title validated) | 200/404/422 |
| DELETE | `/{id}` | Bearer | Delete canvas: placements and groups, then snippets left with no placement | 204/404 |
| POST | `/{id}/share` | Bearer | Generate share token (`ShareResponse`) | 200/404 |
| POST | `/{id}/fork` | Bearer | Fork public canvas | 201/404 |

### Typed Responses

Every route the web and Android clients call declares a pydantic `response_model`; the committed
OpenAPI snapshot ([docs/api/openapi.json](../api/README.md)) is generated from them. The builders in
`app/api/canvases.py` turn ORM rows into those models:

```python
_canvas_response(canvas) -> CanvasResponse
_canvas_detail_response(canvas) -> CanvasDetailResponse   # snippets, groups
_snippet_response(s) -> SnippetResponse   # s: PlacedSnippet; content + this canvas's placement + canvases; grid_data, phonetics, derived_from validated from JSONB; nested SnippetTagResponse list
_group_response(g) -> GroupResponse       # includes snippet_ids (from Group.placements)
_snippet_tag_response(t) -> SnippetTagResponse   # {id, name, color}
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
  reordered stored keys, and nested snippet tags are `id, name, color` since tags became user-wide).
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
| `BatchPositionUpdate` | `canvas_id` (required), `updates: [BatchPositionItem {id, position_x?, position_y?}]` |
| `BatchPositionResult` | `updated: int` |
| `SnippetResponse` | Content fields of the snippet plus, from the placement on `canvas_id`: `placement_id`, `canvas_id`, `position_x/y`, `width/height`, `z_index`, `group_id`; `canvases: [{id, title}]` (every canvas holding it, by lower-cased title then id); `grid_data: GridDataModel | None`, `tags: list[SnippetTagResponse]` (`{id, name, color}`), `phonetics: SnippetPhonetics | None`, `derived_from: DerivedFromModel | None`, timestamps (`created_at`, `updated_at`, `content_updated_at`) as strings |
| `TransferRequest` / `TransferResponse`, `RemovePlacementResponse` | see [snippet_placements.md](../features/snippet_placements.md#api) |
| `MergeRequest` / `MergeResponse` | see [Merge](#merge) |

### Grid on Create

Without `grid_data`, `raw_text` is syllabified (`syllabify_text`). With `grid_data`, the grid is stored as-is, so rests and stress markers survive; `model_dump(exclude_unset=True)`
keeps the cells exactly as sent. Either way `phonetics` is computed from `raw_text` and the stored grid
(`compute_snippet_phonetics`; grid stress wins via overrides). A malformed grid is a 422. `group_id`, when
given, is converted to a UUID so the new placement joins that group; it must be a group of that canvas
(404 `group_not_found`). The snippet is owned by the caller and placed on the canvas in the same
transaction.

The derivation (grid, phonetics, size) lives in `app/services/snippet_content.py`
(`derive_new_content`), shared with voice takes, which create their snippets the same way
([voice_capture.md](../features/voice_capture.md)).

### Auto-Sizing on Create

When a snippet is created, dimensions are auto-calculated from grid data (the stored one, supplied or
syllabified) by `snippet_content.compute_size` (re-exported as `app.api.snippets._compute_size`):

```
width  = (max_cells + 1) * 60 + 44   (min: 200)
height = lines * 22 + 52              (min: 80)
```

### Snippet Routes

**Prefix:** `/api/snippets` (and `/api/canvases/{id}/snippets`)

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| POST | `/api/canvases/{id}/snippets` | Bearer | Create snippet + placement (syllabifies unless `grid_data` is given; auto-sizes) | 201/404/422 |
| PUT | `/api/snippets/{id}?canvas_id={cid}` | Bearer | Update: content on the snippet, layout on the `cid` placement (re-syllabifies if text changes; bumps `content_updated_at` only for a real content edit); `canvas_id` required | 200/404/422 |
| DELETE | `/api/snippets/{id}` | Bearer | Delete everywhere (all placements, recordings) | 204/404 |
| DELETE | `/api/canvases/{cid}/snippets/{sid}` | Bearer | Remove from this canvas (`{"snippet_deleted": bool}`) | 200/404 |
| PATCH | `/api/snippets/batch-position` | Bearer | Batch update positions on `canvas_id` (required) (`BatchPositionResult`) | 200/422 |
| POST | `/api/canvases/{cid}/snippets/transfer` | Bearer | Move / also show snippets on another canvas (`TransferResponse`) | 200/404/422 |
| POST | `/api/canvases/{id}/merge` | Bearer | Replace 2+ snippets on this canvas with one merged snippet (`MergeResponse`) | 200/404/422/500 |

Create and PUT return `SnippetResponse`. Placement errors use the typed
`{"detail": {"error", "message"}}` body (`placement_not_found`, `canvas_not_found`, `same_canvas`,
`group_not_found`, `invalid_canvas_title`).

### Content Edit Time

`SnippetResponse.content_updated_at` (string, same `str(datetime)` format as `updated_at`) is when the
snippet's content last changed. `updated_at` moves on every write, so clients sort "last edited" by
`content_updated_at`. Create and merge stamp it via the column's server default. On PUT the route
passes the content fields the client sent (`raw_text`, `snippet_type`, `grid_mode`, `beats_per_line`,
`grid_data`; `snippet_service.CONTENT_FIELDS`) as `content_edits`, and `update_snippet` sets it to SQL
`now()` (equal to the new `updated_at`) only if one of them differs from the stored value
(`content_edits_differ`). Position, size, z-index, group, batch-position and tag links never touch it.
Details: [snippet_content_timestamp.md](../features/snippet_content_timestamp.md).

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
- All ids must be snippets placed on `canvas_id` (a canvas of the caller); otherwise 404
  `Snippet not found` and nothing changes. Their rows are locked for the rest of the transaction.
- The snippets are ordered by canvas position and merged by `merge_service.merge_snippets`; the
  merged snippet gets the first block's position, type, grid mode and group, the union of the tags,
  phonetics computed from the merged `raw_text` + grid (grid stress wins, as on create) and a size
  from `_compute_size`.
- `replace_with_merged` inserts the merged snippet with its tag links and its placement on
  `canvas_id`, and takes the originals off the canvas, in one transaction with a single commit: an
  original also placed on other canvases loses only this placement (and keeps its recordings); one
  only on this canvas is deleted. Any database error rolls everything back and the route answers 500
  `could not store the merge; nothing changed`.
- Between the insert and the deletes the route's `reparent` hook
  (`recording_service.reassign_recordings`) moves the recordings of the originals being deleted onto
  the merged snippet, so the FK cascade does not delete them.
- `deleted_ids` lists every original removed from this canvas.

Rules and flow: [merge_blocks.md](../features/merge_blocks.md).

### Voice Takes and Recordings

`app/api/voice.py`: `POST /api/canvases/{id}/voice-takes` (multipart; new snippet or append),
`GET /api/snippets/{id}/recordings`, `GET /api/recordings/{id}/audio`, `DELETE /api/recordings/{id}`.
Append needs the snippet placed on the take's canvas, keeps existing grid lines verbatim and
syllabifies only the new lines (unlike PUT with only `raw_text`), recomputes phonetics and the size of
that canvas's placement and bumps `content_updated_at`. A new take is placed on the take's canvas. Errors carry
`{"error", "message"}` details. See [voice_capture.md](../features/voice_capture.md).

### Ownership Verification

Snippet-scoped operations check `Snippet.owner_id`; canvas-scoped ones join the placement's canvas and
check `Canvas.owner_id`. Returns 404 if the snippet or canvas doesn't belong to the current user.
