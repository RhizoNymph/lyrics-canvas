# Groups & Tags

Group containers, user-wide tag labels and saved tag-expression presets for organizing snippets.

## Group Service

**File:** `app/services/group_service.py`

### Operations

```python
async def create_group(db, canvas_id, owner_id, name, **kwargs) -> Group | None
async def get_group(db, group_id) -> Group | None              # Eager-loads snippets
async def update_group(db, group_id, owner_id, **kwargs) -> Group | None
async def delete_group(db, group_id, owner_id) -> bool         # Unassigns snippets
```

### Ownership

All operations verify ownership by joining `Group -> Canvas -> User`. Returns None/False if ownership check fails.

### Delete Behavior

Deleting a group sets `group_id = None` on all contained snippets (unassign). Does NOT delete the snippets themselves.

## Group Schemas

**File:** `app/schemas/group.py`

| Schema | Fields |
|--------|--------|
| `GroupCreate` | name, position_x=0, position_y=0 |
| `GroupUpdate` | name?, position_x?, position_y? |
| `GroupResponse` | id, canvas_id, name, position_x/y, snippet_ids[], created_at |

## Group Routes

**File:** `app/api/groups.py`

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| POST | `/api/canvases/{id}/groups` | Bearer | Create group (`GroupResponse`) | 201/404 |
| PUT | `/api/groups/{id}` | Bearer | Update group (`GroupResponse`) | 200/404 |
| DELETE | `/api/groups/{id}` | Bearer | Delete group | 204/404 |

---

## Tags

Tags are **user-wide**: one list per user, shared by every canvas of that user, names unique per user
ignoring case. The canonical spec (expression language, presets, error codes, migration 005) is
[docs/features/tag_expressions.md](../features/tag_expressions.md); this section summarizes the
backend modules.

### Tag Service

**File:** `app/services/tag_service.py`. Every function is owner-scoped and raises the typed errors of
`app/services/tag_errors.py` (`TagNotFoundError`, `SnippetNotFoundError`, `TagNameTakenError`,
`PresetExpressionTooLongError`); the routes map them to `{"error", "message"}` details.

```python
@dataclass(frozen=True) class TagSummary: id, name, color, snippet_count, created_at
async def list_tags(db, owner_id) -> list[TagSummary]                       # sorted by lower(name), id
async def create_tag(db, owner_id, name: TagName, color: TagColor) -> TagSummary
async def update_tag(db, tag_id, owner_id, name: TagName | None, color: TagColor | None) -> TagSummary
async def delete_tag(db, tag_id, owner_id) -> None
async def add_tag_to_snippet(db, snippet_id, tag_id, owner_id) -> None       # both must be the user's; idempotent
async def remove_tag_from_snippet(db, snippet_id, tag_id, owner_id) -> None  # both must be the user's; idempotent
```

- `update_tag` with a changed name rewrites every preset of the user through
  `tag_expr.rename_tag` in the same transaction; a rewrite longer than 500 code points rolls the whole
  update back (`PresetExpressionTooLongError`, 409).
- Values are validated before the service by `app/services/tag_values.py`: `tag_name` (trimmed,
  1-100 code points, no control characters), `tag_color` (`#rrggbb`, stored lowercase).
- Name uniqueness: a lower-name lookup, plus the `uq_tags_user_lower_name` index (an
  `IntegrityError` on commit is also `TagNameTakenError`).

### Tag Presets

**File:** `app/services/tag_preset_service.py`: `list_presets`, `create_preset`, `update_preset`,
`delete_preset` (owner-scoped; `PresetNotFoundError`, `PresetNameTakenError`). Expressions arrive
normalized from `tag_values.preset_expression` (empty -> `EmptyPresetError`, unparsable ->
`InvalidTagExpressionError` with the parse error attached).

### Tag-Snippet Association

`snippet_tags` junction table with CASCADE deletes on both FKs: deleting a tag or a snippet removes
its links. Deleting a canvas no longer deletes tags (they belong to the user). Merging snippets
unions their tag ids (`merge_service.union_tag_ids`); with user-wide tags a tag shared by the
originals is one link on the merged snippet.

### Tag Schemas

**File:** `app/schemas/tag.py`

| Schema | Fields |
|--------|--------|
| `TagCreate` | name, color (default `"#6366f1"`) |
| `TagUpdate` | name?, color? |
| `TagResponse` | id, name, color, snippet_count, created_at |
| `SnippetTagResponse` | id, name, color (embedded in `SnippetResponse.tags`) |
| `TagLinkResponse` | ok |
| `TagPresetCreate` / `TagPresetUpdate` | name, expression / name?, expression? |
| `TagPresetResponse` | id, name, expression (normalized), created_at, updated_at |
| `TagErrorResponse` | `detail: {error, message, expression_error?: {code, position, message}}` |

### Tag Routes

**Files:** `app/api/tags.py`, `app/api/tag_presets.py`, error mapping in `app/api/tag_errors.py`

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| GET | `/api/tags` | Bearer | The user's tags (`TagResponse[]`) | 200 |
| POST | `/api/tags` | Bearer | Create tag (`TagResponse`) | 201/409/422 |
| PUT | `/api/tags/{id}` | Bearer | Rename/recolor (`TagResponse`); rename rewrites presets | 200/404/409/422 |
| DELETE | `/api/tags/{id}` | Bearer | Delete tag and its links | 204/404 |
| POST | `/api/snippets/{id}/tags?tag_id=` | Bearer | Link (`TagLinkResponse` `{"ok": true}`) | 200/404 |
| DELETE | `/api/snippets/{id}/tags/{tag_id}` | Bearer | Unlink | 204/404 |
| GET | `/api/tag-presets` | Bearer | The user's presets (`TagPresetResponse[]`) | 200 |
| POST | `/api/tag-presets` | Bearer | Create preset | 201/409/422 |
| PUT | `/api/tag-presets/{id}` | Bearer | Update preset | 200/404/409/422 |
| DELETE | `/api/tag-presets/{id}` | Bearer | Delete preset | 204/404 |

`POST /api/canvases/{id}/tags` was removed, and so was the `tags` list on the canvas detail response.
`add_tag` still takes `tag_id` as a query parameter.
