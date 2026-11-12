# Sync Versions (backend)

The server side of the Android offline copy: content **versions** on snippets, canvases, tags and
word bank words, optimistic concurrency with **`base_version`** (409 `version_conflict` carrying the
server's current entity), **client-chosen ids** on creates (safe retries, 409 `id_conflict`), and
**`GET /api/sync/snapshot`**, everything of one user in one response.

The web client sends none of the new fields and needs no change: without `base_version` and `id`
every route behaves as before, apart from the extra `version` field in responses and the snippet
tag unlink answering 200 with a body instead of 204 (see [Tag links](#tag-links)).

Client: [android_offline_sync.md](./android_offline_sync.md) (branch `feat/android-offline-sync`).

## Scope

- Migration 009: `version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1)` on `snippets`,
  `canvases`, `tags` and `bank_words`, both directions.
- Bumping a version by exactly 1, in the same transaction, on every content change, and never on
  layout, viewport, sharing, placements or phonetics-only recomputes.
- `version: int` on `SnippetResponse` (so the canvas detail, merge result and voice-take result),
  `CanvasResponse` / `CanvasDetailResponse`, `TagResponse`, `BankWordResponse` and
  `TagLinkResponse`.
- `base_version` (or per-entity `base_versions`) on every content mutation of those entities.
- Optional client ids on the creates the offline client makes: canvas, snippet, tag, word bank word,
  merged snippet.
- `GET /api/sync/snapshot`.
- Typed errors `version_conflict` and `id_conflict`.

## Non-scope

- A delta feed or change log: the snapshot is the whole data set every time (fine at this size).
- Versions on placements, groups, presets, recordings or users. Layout is last-write-wins;
  presets are saved online only.
- Server-side conflict resolution or merging of edits: the server only detects and refuses.
- Voice takes, recordings, rhymes, related words, similarity, share and fork take no
  `base_version` (online-only features). A voice-take append still bumps the snippet.
- Groups in the snapshot (groups are not part of the offline copy).
- Backwards compatibility beyond "absent fields behave as before".

## Versions

Every versioned row starts at 1 (`app/models/versioned.py`: `version_column()`, the
`ck_<table>_version_positive` check, and `starts_at_initial_version`, which makes a freshly
constructed ORM object read 1 before it is flushed). A version only ever increases, by 1 per
content change, in the transaction that makes the change. Bumps are SQL expressions
(`version = version + 1`, `app/services/sync/bump.py`) so concurrent writers never lose one; the
services reload the row after commit and return the new value.

| Entity | Bumps on (only when the stored value actually changes) | Never bumps on |
|---|---|---|
| Snippet | `raw_text`, `snippet_type`, `grid_mode`, `beats_per_line`, `grid_data` (PUT, once per PUT however many fields), a voice-take append, a tag link or unlink that changes its tags | position, size, z, group (PUT layout, batch-position), placements (transfer, remove from one of several canvases), phonetics recomputed alone (a PUT resending the stored grid, the phonetics backfill), deleting or renaming a tag |
| Canvas | `title` | viewport, `is_public`, share token |
| Tag | `name`, `color` | links to snippets or words |
| Word | `text`, a tag link or unlink that changes its tags (bulk tags, or `tag_ids` on an add that links a tag to a word already in the bank) | deleting or renaming a tag |

New entities are at 1: creates, voice takes, merges (the merged snippet), forks (new canvas and
snippets), words created by an add (their `tag_ids` are part of the creation).

Deleting a tag removes its links by the FK cascade without bumping the snippets and words that
carried it, and a rename changes no snippet or word row: those are changes to the tag. A pending
offline change to such a snippet still applies; its `tags` simply no longer list the deleted tag.

A snippet's tag link/unlink keeps `updated_at` as it was (links never touched the snippet row
before); every other bump goes with an UPDATE that moves `updated_at` as before. `content_updated_at`
keeps its own rule ([snippet_content_timestamp.md](./snippet_content_timestamp.md)): tags do not
move it.

## base_version

Each content mutation accepts an optional `base_version`, the version the client's change started
from. When it is present and differs from the current version, the server answers **409** and
changes nothing:

```json
{"detail": {"error": "version_conflict", "message": "snippet … is at version 4 on the server, not 2",
            "current": { …the entity's current response object… }}}
```

When it is absent the route behaves exactly as before. When it matches, the change applies as if it
had not been sent (and bumps if it changes content). The check and the write share one transaction:
the check takes the row lock (`SELECT … FOR UPDATE`), so nothing can change the entity in between.
An entity that does not exist (or is another user's) is left to the route's usual 404. A
`base_version` below 1 is 422.

| Mutation | Where | Checked against | Checked when | `current` |
|---|---|---|---|---|
| `PUT /api/snippets/{id}?canvas_id=` | body `base_version` | the snippet | the body sets a content field (`raw_text`, `snippet_type`, `grid_mode`, `beats_per_line`, `grid_data`); a layout-only PUT ignores it | `SnippetResponse` on `canvas_id` |
| `DELETE /api/snippets/{id}` (everywhere) | `?base_version=` | the snippet | always | `SnippetResponse` on its oldest placement's canvas |
| `DELETE /api/canvases/{cid}/snippets/{sid}` (remove from canvas) | `?base_version=` | the snippet | only when this is its last placement (the removal deletes it for good) | `SnippetResponse` on `cid` |
| `POST /api/snippets/{id}/tags?tag_id=` | body `{"base_version"}` (optional body) | the **snippet** | always | `SnippetResponse` on its oldest placement's canvas |
| `DELETE /api/snippets/{id}/tags/{tag_id}` | `?base_version=` | the **snippet** | always | same |
| `PUT /api/canvases/{id}` | body `base_version` | the canvas | `title` is set; a viewport/sharing-only PUT ignores it | `CanvasResponse` |
| `DELETE /api/canvases/{id}` | `?base_version=` | the canvas | always | `CanvasResponse` |
| `PUT /api/tags/{id}` | body `base_version` | the tag | always | `TagResponse` |
| `DELETE /api/tags/{id}` | `?base_version=` | the tag | always | `TagResponse` |
| `PUT /api/word-bank/{id}` | body `base_version` | the word | always | `BankWordResponse` |
| `POST /api/word-bank/delete` | body `base_versions: {word_id: version}` | each listed word | any listed word that is the user's | **list** of `BankWordResponse`, the stale words in `base_versions` order |
| `POST /api/word-bank/tags` | body `base_versions: {word_id: version}` | each listed word | same | **list** of `BankWordResponse` |
| `POST /api/canvases/{cid}/merge` | body `base_versions: {snippet_id: version}` | each listed original | after the originals are loaded (a missing original stays 404) | **list** of `SnippetResponse` on `cid`, the stale originals in request order |

Bulk endpoints: words in `base_versions` that are not in the request, not the user's or already
gone are ignored by the check; one stale word refuses the whole request. Merge: ids in
`base_versions` that are not among the originals are ignored.

Transfer (move / also show) is layout only and takes no version.

### Deleted on the server

A mutation of an entity that is gone still answers 404, as before. For deletes and removes the
client treats 404 as "already done"; when it sent a `base_version` with a content-bearing delete,
the entity having vanished is the answer and the delete is done.

## Client ids

The offline client picks UUIDs for what it creates, so a create can be retried:

| Create | Field |
|---|---|
| `POST /api/canvases` | `id` |
| `POST /api/canvases/{cid}/snippets` | `id` |
| `POST /api/tags` | `id` |
| `POST /api/word-bank` | per word: an item of `words` is a plain string (as before) or `{"text": …, "id": …}` |
| `POST /api/canvases/{cid}/merge` | `merged_id` (the merged snippet's id) |

- No row has the id: the entity is created with it.
- A row has it, belongs to the caller and was made by an identical request: it is returned with the
  normal success status (201, or 200 for merge). Retries are safe and never duplicate.
- Otherwise (another user's row, or a different payload): **409**
  `{"detail": {"error": "id_conflict", "message": …}}`, nothing written.
- A malformed id is 422 (pydantic).

"Identical" compares what the create stored from the request, against the row as it is now:

| Create | Compared |
|---|---|
| canvas | the normalized title |
| snippet | `raw_text`, `snippet_type`, `grid_mode`, `beats_per_line`, `derived_from`, `grid_data` when the client sent one, and a placement on the requested canvas. Layout is last-write-wins and not compared |
| tag | the normalized name and color |
| word | owner and exact text; the retried word comes back in `created` |
| merge | the merged snippet is the caller's and placed on `cid`, and none of the requested `snippet_ids` is still placed on `cid`. The retry's `deleted_ids` are the requested ids in request order (the first response's merge order is not stored) |

Existing error codes stay: a tag name already used by another tag is `tag_name_taken`; a word whose
text is already in the bank under another id comes back in `existing`, as before. Inside one word
batch, duplicates (ignoring case) collapse onto the first (its id wins); one id given for two
different words is `id_conflict`.

A concurrent create with the same id loses the primary-key race and is resolved again as a retry
(`app/services/sync/client_ids.py`); a concurrent identical merge that took the originals first is
recognized after the 404 and returned.

## Snapshot

`GET /api/sync/snapshot` (bearer auth, the caller's data only):

```json
{
  "server_time": "2026-11-11 16:42:00.123456+00:00",
  "canvases":   [CanvasResponse, …],
  "placements": [{"snippet_id", "canvas_id", "placement_id", "position_x", "position_y",
                  "width", "height", "z_index", "group_id"}, …],
  "snippets":   [{"id", "snippet_type", "grid_mode", "beats_per_line", "grid_data", "raw_text",
                  "phonetics", "derived_from", "created_at", "updated_at", "content_updated_at",
                  "version", "tag_ids": [tag id, …]}, …],
  "tags":        [TagResponse, …],
  "tag_presets": [TagPresetResponse, …],
  "word_bank":   [BankWordResponse, …]
}
```

- `server_time` is the database clock of the snapshot's transaction, formatted like every other
  timestamp (`str(datetime)`, e.g. `2026-11-11 16:42:00.123456+00:00`).
- `canvases` is ordered like `GET /api/canvases` (updated last first); `placements` by placement
  creation; `snippets` by creation; `tags`, `tag_presets` and `word_bank` like their list routes.
- Each snippet appears once, however many canvases hold it. Its per-canvas fields
  (`placement_id, canvas_id, position_x, position_y, width, height, z_index, group_id`) are in
  `placements`; `canvases: [{id, title}]` is built by the client from `placements` and `canvases`.
  `tags` is replaced by `tag_ids`, sorted by lower-cased tag name then id; the tag objects are in
  `tags`.
- Every list is present (possibly empty); every field is present (nullable ones as `null`).
- One REPEATABLE READ, READ ONLY transaction, so the tables agree with each other; one query per
  table whatever the data size (canvases, placements, snippets, snippet tag links, tags with counts,
  presets, words, word tag links).

## Errors

| Code | Status | Body | Raised by |
|---|---|---|---|
| `version_conflict` | 409 | `{"error", "message", "current"}`; `current` is one response object, or a list for the bulk word endpoints and merge | `app/services/sync/versions.py` |
| `id_conflict` | 409 | `{"error", "message"}` | `app/services/sync/client_ids.py`, `word_bank/service.add_words` |

The other errors of these routes are unchanged (`placement_not_found`, `tag_name_taken`,
`word_exists`, …). Routes whose 409 can be either kind declare both bodies in OpenAPI (`anyOf`).

## Data and control flow

```
client ──PUT /api/snippets/{id}?canvas_id {raw_text, base_version}──▶ api/snippets.update_snippet
  ├─ versions.has_content(body) and base_version?  ─▶ versions.check_placed_snippet
  │      placed_snippet(lock=True) ─ version != base ─▶ VersionConflictError(placed)
  │                                                   └▶ sync_errors.version_conflict_http_error(exc, _snippet_response) ─▶ 409
  ├─ derive grid / phonetics (unchanged)
  └─ snippet_service.update_snippet ─▶ apply_update: content differs ─▶ content_updated_at = now(), bump(snippet)
         commit (lock released) ─▶ placed_by_placement_id (fresh version) ─▶ 200 SnippetResponse{version}

client ──POST /api/canvases {id, title}──▶ api/canvases.create_canvas
  └─ id? ─▶ client_ids.create_canvas ─ row with id? ─ same owner & title ─▶ that canvas (201)
                                       │              └ else ─▶ IdConflictError ─▶ 409 id_conflict
                                       └ none ─▶ canvas_service.create_canvas(canvas_id=id) (PK race ─▶ resolve again)

client ──GET /api/sync/snapshot──▶ api/sync.snapshot ─▶ services/sync/snapshot.load_snapshot
  commit · SET TRANSACTION REPEATABLE READ, READ ONLY · now() · one query per table ─▶ SyncSnapshotResponse
```

The `check_*` functions run in the route's session before the service call they guard, so their
row lock holds until that service commits; on a conflict nothing was written and the session's
rollback releases it. A `None` base version checks nothing and touches no row, which keeps the web
client on the old path.

## Migration 009

`backend/alembic/versions/009_content_versions.py`.

- Upgrade: per table add `version INTEGER NOT NULL DEFAULT 1` (existing rows get 1) and
  `ck_<table>_version_positive CHECK (version >= 1)`.
- Downgrade: drop the constraints and the columns; rows are kept.

## Files

| File | Role |
|---|---|
| `backend/alembic/versions/009_content_versions.py` | Migration 009 |
| `backend/app/models/versioned.py` | `version_column()`, `version_check(table)`, `starts_at_initial_version`, `INITIAL_VERSION` |
| `backend/app/models/{snippet,canvas,tag,word_bank}.py` | `version` column on `Snippet`, `Canvas`, `Tag`, `BankWord` |
| `backend/app/services/sync/errors.py` | `SyncErrorCode`, `SyncError`, `VersionConflictError[T]` (`current`), `IdConflictError` |
| `backend/app/services/sync/bump.py` | `bump(entity)`, `bump_words(db, ids)` |
| `backend/app/services/sync/versions.py` | `conflicts`, `has_content`, `check_placed_snippet`, `check_snippet`, `check_removal`, `check_originals`, `check_canvas`, `check_tag`, `check_word`, `check_words` |
| `backend/app/services/sync/client_ids.py` | `create_canvas`, `create_snippet` (+ `SnippetCreateContent`), `create_tag`, `resolve_merge` |
| `backend/app/services/sync/snapshot.py` | `load_snapshot` -> `Snapshot` (`SnapshotSnippet`) |
| `backend/app/api/sync_errors.py` | `version_conflict_http_error`, `id_conflict_http_error`, `conflict_responses` (OpenAPI 409), `base_version_query` |
| `backend/app/api/sync.py` | `GET /api/sync/snapshot` |
| `backend/app/schemas/versioning.py` | `BaseVersion` (int ≥ 1), `BaseVersions` (`{uuid: BaseVersion}`), `ClientId` |
| `backend/app/schemas/sync.py` | `SyncPlacement`, `SyncSnippet`, `SyncSnapshotResponse` |
| `backend/app/schemas/sync_errors.py` | `IdConflictResponse` and one `*VersionConflictResponse` per entity (single and list) |
| `backend/app/services/snippet_service.py` | bump in `apply_update`; `create_snippet(snippet_id=)`, `replace_with_merged(merged_id=)` |
| `backend/app/services/recording_service.py` | bump on voice-take append |
| `backend/app/services/canvas_service.py` | bump on title change; `create_canvas(canvas_id=)`; `CanvasDetail.version` |
| `backend/app/services/tag_service.py` | bump on name/color; link/unlink bump the snippet and return its version; `create_tag(tag_id=)`; `summary` |
| `backend/app/services/word_bank/service.py` | `NewWord`, client ids in `add_words`, bumps on rename / link / unlink, `words_by_ids` |
| `backend/app/services/placements/queries.py` | `first_placed` (a snippet on its oldest placement) |
| `backend/app/services/placements/view.py` | `PlacedSnippet.version` |
| `backend/app/api/{canvases,snippets,placements,tags,word_bank,merge}.py` | the `base_version` checks, client-id branches and 409 mapping |
| `backend/tests/sync/` | migration, bump, `base_version`, client id, snapshot and pure tests |

## Invariants and constraints

- `version >= 1` always (check constraint), starts at 1, and only increases, by exactly 1 per
  content change, in the transaction of that change.
- Layout, viewport, sharing, placements and derived phonetics never change a version.
- A 409 (`version_conflict` or `id_conflict`) means nothing was written.
- Without `base_version` / `id` every route behaves as before (the web client is unchanged).
- A version check and the write it guards hold the same row lock; no other write can interleave.
- Creates with a client id are idempotent: an identical retry returns the existing entity with the
  normal status and never creates a second one.
- The snapshot reads one consistent view, the caller's rows only, with a fixed number of queries.
