# Merge Blocks

Select two or more snippets on the canvas and merge them into one, either vertically (stacked, line after
line) or horizontally (joined, line beside line). The originals are replaced by a single merged snippet at
the first block's position. Blocks combine in canvas-position order, never selection order.

The merge runs on the server (`POST /api/canvases/{canvas_id}/merge`) so the web and Android clients share
one implementation; the client only sends the selected ids and the mode and swaps in the result.

## Scope

- Merge any multi-selection (2+) of snippets on the loaded canvas, from the context menu, the toolbar, or
  the keyboard (Ctrl/Cmd+Shift+V vertical, Ctrl/Cmd+Shift+H horizontal).
- The merged snippet keeps the originals' beat grids as they are: rests, stress markers, cell text and
  extra cell keys are carried over, not re-syllabified. Phonetics are recomputed from the merged text and
  grid.
- Best effort when syllable counts differ (see [Best-effort rules](#best-effort-rules)).
- Tags are the union of the originals' tags; the merged snippet takes the first block's type, grid mode,
  position and group.
- All or nothing: create, tag links and deletes are one database transaction.
- One undo step for the whole merge (local state only).

## Non-scope

- Splitting a snippet back into blocks (undo restores the local state only; the server-side originals are
  gone once the merge commits).
- Merging across canvases, or merging groups.
- Any alignment smarter than padding with rests: no beat-matching, no stress-aware alignment.
- Client-side merge logic. The former `frontend/src/lib/merge/{order,grid,mergeVertical,mergeHorizontal}.ts`
  were ported to `backend/app/services/merge_service.py` (with their test cases) and deleted.

## API

```jsonc
// POST /api/canvases/{canvas_id}/merge   (Bearer)
{ "snippet_ids": ["uuid", "uuid"], "mode": "vertical" | "horizontal" }
// 200
{ "snippet": SnippetResponse, "deleted_ids": ["uuid", ...] }
```

| Status | When |
|---|---|
| 200 | merged; `deleted_ids` = the originals in merge order |
| 404 `Snippet not found` | any id is not a snippet of this canvas owned by the caller (or the canvas is not the caller's) |
| 422 | fewer than two distinct ids, an id that is not a UUID, a missing or unknown `mode` |
| 500 `could not store the merge; nothing changed` | the transaction failed and was rolled back |

Repeated ids are de-duplicated (first occurrence kept) before the two-id minimum is checked.

## Position order

| Mode | Primary key | Secondary key | Tie-break |
|------|-------------|---------------|-----------|
| vertical | `position_y` ascending (top first) | `position_x` ascending | `id` |
| horizontal | `position_x` ascending (left first) | `position_y` ascending | `id` |

`merge_service.order_by_position(sources, mode)` is the single implementation.

## Merge rules

### Vertical (`merge_vertical`)

- `grid_data.lines` = every line of every snippet, in order.
- `raw_text` = the lines' `raw_text` joined with `\n`.
- Cells are deep-copied; `syllable_count` is recomputed from non-rest cells. Line-level extra keys are
  not carried over (a merged line is `cells`, `syllable_count`, `raw_text`).

### Horizontal (`merge_horizontal`)

- Result line `i` = line `i` of each snippet in order, cells concatenated.
- `word_index` of each contributed cell is re-based by the number of words already on the result line
  (counted from the accumulated text, so words with no cells still count, matching the `text.split()`
  tokenisation used by phonetics reconciliation). Cells without an integer `word_index` (rests) are
  copied unchanged.
- Line text = the contributors' trimmed line texts joined with a single space; `raw_text` = lines joined
  with `\n`.
- A snippet with no line `i` contributes nothing to line `i`; later contributors follow the previous ones
  directly. Example: A has 3 lines, B has 1, C has 2 -> line 2 = A2 + C2, line 3 = A3.
- `syllable_count` recomputed from non-rest cells.

### Both

- `snippet_type`, `grid_mode`, `position_x/y`, `group_id` come from the first snippet in merge order.
- Tags = union of all originals' tags, first-seen order.
- A snippet with `grid_data: null` contributes its non-blank, trimmed text lines with no cells.
- Cell objects are copied, never shared with the originals.
- Phonetics = `compute_snippet_phonetics(raw_text, grid_data, None)` (grid stress wins via overrides,
  exactly as on create); `width`/`height` = `_compute_size(grid_data)`; `derived_from` is not set;
  `z_index` is the column default.

## Best-effort rules

Lines of different syllable counts are reconciled by the first snippet's grid mode (`fit_to_mode`):

| First block's `grid_mode` | Rule |
|---|---|
| `auto` | Lines stay ragged; the grid already renders lines of different lengths. `beats_per_line` is the first block's. |
| `fixed` | Every line is padded with rest cells (`{"rest": true}`) to `beats_per_line`. If any line is longer than `beats_per_line`, `beats_per_line` is raised to the longest line. Nothing is ever truncated. If the first block has no `beats_per_line`, the longest line is used (if every line is empty, `beats_per_line` is left as it was). |

The same rule applies after a horizontal join (the joined lines may be longer than the original
`beats_per_line`).

## Data / control flow

```
UI (context menu item | toolbar button | Ctrl/Cmd+Shift+V/H)
  -> canvasStore.mergeSelected(mode)
       guards: canvas loaded (no_canvas), >= 2 selected snippets that still exist locally (too_few_selected)
       canvasApi.merge(canvasId, ids, mode)       POST /api/canvases/{id}/merge { snippet_ids, mode }
  -> backend app/api/merge.py
       MergeRequest validation                    UUIDs, de-dup, >= 2 distinct, mode enum      (422)
       snippet_service.get_canvas_snippets        ids in this canvas, owned by the caller, tags loaded
       any id missing                             MergeSourcesNotFoundError                    (404)
       merge_service.merge_snippets(sources, mode)
         pure: de-dup -> order_by_position -> merge_vertical | merge_horizontal -> fit_to_mode
       grid_data = merged.grid.model_dump(mode="json")   (unset cell fields are not emitted)
       phonetics = compute_snippet_phonetics(raw_text, grid_data, None); width/height = _compute_size
       snippet_service.replace_with_merged        ONE transaction:
         add merged snippet (with union Tag rows) -> flush -> delete each original -> commit
         SQLAlchemyError -> rollback -> MergePersistError                                     (500)
       re-fetch merged snippet with tags -> MergeResponse { snippet, deleted_ids }
  <- canvasStore
       failure -> MergeResult { ok: false, error: merge_failed (status, detail, ids) }; nothing changed
       success -> pushUndoState() once (pre-merge snapshot); remove deleted_ids; insert the returned
                  snippet (server tags); selectedSnippetIds = { merged.id }; mergeError = null
  <- MergeResult { ok: true, snippet }
Toolbar renders mergeError ("Merge failed: ..."); click dismisses.
```

### Error model

Backend (`app/services/merge_service.py`): `MergeError` base with `TooFewSnippetsError` (422),
`MergeSourcesNotFoundError(missing_ids)` (404) and `MergePersistError` (500, after rollback).

Frontend `MergeError` (`frontend/src/lib/merge/types.ts`) carries `kind`, `status`, `ids`:

| kind | meaning |
|---|---|
| `no_canvas` | no canvas loaded |
| `too_few_selected` | fewer than two existing snippets selected (`ids` = the existing ones) |
| `merge_failed` | the merge request failed (`status` = HTTP status or null for a network error, `message` = the server's `detail`); nothing changed |

`mergeSelected` never throws; it returns `MergeResult` (`{ ok: true, snippet } | { ok: false, error }`)
and mirrors the error into `canvasStore.mergeError`. The former `create_failed`, `tag_failed` and
`delete_failed` kinds, the `warning` level and `MergeResult.warnings` were removed: with one server
transaction a merge either fully happens or does not happen.

## Files

### Backend

| File | Role / key exports |
|---|---|
| `backend/app/api/merge.py` | `POST /api/canvases/{canvas_id}/merge`: loads sources, merges, computes phonetics and size, maps typed errors to 404/422/500 |
| `backend/app/schemas/merge.py` | `MergeMode` (enum), `MergeRequest` (de-dup + two-distinct validator), `MergeResponse` |
| `backend/app/services/merge_service.py` | Pure: `MergeSource`, `MergedSnippet`, `order_by_position`, `text_lines`, `word_count`, `lines_of`, `count_syllables`, `with_count`, `fit_to_mode`, `union_tag_ids`, `merge_vertical`, `merge_horizontal`, `merge_snippets`; errors `MergeError`, `TooFewSnippetsError`, `MergeSourcesNotFoundError`, `MergePersistError` |
| `backend/app/services/snippet_service.py` | `get_canvas_snippets(db, canvas_id, owner_id, ids)`, `replace_with_merged(db, canvas_id, fields, tags, originals)` |
| `backend/app/schemas/snippet.py` | `FaithfulModel`, `GridCellModel`, `GridLineModel`, `GridDataModel` (extras kept, only present keys emitted), `SnippetResponse` |
| `backend/tests/test_merge_service.py` | Pure rules; cases mirror the deleted frontend `grid`, `order`, `mergeVertical`, `mergeHorizontal` tests |
| `backend/tests/test_merge_route.py` | Route with the snippet service faked: order, fields, tags, phonetics, 404/422/500, response shape |
| `backend/tests/test_snippet_service_merge.py` | Transaction: add -> flush -> deletes -> one commit; rollback on any database error |
| `backend/tests/merge_fixtures.py` | `source`, `line`, `grid`, `cells_for` builders |

### Frontend

| File | Role / key exports |
|---|---|
| `frontend/src/api/client.ts` | `canvasApi.merge(canvasId, snippetIds, mode)` |
| `frontend/src/types/index.ts` | `MergeMode`, `MergeResponse` |
| `frontend/src/lib/merge/types.ts` | `MergeError`, `MergeErrorKind`, `MergeResult` (re-exports `MergeMode`) |
| `frontend/src/lib/merge/errors.ts` | `toMergeError(err, kind, ids)` |
| `frontend/src/lib/merge/index.ts` | re-exports |
| `frontend/src/lib/merge/fixtures.ts` | test helpers (`snippet`, `line`, `cellsFor`, `tag`) |
| `frontend/src/stores/canvasStore.ts` | `mergeSelected(mode)`, `mergeError`, `clearMergeError()` |
| `frontend/src/stores/canvasStore.merge.test.ts` | store tests with `vi.mock('@/api/client')`: request, replacement, selection, undo, failures |
| `frontend/src/components/canvas/SnippetContextMenu.tsx` | "Merge vertically" / "Merge horizontally" items when 2+ selected and the right-clicked snippet is among them |
| `frontend/src/components/canvas/SnippetCard.tsx` | Right-click keeps an existing multi-selection when the card is part of it |
| `frontend/src/components/toolbar/Toolbar.tsx` | "Merge ⇣" / "Merge ⇢" buttons (disabled unless 2+ selected) and the `mergeError` notice |
| `frontend/src/components/pages/CanvasPage.tsx` | Ctrl/Cmd+Shift+V / Ctrl/Cmd+Shift+H shortcuts (skipped while typing in an input or editing a snippet) |

## Invariants and constraints

- All or nothing: the merged snippet, its tag links and the deletion of every original commit together
  in one transaction; any failure (missing id, validation, database error) leaves the canvas unchanged.
- The client changes local state only after a successful response, and then only by the server's
  answer: the returned snippet is inserted and exactly `deleted_ids` are removed.
- Exactly one undo state per successful merge, snapshotting the pre-merge snippets; none on failure.
- Merge order is canvas position (see table), never selection, request or id order (id only breaks exact
  position ties).
- Nothing is truncated: `fixed` mode raises `beats_per_line` to the longest line.
- The stored grid is the merged grid verbatim; its `word_index` values index the words of the matching
  `raw_text` line (`text.split()`), which the horizontal re-base guarantees for grids the backend produced.
- Every grid line's `raw_text` is assumed non-blank (true for backend-produced grids); phonetics line
  indexes are aligned with `raw_text.split('\n')` minus blank lines.
- Only snippets present in `canvasStore.snippets` are sent; stale selection ids are ignored client-side,
  and ids the server cannot find in the caller's canvas are a 404.
