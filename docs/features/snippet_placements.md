# Snippet Placements (backend)

A snippet's **content** is separated from its **layout on a canvas**. One snippet can be placed on
several canvases, each with an independent position, size, stacking order and group. Snippets can be
moved to another canvas or also shown on one. Canvas titles are validated on create and rename.

Clients: [web_snippet_placements.md](./web_snippet_placements.md),
[android_snippet_placements.md](./android_snippet_placements.md).

## Scope

- Table `snippet_placements` and `snippets.owner_id` (migration 008, both directions).
- Every snippet read/write goes through placements: canvas detail, create, PUT, batch-position,
  delete from a canvas, delete everywhere, transfer (move / show), merge, voice takes, groups, fork,
  search, similarity, t-SNE, export, shared view, rhyme notes.
- The invariant **every snippet has at least one placement**, enforced in every path that removes
  placements.
- Canvas title validation (`invalid_canvas_title`) on `POST /api/canvases` and `PUT /api/canvases/{id}`.
- Typed errors `invalid_canvas_title`, `placement_not_found`, `canvas_not_found`, `same_canvas`,
  `group_not_found`.

## Non-scope

- Duplicating a snippet as an independent copy (a user decision: there is none).
- Sharing snippets between users: a snippet is owned by one user and placed only on that user's
  canvases (a fork makes new snippets).
- Undo of transfer / remove / delete everywhere (client concern; see the client docs).
- Persisting t-SNE layouts (see [t-SNE](#canvas-scoped-readers)).
- Backwards compatibility: `canvas_id` is now required on PUT and batch-position; old clients fail
  their layout saves with 422.

## Data model

```
User 1──N Canvas 1──N SnippetPlacement N──1 Snippet N──1 User (owner_id)
                │           │
                1──N Group ─┘ (placement.group_id, a group of the placement's canvas)
```

**Shared (on `snippets`):** `raw_text`, `snippet_type`, `grid_mode`, `beats_per_line`, `grid_data`,
`phonetics`, `derived_from`, tags (`snippet_tags`), recordings (`snippet_recordings`), embeddings,
`created_at`, `updated_at`, `content_updated_at`, and `owner_id` (FK users ON DELETE CASCADE, indexed).

**Per canvas (`snippet_placements`):**

| column | type | notes |
|---|---|---|
| `id` | uuid pk | `placement_id` on the wire |
| `snippet_id` | uuid fk snippets ON DELETE CASCADE | indexed |
| `canvas_id` | uuid fk canvases ON DELETE CASCADE | indexed |
| `position_x`, `position_y` | float not null | default 0 |
| `width`, `height` | float not null | default 300 / 200 |
| `z_index` | int not null | default 0 |
| `group_id` | uuid fk groups ON DELETE SET NULL | must be a group of `canvas_id` (service-enforced) |
| `created_at` | timestamptz not null | default now() |

Unique `(snippet_id, canvas_id)` (`uq_snippet_placements_snippet_canvas`).

ORM: `app/models/placement.py` (`SnippetPlacement`); `Snippet.placements`, `Canvas.placements` and
`Group.placements` are `passive_deletes` relationships (the DB cascades); `Snippet.placements` and
`Canvas.placements` are `lazy="raise"` so nothing loads them implicitly. `Snippet.updated_at` no
longer moves on a layout change (layout is on the placement, which has no `updated_at`);
`content_updated_at` keeps its meaning.

### Ownership

- Snippet-scoped checks (tags, recordings, similarity target, rhyme anchors and notes, delete
  everywhere, PUT's grid-only phonetics lookup) use `Snippet.owner_id`.
- Canvas-scoped checks (canvas detail, create on a canvas, PUT / batch-position / remove / transfer /
  merge / voice append / search / t-SNE / export) join the placement's canvas and check
  `Canvas.owner_id`.
- A placement's canvas owner always equals the snippet owner: transfer requires both canvases to be the
  caller's and fork creates new snippets for the forker.

## Migration 008

`backend/alembic/versions/008_snippet_placements.py` (revises 007).

Upgrade:

1. Create `snippet_placements` with its indexes and unique constraint.
2. Insert one placement per snippet from its `canvas_id, position_x, position_y, width, height,
   z_index, group_id` (nulls take the column defaults; `created_at` is the snippet's). `group_id` is
   kept only when the group belongs to the snippet's canvas (the old schema did not enforce it).
3. Add `snippets.owner_id`, fill it from the canvas owner, make it NOT NULL, add the FK (ON DELETE
   CASCADE, `fk_snippets_owner_id`) and index `ix_snippets_owner_id`.
4. Drop the seven layout columns (their FKs go with them).

Downgrade:

1. Re-add the seven columns (nullable).
2. Rank each snippet's placements by `created_at, id`. Rank 1 (the oldest) fills the snippet's own
   columns and keeps its id.
3. Every other placement becomes a **new snippet row** (new uuid) with the content columns copied
   (`snippet_type, grid_mode, beats_per_line, grid_data, raw_text, phonetics, derived_from,
   created_at, updated_at, content_updated_at`), that placement's layout, and copies of the original's
   tag links. Recordings and embeddings are not copied; they stay with the original id.
4. Snippets with no placement (the invariant forbids them) are deleted: they have no canvas.
5. `canvas_id` becomes NOT NULL again with FKs `snippets_canvas_id_fkey` / `snippets_group_id_fkey`;
   `owner_id` and the table are dropped.

Tested on Postgres both ways with seeded data (two users, three canvases, a cross-canvas group, tags,
recordings, then extra placements before the downgrade): `tests/placements/test_migration_008_db.py`.

## The invariant

Every snippet has at least one placement. The paths that remove placements lock the affected snippet
rows first (`removal.lock_snippets`: `SELECT ... FOR UPDATE` in id order, so two concurrent removals of
the last two placements serialize instead of each seeing "another placement left"), then delete in the
same transaction the snippets left with none (`removal.delete_orphans`; recordings, tag links and
embeddings go by the FK cascades):

| path | what it removes |
|---|---|
| `DELETE /api/canvases/{id}` | all placements and groups of the canvas, then the canvas |
| `DELETE /api/canvases/{cid}/snippets/{sid}` | one placement |
| transfer `mode: move` | the source placements (after the target ones exist, so nothing is orphaned) |
| merge | the merge canvas's placements of the originals |

Creating a snippet (POST, voice take, merge, fork) always inserts its placement in the same
transaction. `tests/placements/db.py:snippets_without_placement` is asserted to be 0 after each path.

## API

`SnippetResponse` keeps its field names. In a canvas context `canvas_id, position_x, position_y,
width, height, z_index, group_id` come from the placement on that canvas. New fields:

- `placement_id: str`, the placement's id;
- `canvases: [{id, title}]`, every canvas holding the snippet including the current one, sorted by
  `title.lower()` (Python code-point order, not the database collation), then by id.

Exception: the shared view (`GET /api/canvases/shared/{token}`) lists only the shared canvas, so the
owner's other canvas titles are not shown to whoever holds the link.

Example, a snippet on two canvases, read from "Song":

```json
{
  "id": "0c4f5a3e-2d9b-4f7e-9a51-6b2f0e1d7c11",
  "placement_id": "9b1d2e3f-4a5b-4c6d-8e7f-0a1b2c3d4e5f",
  "canvas_id": "5e2a8c1d-7b3f-4e9a-a6d4-2c1b0f9e8d7a",
  "snippet_type": "verse",
  "grid_mode": "auto",
  "beats_per_line": null,
  "grid_data": {"lines": [{"cells": [{"syllable": "black", "stress": 1, "word_index": 0}, {"syllable": "cat", "stress": 1, "word_index": 1}], "syllable_count": 2, "raw_text": "black cat"}]},
  "raw_text": "black cat",
  "position_x": 120.0,
  "position_y": 80.0,
  "width": 224.0,
  "height": 80.0,
  "z_index": 3,
  "group_id": null,
  "tags": [],
  "phonetics": {"...": "..."},
  "derived_from": null,
  "created_at": "2026-11-09 10:00:00.000000+00:00",
  "updated_at": "2026-11-09 10:00:00.000000+00:00",
  "content_updated_at": "2026-11-09 10:00:00.000000+00:00",
  "canvases": [
    {"id": "a7c3e9f1-0b2d-4c6e-8f1a-3b5d7e9f1a2c", "title": "drafts"},
    {"id": "5e2a8c1d-7b3f-4e9a-a6d4-2c1b0f9e8d7a", "title": "Song"}
  ]
}
```

### Errors

`{"detail": {"error": <code>, "message": <text>}}` (`PlacementErrorResponse` in the OpenAPI
document), from `app/services/placements/errors.py` via `app/api/placement_errors.py`:

| code | status | when |
|---|---|---|
| `invalid_canvas_title` | 422 | title empty after strip (rename) or over 255 code points |
| `placement_not_found` | 404 | the snippet is not placed on that canvas (or the canvas / snippet is not the caller's) |
| `canvas_not_found` | 404 | transfer source or target is not the caller's canvas |
| `same_canvas` | 422 | transfer target is the source |
| `group_not_found` | 404 | `group_id` is not a group of the placement's canvas (or not a uuid) |

A missing or malformed `canvas_id` is pydantic's 422. Existing 404s (`Canvas not found`,
`Snippet not found`) keep their string details.

### Canvases

| method | path | behaviour |
|---|---|---|
| POST | `/api/canvases` | `{title}` stripped; empty or omitted → `"Untitled Canvas"`; > 255 → 422 `invalid_canvas_title` |
| GET | `/api/canvases/{id}` | the snippets placed on it (oldest placement first), each in this canvas's context; groups with `snippet_ids` from their placements |
| PUT | `/api/canvases/{id}` | rename: `title` stripped, must be 1-255 code points, else 422 `invalid_canvas_title`; `viewport_state` / `is_public` as before |
| DELETE | `/api/canvases/{id}` | 204; placements and groups go, then the snippets left with none (a shared snippet survives on its other canvases) |
| POST | `/api/canvases/{id}/fork` | see [Fork](#fork) |
| GET | `/api/canvases/shared/{token}` | as GET, `canvases` limited to the shared canvas |

### Snippets

| method | path | behaviour |
|---|---|---|
| POST | `/api/canvases/{cid}/snippets` | body unchanged; creates the snippet (owner = caller) and its placement on `cid`; `group_id` must be a group of `cid` (404 `group_not_found`) |
| PUT | `/api/snippets/{id}?canvas_id={cid}` | `canvas_id` **required**. Content fields update the shared snippet; layout fields (`position_x, position_y, width, height, z_index, group_id`) the placement on `cid`, including the size derived from a new grid. Response in `cid` context. Not placed on `cid` → 404 `placement_not_found` |
| PATCH | `/api/snippets/batch-position` | `{canvas_id, updates: [{id, position_x?, position_y?}]}`; `canvas_id` **required**, applies to every update; ids not placed there are skipped; `{updated: n}` |
| DELETE | `/api/canvases/{cid}/snippets/{sid}` | remove from this canvas: 200 `{"snippet_deleted": bool}` (true when it was the last placement and the snippet is gone, with its recordings); not placed there → 404 `placement_not_found` |
| DELETE | `/api/snippets/{id}` | delete everywhere: 204; all placements and recordings go; 404 `Snippet not found` if not the caller's |
| POST | `/api/canvases/{cid}/snippets/transfer` | see [Transfer](#transfer) |
| POST | `/api/canvases/{cid}/merge` | see [Merge](#merge) |

### Transfer

`app/services/placements/transfer.py`, route in `app/api/placements.py`.

```jsonc
// POST /api/canvases/{cid}/snippets/transfer
{ "snippet_ids": ["uuid", ...],   // 1..500 (pydantic 422 otherwise); duplicates count once
  "target_canvas_id": "uuid",
  "mode": "move" | "show" }
// 200
{ "target_canvas_id": "uuid",
  "placed": ["uuid", ...],              // got a new target placement, request order
  "already_on_target": ["uuid", ...],   // kept their target placement untouched, request order
  "removed_from_source": true }         // mode == move
```

Checks, all before the first write (all or nothing, one commit):

1. `target_canvas_id == cid` → 422 `same_canvas`.
2. Both canvases must be the caller's → 404 `canvas_not_found`.
3. The snippet rows are locked; every snippet must be placed on `cid` → 404 `placement_not_found`
   listing the missing ids.

For each snippet not yet on the target a placement is created with the same `position_x, position_y,
width, height` as on `cid` (the selection keeps its relative layout), `group_id` null, and
`z_index = (max z on the target, or -1 when it is empty) + 1 + rank`, where rank orders the newly
placed snippets by their source `z_index`, ties by id. A snippet already on the target keeps its
target placement. In `move` mode the `cid` placements of all requested snippets are then removed
(each now has its target placement, so none is orphaned; `delete_orphans` runs all the same).
Recordings, tags and content are untouched: they belong to the snippet.

### Merge

`POST /api/canvases/{cid}/merge` keeps its request and response. The originals are loaded through
their `cid` placements with their rows locked (`snippet_service.get_canvas_snippets`) and their
`canvases`. In one transaction (`snippet_service.replace_with_merged`):

- the merged snippet (owner = the originals' owner) is inserted with its tags and its placement on
  `cid` (first block's position and group, computed size, z 0);
- an original **also placed on other canvases** loses only its `cid` placement; it stays there
  unchanged and keeps its recordings;
- an original **only on `cid`** is deleted, and its recordings move to the merged snippet first
  (`reparent` = `recording_service.reassign_recordings`, called with those ids only).

`deleted_ids` lists every original taken off `cid` (merge order), whether deleted or kept elsewhere;
the clients drop them from the canvas, as before. See [merge_blocks.md](./merge_blocks.md).

### Voice takes

`POST /api/canvases/{cid}/voice-takes`: `kind: new` creates the snippet with a placement on `cid`;
`kind: append` needs the target snippet placed on `cid` (otherwise 404 `snippet_not_found`, as
before). The appended content is shared; the new size goes to the `cid` placement only. Recordings
stay attached to the snippet, whatever canvas they were taken on. See [voice_capture.md](./voice_capture.md).

### Groups

A group belongs to one canvas. Members are placements of that canvas: `GroupResponse.snippet_ids` is
the `snippet_id` of its placements, membership is set through the placement's `group_id` (POST /
PUT with `canvas_id`, checked against the canvas), and deleting a group nulls `group_id` on its
placements. The same snippet can be in a different group on each canvas. Transfer never copies a
group.

### Fork

`POST /api/canvases/{id}/fork` (public canvases): a new canvas owned by the forking user titled
`"<title> (fork)"` (the original cut so the result fits 255 code points). Each snippet placed on the
source becomes a **new** snippet owned by the forker with its content copied (`snippet_type,
grid_mode, beats_per_line, grid_data, raw_text, phonetics, derived_from, content_updated_at`) and one
placement on the new canvas at the same position, size and z. Tag links, recordings and groups are
not copied (as before; phonetics and `derived_from` are now copied too).

### Canvas-scoped readers

- **Search** (`GET /api/canvases/{id}/search`): substring match over the snippets placed on the
  canvas, now also checked against the caller (previously the owner was not checked).
- **Similarity** (`GET /api/snippets/{id}/similar`): new optional `canvas_id` query. With it, the
  other snippets placed on that canvas (the target must be placed there, else 404
  `placement_not_found`); without it, the other snippets on any canvas holding the target, each once.
- **t-SNE** (`POST /api/canvases/{id}/tsne`): positions for the snippets placed on the canvas. It
  writes nothing: both clients use it as a preview and restore the saved layout on exit, so layout
  changes are saved through PUT / batch-position with the canvas id.
- **Export** (`POST /api/canvases/{id}/export`): the canvas's placed snippets in reading order of their
  placements there (y, then x).
- **Rhyme notes** (`POST /api/nlp/rhyme-suggest` `sources: notes`, and `GET /api/nlp/rhymes`): every
  snippet the user owns, read through `owner_id`, so a snippet on several canvases counts once.

## Data and control flow

```
route (app/api/snippets.py, placements.py, canvases.py, merge.py, voice.py, nlp.py, export.py)
  │  typed request (canvas_id required where layout is touched)
  ▼
service (snippet_service, canvas_service, group_service, recording_service, placements/*)
  │  loads PlacedSnippet = (Snippet, SnippetPlacement on that canvas, canvases)
  │  via placements/queries.py (join placement → snippet → canvas, owner check, tags, canvas refs)
  │  writes content to Snippet, layout to SnippetPlacement
  │  removals: lock_snippets → delete placements → delete_orphans → commit
  ▼
_snippet_response(PlacedSnippet) → SnippetResponse (content + this placement + canvases)
```

`PlacedSnippet` exposes the old flat attribute names (`id, canvas_id, position_x, ..., tags,
content_updated_at`) plus `placement_id` and `canvases`, so the response builder and merge sources
read it like the old ORM row. `CanvasDetail` does the same for canvas detail (`snippets`, `groups`).

## Files

| file | role |
|---|---|
| `backend/alembic/versions/008_snippet_placements.py` | the migration, both directions |
| `backend/app/models/placement.py` | `SnippetPlacement`, `DEFAULT_WIDTH/HEIGHT` |
| `backend/app/models/snippet.py` | `Snippet.owner_id`, `Snippet.placements`; layout columns removed |
| `backend/app/models/canvas.py`, `group.py` | `Canvas.placements`, `Group.placements` |
| `backend/app/services/placements/errors.py` | `PlacementErrorCode`, `PlacementError` and subclasses |
| `backend/app/services/placements/view.py` | `PlacedSnippet`, `CanvasRef`, `sort_canvas_refs`, `LAYOUT_FIELDS` |
| `backend/app/services/placements/queries.py` | `owned_canvas`, `canvas_refs`, `placed_on_canvas`, `placed_snippets`, `placed_snippet`, `placed_by_placement_id`, `ensure_group_on_canvas` |
| `backend/app/services/placements/removal.py` | `lock_snippets`, `delete_orphans`, `remove_placements`, `remove_from_canvas`, `delete_everywhere` |
| `backend/app/services/placements/transfer.py` | `TransferMode`, `TransferResult`, `stacking_order`, `target_placements`, `transfer` |
| `backend/app/services/canvas_title.py` | `CanvasTitle`, `new_canvas_title`, `rename_title`, `fork_title`, `MAX_CANVAS_TITLE_LENGTH` |
| `backend/app/services/snippet_service.py` | `create_snippet`, `update_snippet` (+ pure `apply_update`), `batch_update_positions`, `delete_snippet`, `get_canvas_snippets`, `replace_with_merged`, `split_fields` |
| `backend/app/services/canvas_service.py` | `CanvasDetail`, `delete_canvas`, `get_canvas_by_share_token`, `fork_copy`, `fork_canvas` |
| `backend/app/services/group_service.py` | groups with `placements` loaded; delete nulls placement `group_id` |
| `backend/app/services/recording_service.py` | voice takes on placements, ownership via `owner_id` |
| `backend/app/schemas/placement.py` | `TransferRequest/Response`, `RemovePlacementResponse`, `PlacementErrorBody/Response` |
| `backend/app/schemas/snippet.py` | `SnippetResponse.placement_id/canvases`, `SnippetCanvasRef`, `BatchPositionUpdate.canvas_id`, `BatchPositionItem` |
| `backend/app/api/placements.py` | remove-from-canvas and transfer routes |
| `backend/app/api/placement_errors.py` | `placement_http_error`, `placement_errors` (OpenAPI) |
| `backend/tests/placements/` | migration, service, route (DB and faked) and pure tests |

## Invariants and constraints

- Every snippet has at least one placement (see [The invariant](#the-invariant)).
- At most one placement per (snippet, canvas).
- `placement.group_id` is null or a group of `placement.canvas_id`.
- A placement's canvas belongs to the snippet's owner.
- `canvases` is sorted by `title.lower()`, then id, and always includes the current canvas (except it
  is the only entry in the shared view).
- A canvas title is 1-255 code points after strip.
- Transfer and merge are all or nothing with one commit; transfer never changes an existing target
  placement.
