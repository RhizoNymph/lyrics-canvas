# Web Snippet Placements and Canvas Rename

The web client's side of canvas rename and **snippet placements**: one snippet can be on several canvases,
each with its own position, size, stacking order and group, while text, type, grid, tags and recordings are
shared. The backend contract (data model, migration 008, endpoints, invariants) is in
[snippet_placements.md](./snippet_placements.md); this page covers only the React client.

## Scope

- **Canvas rename**: a pencil on each canvas list card (inline edit, Enter saves, Escape cancels) and a
  click-to-edit title in the canvas header. Local check (trim, 1..255 code points) before the request; the
  server's `invalid_canvas_title` (and any other failure) shows inline under the input. The list card, the
  header and every snippet's embedded `canvases` list update without a reload.
- **Canvas create**: the canvas list's create box keeps its behaviour (blank title becomes "Untitled
  Canvas"), now with the same length check and an inline error instead of an unhandled rejection.
- **Layout saves send `canvas_id`**: `PUT /api/snippets/{id}?canvas_id=` and
  `PATCH /api/snippets/batch-position {canvas_id, updates}`; there is no other snippet update path.
- **Context menu (multi-selection aware)**: "Move to canvas…" and "Also show on canvas…" open a canvas
  picker (search, newest updated first, "New canvas…" at the top which creates a canvas then acts on it);
  a toast reports the result with an **Open** action.
- **Delete**: the plain delete (menu item and the Delete/Backspace key) removes from this canvas
  (`DELETE /api/canvases/{cid}/snippets/{sid}`); it is labelled **Remove from this canvas** when any target
  is on another canvas. **Delete everywhere** (`DELETE /api/snippets/{id}`) is a separate item, offered when
  any target is shared, and asks for confirmation naming the other canvases.
- **"⧉ N" badge** on canvas cards (Pixi) and list-mode rows for a snippet on N > 1 canvases; clicking it opens
  a popover listing the canvases, each other canvas navigating there. The inline editor shows
  "Also on: A, B" with the same links (it saves first).
- **Undo/redo** rule for the non-undoable cross-canvas operations (below).

## Non-scope

- Backend endpoints, migration, merge and voice-take rules (backend branch, `snippet_placements.md`).
- Android (`android_snippet_placements.md`).
- "Duplicate as an independent copy" (not a feature, by decision).
- Undoing a transfer, a remove or a delete everywhere.
- Merge UI: unchanged. After a merge the originals disappear from this canvas as before.
- Groups: membership operations are unchanged; the client only drops removed snippets from the local
  `snippet_ids` of this canvas's groups.

## Types (`frontend/src/types/index.ts`)

- `Snippet` gains `placement_id: string` and `canvases: CanvasRef[]` (every canvas holding it, current
  included, sorted by lower(title) then id). `canvas_id` and the layout fields are this canvas's placement.
- `CanvasRef {id, title}`, `TransferMode = 'move' | 'show'`, `TransferRequest`, `TransferResponse
  {target_canvas_id, placed, already_on_target, removed_from_source}`, `RemovePlacementResponse
  {snippet_deleted}`, `PositionUpdate`.
- Errors (`lib/placements/errors.ts`): `PlacementError` (`kind`, `operation`, `status`) with
  `PlacementErrorKind = PlacementServerErrorCode | 'no_canvas' | 'nothing_selected' | 'too_many_snippets' |
  'not_found' | 'validation' | 'network' | 'server' | 'http'` and
  `PlacementServerErrorCode = 'invalid_canvas_title' | 'placement_not_found' | 'canvas_not_found' |
  'same_canvas'`. `describePlacementError` gives every kind a human message. Store actions return
  `PlacementResult<T> = {ok: true, value} | {ok: false, error}` and never throw (except `updateSnippet`,
  which rejects with a `PlacementError` as it rejected before).

## Data and control flow

### Rename

```
Canvas list card ✎ -> TitleEditor (Enter)
  -> submitCanvasTitle(raw, save): checkCanvasTitle (trim, 1..255 code points) else inline message
  -> renameCanvasIn(id, title, placementApi.renameCanvas)  PUT /api/canvases/{id} {title}
  -> ok: replaceCanvas(list, response) / error: describePlacementError inline

Header CanvasTitle click -> TitleEditor (Enter) -> canvasStore.renameCanvas(raw)
  -> local check -> placementApi.renameCanvas -> canvasTitle = response.title,
     renameCanvasRef on every snippet's `canvases` (live map and every undo/redo snapshot)
```

### Saving layout and content

`canvasStore.updateSnippet(id, data)` -> `placementApi.updateSnippet(id, canvasId, data)` (query
`canvas_id`). The response replaces the snippet only if the same canvas is still open and the snippet is
still on it (a slow save after navigating away cannot write into the next canvas). `commitPositions()` ->
`placementApi.batchPosition(canvasId, updates)`; skipped without a canvas or snippets.

### Transfer

```
SnippetContextMenu "Move to canvas…" / "Also show on canvas…"
  -> CanvasPicker: placementApi.listCanvases() -> pickerCanvases(list, current, query) (no current, newest first)
     "New canvas…" -> createCanvasFromInput(title, placementApi.createCanvas) -> pick it
  -> canvasStore.transferSnippets(targetIds, {id, title}, mode)
       local refusals: no_canvas, same_canvas, nothing_selected, too_many_snippets (> 500)
       POST /api/canvases/{cid}/snippets/transfer {snippet_ids, target_canvas_id, mode}
       failure -> nothing changes (server is atomic), error toast
       removed_from_source (move) -> dropLocally(ids)
       show -> showOnTarget: target added to `canvases` (live and in every snapshot), no history entry
  -> placementNoticeStore.show(transferNotice(...), 'info', target) -> PlacementToast with Open
```

`targetIds` is `pickerTargets(contextSnippetId, selection, snippets)`: the whole multi-selection when the
menu was opened on one of its members, else that snippet (the tag picker's rule).

### Delete

```
Menu "Delete snippet" / "Remove from this canvas", or Delete/Backspace on the selection
  -> canvasStore.removeFromCanvas(ids): DELETE /api/canvases/{cid}/snippets/{sid} per id, in parallel
Menu "Delete everywhere" -> confirm view (deleteEverywhereConfirm names the other canvases)
  -> canvasStore.deleteEverywhere(ids): DELETE /api/snippets/{id} per id, in parallel
Both -> RemovalSummary {removed, deletedForGood, failures}; successes -> dropLocally(removed);
        placement_not_found (remove) / 404 (delete everywhere) count as removed;
        other failures keep the snippet and show removalFailureNotice as an error toast
```

`deleteMenu(targets, canvasId)` picks the labels: no target shared -> "Delete snippet" / "Delete N
snippets" and no Delete everywhere; any target shared -> "Remove from this canvas" plus "Delete
everywhere". There is no confirm for the plain delete, as before.

### Badge and popover

`canvasBadge(snippet)` is `{count, label: '⧉ N'}` for more than one canvas. `SnippetCard` draws it after the
type label as a Pixi container whose `pointerdown` stops propagation (no drag, no selection) and calls
`uiStore.setCanvasesPopover({snippetId, x, y})`; `ListRow` renders `CanvasBadge` (a DOM button) the same way.
`CanvasesPopover` (mounted once in `CanvasPage`) lists `snippet.canvases`; the current canvas is marked
"(this canvas)", the others navigate to `/canvas/{id}`. Outside click or Escape closes it; opening it closes
the context menu; switching view or canvas closes it (`uiStore.leaveCanvas`, called from `CanvasPage`'s
effect cleanup on every canvas change).

### Undo / redo (the contract rule)

History is local snapshots of the whole snippet and group maps (`HistorySnapshot`). The rule:

- Content edits, creates and in-canvas layout changes keep pushing snapshots, as before.
- **Move, Remove from this canvas, Delete everywhere** (and the plain delete of a snippet only on this
  canvas, which the server now deletes for good through the same placement call): not undoable, no entry is
  pushed, and `dropSnapshotsWith(past/future, ids)` drops **every snapshot that holds one of the affected
  snippets**. The remaining snapshots never contain them, so undo/redo cannot put a snippet or placement
  back that the server no longer has here. This usually clears most of the history, which the contract
  allows; it was chosen over clearing everything because entries from before those snippets existed stay
  usable.
- **Also show on** and **rename**: nothing is removed from this canvas, so no entry is dropped and none is
  pushed; `mapSnapshotSnippets` patches the `canvases` list in every snapshot (like the tag picker's
  `updateEmbeddedTags`), so undo never brings back a stale badge.
- Merge is unchanged (one undo entry; undo restores the originals locally, as before this feature).

## Files

| File | Role | Key exports |
|---|---|---|
| `frontend/src/types/index.ts` | Snippet placement fields, transfer and remove types | `Snippet.placement_id`, `Snippet.canvases`, `CanvasRef`, `TransferMode`, `TransferRequest`, `TransferResponse`, `RemovePlacementResponse`, `PositionUpdate` |
| `frontend/src/api/placements.ts` | Typed endpoints over an injected axios instance; rejects with `PlacementError` only | `createPlacementApi`, `PlacementApi`, `SnippetUpdateBody` |
| `frontend/src/api/client.ts` | Binds `placementApi` to the app instance; `snippetApi` keeps only `create`, `canvasApi` lost `list`/`create` (now in `placementApi`) | `placementApi` |
| `frontend/src/lib/placements/errors.ts` | Typed errors and messages | `PlacementError`, `PlacementErrorKind`, `PlacementServerErrorCode`, `PlacementOperation`, `toPlacementError`, `describePlacementError`, `isPlacementServerErrorCode`, `PlacementResult` |
| `frontend/src/lib/placements/title.ts` | Title rules and editor keys | `checkCanvasTitle`, `checkNewCanvasTitle`, `titleProblemMessage`, `titleEditKey`, `CANVAS_TITLE_MAX`, `DEFAULT_CANVAS_TITLE` |
| `frontend/src/lib/placements/titleSubmit.ts` | Enter in the title editor: check, save, message | `submitCanvasTitle` |
| `frontend/src/lib/placements/canvasList.ts` | Create / rename outside an open canvas | `createCanvasFromInput`, `renameCanvasIn`, `replaceCanvas` |
| `frontend/src/lib/placements/canvases.ts` | `canvases` ordering and updates, badge, "Also on" | `sortCanvasRefs`, `withCanvasRef`, `renameCanvasRef`, `otherCanvases`, `isOnOtherCanvases`, `canvasBadge`, `alsoOnText` |
| `frontend/src/lib/placements/history.ts` | Undo/redo consistency | `HistorySnapshot`, `dropSnapshotsWith`, `mapSnapshotSnippets` |
| `frontend/src/lib/placements/transfer.ts` | Picker list, id checks, show result, notice text | `pickerCanvases`, `checkTransferIds`, `TRANSFER_MAX`, `showOnTarget`, `transferNotice` |
| `frontend/src/lib/placements/deletePlan.ts` | Delete labels and the Delete everywhere confirmation | `deleteMenu`, `deleteEverywhereConfirm` |
| `frontend/src/lib/placements/removal.ts` | Removal outcome and its failure notice | `RemovalSummary`, `removalFailureNotice` |
| `frontend/src/stores/canvasStore.ts` | Store actions | `updateSnippet`, `commitPositions`, `removeFromCanvas`, `deleteEverywhere`, `transferSnippets`, `renameCanvas` (replaces `deleteSnippet`); stale-load guard in `loadCanvas` |
| `frontend/src/stores/placementNoticeStore.ts` | Result toast state | `usePlacementNoticeStore`, `PlacementNotice` |
| `frontend/src/stores/uiStore.ts` | Badge popover state | `canvasesPopover`, `setCanvasesPopover`, `leaveCanvas` |
| `frontend/src/components/placements/TitleEditor.tsx` | Inline title input with inline error | default |
| `frontend/src/components/placements/CanvasPicker.tsx` | Searchable canvas picker with "New canvas…" | default |
| `frontend/src/components/placements/PlacementToast.tsx` | Result toast with Open | default, `PlacementToastView`, `PLACEMENT_NOTICE_MS` |
| `frontend/src/components/placements/CanvasesPopover.tsx` | Badge popover | default, `CanvasesPopoverView` |
| `frontend/src/components/placements/CanvasBadge.tsx` | DOM "⧉ N" button (list rows) | default |
| `frontend/src/components/toolbar/CanvasTitle.tsx` | Click-to-edit header title | default |
| `frontend/src/components/canvas/SnippetContextMenu.tsx` | Move / Also show / Remove / Delete everywhere, picker and confirm views | default |
| `frontend/src/components/canvas/SnippetCard.tsx` | Pixi badge in the header | |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | "Also on: …" links (save, then navigate) | |
| `frontend/src/components/list/ListRow.tsx`, `ListView.tsx` | Badge in list rows (`onShowCanvases`) | |
| `frontend/src/components/pages/CanvasListPage.tsx` | Rename pencil, typed create | |
| `frontend/src/components/pages/CanvasPage.tsx` | Delete key -> `removeFromCanvas`; mounts `PlacementToast`, `CanvasesPopover`; `leaveCanvas` on canvas change | |

Tests: `api/placements.test.ts`, `lib/placements/*.test.ts`, `stores/canvasStore.placements.test.ts`,
`stores/placementNoticeStore.test.ts`, `stores/uiStore.canvasesPopover.test.ts`,
`components/placements/placementViews.test.tsx`.

## Invariants

- Every snippet update and batch-position request carries the open canvas's id; `snippetApi` has no
  update path that could omit it.
- Undo/redo never contains a snippet that a move, remove or delete everywhere took off this canvas.
- A transfer failure changes nothing locally; a show never removes anything locally.
- A response for a canvas the user has left (load, update, transfer, rename, removals) never writes into
  the canvas now open.
- `Snippet.canvases` stays sorted by lower(title), then id, after local patches (show, rename).
- Titles sent are trimmed and 1..255 code points (create: blank becomes "Untitled Canvas").
- Context menu actions apply to `pickerTargets`: the multi-selection when opened on a member, else the one
  snippet.
