# Merge Blocks

Select two or more snippets on the canvas and merge them into one, either vertically (stacked, line after
line) or horizontally (joined, line beside line). The originals are replaced by a single merged snippet at
the first block's position. Blocks combine in canvas-position order, never selection order.

## Scope

- Merge any multi-selection (2+) of snippets on the loaded canvas, from the context menu, the toolbar, or
  the keyboard (Ctrl/Cmd+Shift+V vertical, Ctrl/Cmd+Shift+H horizontal).
- The merged snippet keeps the originals' beat grids as they are: rests, stress markers and cell text are
  carried over, not re-syllabified. Phonetics are recomputed by the backend from the merged text and grid.
- Best effort when syllable counts differ (see [Best-effort rules](#best-effort-rules)).
- Tags are the union of the originals' tags; the merged snippet takes the first block's type, grid mode,
  position and group.
- One undo step for the whole merge.

## Non-scope

- Splitting a snippet back into blocks (undo restores the local state only; the server-side originals are
  gone once deleted).
- Merging across canvases, or merging groups.
- Any alignment smarter than padding with rests: no beat-matching, no stress-aware alignment.
- Server-side merge endpoint. The merge is computed in the browser and uses the existing create, tag-link
  and delete routes.

## Position order

| Mode | Primary key | Secondary key | Tie-break |
|------|-------------|---------------|-----------|
| vertical | `position_y` ascending (top first) | `position_x` ascending | `id` |
| horizontal | `position_x` ascending (left first) | `position_y` ascending | `id` |

`orderByPosition(snippets, mode)` in `frontend/src/lib/merge/order.ts` is the single implementation.

## Merge rules

### Vertical (`mergeVertical`)

- `grid_data.lines` = every line of every snippet, in order.
- `raw_text` = the lines' `raw_text` joined with `\n`.
- Cells are copied; `syllable_count` is recomputed from non-rest cells.

### Horizontal (`mergeHorizontal`)

- Result line `i` = line `i` of each snippet in order, cells concatenated.
- `word_index` of each contributed cell is re-based by the number of words already on the result line
  (counted from the accumulated text, so words with no cells still count, matching the backend's
  `text.split()` tokenisation used by phonetics reconciliation).
- Line text = the contributors' line texts joined with a single space; `raw_text` = lines joined with `\n`.
- A snippet with no line `i` contributes nothing to line `i`; later contributors follow the previous ones
  directly. Example: A has 3 lines, B has 1, C has 2 -> line 2 = A2 + C2, line 3 = A3.
- `syllable_count` recomputed from non-rest cells.

### Both

- `snippet_type`, `grid_mode`, `position_x/y`, `group_id` come from the first snippet in merge order.
- `tag_ids` = union of all originals' tags, first-seen order.
- A snippet with `grid_data: null` contributes its non-blank text lines with no cells.
- Cell objects are copied, never shared with the originals.

## Best-effort rules

Lines of different syllable counts are reconciled by the first snippet's grid mode (`fitToMode` in
`grid.ts`):

| First block's `grid_mode` | Rule |
|---|---|
| `auto` | Lines stay ragged; the grid already renders lines of different lengths. `beats_per_line` is the first block's. |
| `fixed` | Every line is padded with rest cells to `beats_per_line`. If any line is longer than `beats_per_line`, `beats_per_line` is raised to the longest line. Nothing is ever truncated. If the first block has no `beats_per_line`, the longest line is used. |

The same rule applies after a horizontal join (the joined lines may be longer than the original
`beats_per_line`).

## Data / control flow

```
UI (context menu item | toolbar button | Ctrl/Cmd+Shift+V/H)
  -> canvasStore.mergeSelected(mode)
       guards: canvas loaded (no_canvas), >= 2 selected snippets that still exist (too_few_selected)
       mergeSnippets(selected, mode)             pure: orderByPosition -> mergeVertical | mergeHorizontal
       POST /api/canvases/{id}/snippets          { raw_text, grid_data, snippet_type, grid_mode,
                                                   beats_per_line, group_id, position_x, position_y }
         failure -> MergeResult { ok: false, error: create_failed }   (nothing else changed)
       backend: grid_data stored as-is, phonetics = compute_snippet_phonetics(raw_text, grid_data, None),
                width/height = _compute_size(grid_data)
       POST /api/snippets/{merged}/tags?tag_id=  for each union tag id (parallel; failures -> tag_failed warnings)
       DELETE /api/snippets/{original}           for each original (parallel; failures -> delete_failed warnings)
       pushUndoState()                           once, snapshot taken before any local change
       set snippets (originals that were deleted removed, merged inserted with the attached tags),
           selectedSnippetIds = { merged.id }, mergeError = first warning | null
  <- MergeResult { ok: true, snippet, warnings }
Toolbar renders mergeError (red for error level, amber for warning level); click dismisses.
```

### Error model

`MergeError` (`frontend/src/lib/merge/types.ts`) carries `kind`, `level`, `status`, `ids`:

| kind | level | meaning |
|---|---|---|
| `no_canvas` | error | no canvas loaded |
| `too_few_selected` | error | fewer than two existing snippets selected |
| `create_failed` | error | the create request failed; nothing changed |
| `tag_failed` | warning | the merged snippet exists; a tag could not be re-attached (`ids` = tag id) |
| `delete_failed` | warning | the merged snippet exists; an original could not be deleted and stays on the canvas (`ids` = snippet id) |

`mergeSelected` never throws; it returns `MergeResult` and mirrors the error (or first warning) into
`canvasStore.mergeError`.

## Files

### Backend

| File | Role |
|---|---|
| `backend/app/schemas/snippet.py` | `GridCellModel`, `GridLineModel`, `GridDataModel` (permissive: extra keys allowed); `SnippetCreate.grid_data: GridDataModel | None`, `SnippetCreate.group_id: str | None` |
| `backend/app/api/snippets.py` | `create_snippet`: a supplied `grid_data` is stored as-is (`model_dump(exclude_unset=True)`), phonetics computed from `raw_text` + that grid, size from it; `group_id` converted to a UUID |
| `backend/tests/test_snippet_create_route.py` | Create with/without `grid_data`, phonetics from the grid, size from the grid, extra keys kept, `group_id`, 422 on malformed grids |

### Frontend

| File | Role / key exports |
|---|---|
| `frontend/src/lib/merge/types.ts` | `MergeMode`, `NonEmptySnippets`, `MergedSnippet`, `MergeError`, `MergeErrorKind`, `MergeErrorLevel`, `MergeResult` |
| `frontend/src/lib/merge/order.ts` | `orderByPosition(snippets, mode)` |
| `frontend/src/lib/merge/grid.ts` | `textLines`, `wordCount`, `linesOf`, `countSyllables`, `withCount`, `fitToMode`, `unionTagIds` |
| `frontend/src/lib/merge/mergeVertical.ts` | `mergeVertical(snippets)` |
| `frontend/src/lib/merge/mergeHorizontal.ts` | `mergeHorizontal(snippets)` |
| `frontend/src/lib/merge/errors.ts` | `toMergeError(err, kind, ids)` |
| `frontend/src/lib/merge/index.ts` | re-exports; `mergeSnippets(snippets, mode)` = order + merge |
| `frontend/src/lib/merge/fixtures.ts` | test helpers (`snippet`, `line`, `cellsFor`, `tag`) |
| `frontend/src/lib/merge/*.test.ts` | unit tests for each module |
| `frontend/src/stores/canvasStore.ts` | `mergeSelected(mode)`, `mergeError`, `clearMergeError()` |
| `frontend/src/stores/canvasStore.merge.test.ts` | store tests with `vi.mock('@/api/client')`: order, request body, tags, deletions, undo, selection, failure paths |
| `frontend/src/api/client.ts` | `SnippetCreateBody` gains `grid_mode`, `beats_per_line`, `grid_data`, `group_id` |
| `frontend/src/components/canvas/SnippetContextMenu.tsx` | "Merge vertically" / "Merge horizontally" items when 2+ selected and the right-clicked snippet is among them |
| `frontend/src/components/canvas/SnippetCard.tsx` | Right-click keeps an existing multi-selection when the card is part of it |
| `frontend/src/components/toolbar/Toolbar.tsx` | "Merge ⇣" / "Merge ⇢" buttons (disabled unless 2+ selected) and the `mergeError` notice |
| `frontend/src/components/pages/CanvasPage.tsx` | Ctrl/Cmd+Shift+V / Ctrl/Cmd+Shift+H shortcuts (skipped while typing in an input or editing a snippet) |

## Invariants and constraints

- The originals are deleted only after the merged snippet has been created. A create failure changes
  nothing locally or remotely.
- Once the merged snippet exists it is always inserted into the store and selected, whatever happens to
  the tag and delete requests; those failures are surfaced as warnings and the undeleted originals stay on
  the canvas.
- Exactly one undo state per merge, snapshotting the pre-merge snippets.
- Merge order is canvas position (see table), never selection order or id order.
- Nothing is truncated: `fixed` mode raises `beats_per_line` to the longest line.
- The supplied grid is stored verbatim by the backend; its `word_index` values must index the words of
  the matching `raw_text` line (`text.split()`), which the horizontal re-base guarantees for grids the
  backend produced.
- Every grid line's `raw_text` is assumed non-blank (true for backend-produced grids); phonetics line
  indexes are aligned with `raw_text.split('\n')` minus blank lines.
- Only snippets present in `canvasStore.snippets` count towards the 2+ minimum; stale selection ids are
  ignored.
