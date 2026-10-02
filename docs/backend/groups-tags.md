# Groups & Tags

Group containers and tag labels for organizing snippets.

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

## Tag Service

**File:** `app/services/tag_service.py`

### Operations

```python
async def create_tag(db, canvas_id, owner_id, name, color="#6366f1") -> Tag | None
async def update_tag(db, tag_id, owner_id, name=None, color=None) -> Tag | None
async def delete_tag(db, tag_id, owner_id) -> bool
async def add_tag_to_snippet(db, snippet_id, tag_id, owner_id) -> bool
async def remove_tag_from_snippet(db, snippet_id, tag_id, owner_id) -> bool
```

### Constraints

- Tag names are unique per canvas (`UniqueConstraint("canvas_id", "name")`)
- Default color: `#6366f1` (indigo)

### Tag-Snippet Association

Managed via the `snippet_tags` junction table with CASCADE deletes:
- Deleting a tag removes all associations
- Deleting a snippet removes all associations
- `add_tag_to_snippet`: Verifies snippet ownership via Canvas join, verifies tag exists, inserts association
- `remove_tag_from_snippet`: Deletes association directly (note: does not verify ownership)

## Tag Schemas

**File:** `app/schemas/tag.py`

| Schema | Fields |
|--------|--------|
| `TagCreate` | name, color (default: "#6366f1") |
| `TagUpdate` | name?, color? |
| `TagResponse` | id, canvas_id, name, color |

## Tag Routes

**File:** `app/api/tags.py`

| Method | Path | Auth | Purpose | Status |
|--------|------|------|---------|--------|
| POST | `/api/canvases/{id}/tags` | Bearer | Create tag (`TagResponse`) | 201/404 |
| PUT | `/api/tags/{id}` | Bearer | Update tag (name/color) (`TagResponse`) | 200/404 |
| DELETE | `/api/tags/{id}` | Bearer | Delete tag | 204/404 |
| POST | `/api/snippets/{id}/tags?tag_id=` | Bearer | Add tag to snippet (`TagLinkResponse` `{"ok": true}`) | 200/404 |
| DELETE | `/api/snippets/{id}/tags/{tag_id}` | Bearer | Remove tag from snippet | 204 |

Note: `add_tag_to_snippet` uses a query parameter for `tag_id` rather than a request body.
