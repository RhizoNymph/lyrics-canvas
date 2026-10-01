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
| `CanvasDetailResponse` | extends CanvasResponse + snippets[], groups[], tags[] |

## Canvas Routes

**File:** `app/api/canvases.py`
**Prefix:** `/api/canvases`

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| GET | `/` | Bearer | List user's canvases | 200 |
| POST | `/` | Bearer | Create canvas | 201 |
| GET | `/shared/{token}` | - | Get public canvas by share token | 200/404 |
| GET | `/{id}` | Bearer | Get canvas with all children | 200/404 |
| PUT | `/{id}` | Bearer | Update canvas metadata | 200/404 |
| DELETE | `/{id}` | Bearer | Delete canvas (cascades all) | 204/404 |
| POST | `/{id}/share` | Bearer | Generate share token | 200/404 |
| POST | `/{id}/fork` | Bearer | Fork public canvas | 201/404 |

### Response Helpers

```python
_canvas_response(canvas) -> CanvasResponse
_snippet_response(s) -> dict     # Includes nested tags
_group_response(g) -> dict       # Includes snippet_ids
_tag_response(t) -> dict
```

---

## Snippet Operations

Snippet creation, updates, and batch positioning are handled in the canvases router.

### Snippet Schemas

**File:** `app/schemas/snippet.py`

| Schema | Fields |
|--------|--------|
| `SnippetCreate` | snippet_type, grid_mode, beats_per_line?, raw_text, grid_data?, position_x/y, width/height, group_id?, derived_from? |
| `GridDataModel` | `{ lines: [{ cells: [{ syllable?, stress?, word_index?, rest? }], syllable_count, raw_text }] }`; extra keys are kept |
| `SnippetUpdate` | All fields optional |
| `BatchPositionUpdate` | `updates: [{id, position_x, position_y}]` |
| `SnippetResponse` | All model fields + tags[], timestamps |

### Grid on Create

Without `grid_data`, `raw_text` is syllabified (`syllabify_text`). With `grid_data` (block merging sends the
merged grid), the grid is stored as-is, so rests and stress markers survive; `model_dump(exclude_unset=True)`
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
| PATCH | `/api/snippets/batch-position` | Bearer | Batch update positions | 200 |

### Ownership Verification

All operations verify ownership by joining Snippet -> Canvas -> User. Returns 404 if the canvas doesn't belong to the current user.
