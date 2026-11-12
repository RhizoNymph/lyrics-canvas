# Android: Canvas Rename and Snippet Placements

The Android client of [snippet_placements.md](./snippet_placements.md) (the canonical spec, written with
the backend: data model, migration, endpoints, delete and merge semantics). A snippet's content (text,
type, grid, tags, phonetics, recordings) is shared; its layout (position, size, z, group) is a
**placement** on one canvas. One snippet can be placed on several canvases. On Android this feature
adds canvas rename, "Move to canvas…" / "Also show on canvas…", "Remove from this canvas" vs
"Delete everywhere", the "⧉ N" badge and the editor's "Also on: …", and makes every layout save name
the open canvas.

> Offline copy: Rename, Move to / Also show on, remove and delete everywhere are applied to the local copy and synced (the server's transfer rules are reproduced locally); canvases can also be deleted from the list ⋮ menu; see [android_offline_sync.md](./android_offline_sync.md).

## Scope

- **Model** (`:core:model`): `Snippet.placementId` (`placement_id`, required) and `Snippet.canvases`
  (`[{id, title}]`, required, every canvas holding the snippet including the current one);
  `PlacementId`; `CanvasRef`; `TransferMode` (`move` / `show`); `TransferRequest` (built only by
  `of`: de-duplicated, 1..500 ids); `TransferResponse`; `RemovePlacementResponse`
  (`snippet_deleted`); `BatchPositionRequest.canvasId` (`canvas_id`); `PlacementErrorCode`
  (`invalid_canvas_title`, `placement_not_found`, `canvas_not_found`, `same_canvas`).
- **Repositories** (`:core:data`): `CanvasRepository.rename`; `SnippetRepository.update(canvasId, …)`
  (`PUT /api/snippets/{id}?canvas_id=`), `batchPosition(canvasId, …)`, `removeFromCanvas`
  (`DELETE /api/canvases/{cid}/snippets/{sid}`), `deleteEverywhere` (`DELETE /api/snippets/{id}`),
  `transfer` (`POST /api/canvases/{cid}/snippets/transfer`). `ApiClient.send` takes query parameters.
- **Rename**: canvas list rows (long-press, or ⋮ → Rename) and the canvas screen's top-bar title
  (tap) open the same dialog. Local check: trim (Python whitespace), 1–255 code points. A local or
  server (`invalid_canvas_title`) refusal shows inline in the dialog. The list row and the header
  update without a reload; a rename on the canvas screen also reaches the list below it on the back
  stack, and the snippets' `canvases` titles (badges, editor line).
- **Create** (kept): the list's "New canvas" dialog now uses the same title check (blank = the server
  default "Untitled Canvas", over 255 refused locally) and shows `invalid_canvas_title` by message.
- **Transfer**: the selection bar's ⋮ menu has "Move to canvas…" and "Also show on canvas…". They
  open a bottom-sheet picker: "New canvas…" at the top (title prompt, then create and act on it),
  a search field, and the user's other canvases newest updated first. Afterwards a snackbar
  ("Moved 3 snippets to *Title*" / "Showing 3 snippets on *Title* (1 was already there)") offers
  **Open**, which navigates to the target. A move takes the snippets off this canvas; a show updates
  their badges.
- **Delete**: the trash icon and the ⋮ menu's delete item are labelled "Remove from this canvas" when
  any selected snippet is on other canvases (otherwise "Delete"), and call the placement delete for
  every selected snippet. Confirm only when some snippet is on no other canvas (deleted for good, as
  before). "Delete everywhere…" in the ⋮ menu always confirms, naming the other canvases.
- **Badge**: cards (canvas and list mode, same renderer) of a snippet on more than one canvas show
  "⧉ N" in the header after the type label. The badge's list is the selection bar's ⋮ →
  "On other canvases…" (long-press the card to select it): a dialog of the other canvases, each
  opening that canvas. The editor shows "Also on:" with a button per other canvas.
- **Merge**: unchanged UI. After a merge every requested original leaves this canvas's view (also one
  the server kept because it is on other canvases).
- **Errors**: `PlacementError` (typed) with a human message for each new code.

## Non-scope

- **Undo**: Android has no undo / redo (canvas or editor; the editor's Discard drops unsaved edits).
  The contract's rule ("transfer, shared removal and delete everywhere are not undoable and clear the
  history") therefore needs nothing on Android.
- Tapping the badge itself on the canvas: cards are drawn in one Compose `Canvas` whose hit test
  returns only the card, so the badge's list is offered through the long-press selection's menu
  (the contract allows "a long-press menu entry"). List rows use the same card drawing.
- Groups (Android has no group UI), fork, sharing, t-SNE, export.
- A canvas list refresh after a transfer (the target's `updated_at` changes on the server; the list
  shows it at the next refresh).

## Data and control flow

### Rename

```
CanvasListScreen row long-press / ⋮ Rename -> CanvasListViewModel.startRename(canvas)
CanvasTopBar title tap                       -> CanvasViewModel.startRename()
  -> CanvasRenamer.open(id, title)           state: RenameDialog.Open(canvasId, initial)
RenameCanvasDialog "Rename" / IME Done       -> submitRename(text) -> CanvasRenamer.submit
  checkCanvasTitle(text): Invalid -> Open(error = CanvasTitleProblem message), no request
                          Valid(trimmed) -> Open(saving) -> CanvasRepository.rename (PUT {title})
  Success -> Closed; onRenamed(canvas); CanvasUpdates.publish(canvas)
     list VM:   upsert row in place
     canvas VM: Ready.title = title; snippets = withCanvasRenamed(snippets, id, title)
  Failure -> Open(error = toPlacementError(e).userMessage(), saving = false)
CanvasUpdates (AppContainer, SharedFlow) -> CanvasListViewModel collects -> upsert (replace or prepend)
```

### Layout saves

`CanvasViewModel.endMove` sends `batchPosition(canvasId, [update])`; the editor's save and the
pre-recording flush send `update(snippet.canvasId, snippet.id, body)` (the canvas the snippet was
opened on, which is its placement context). No other code calls `update` or `batchPosition`.

### Transfer

```
SelectionTopBar ⋮ Move to canvas… / Also show on canvas…
  -> TransferViewModel.open(mode, selection ids)   Picking(mode, source, ids, choices = Loading)
  -> CanvasRepository.list -> choices = Loaded(all) | Failed(message, Retry)
  rows = pickerCanvases(all, source, query)        (not the source; title contains query; updated_at desc, id)
row tap -> choose(canvas)
  -> TransferRequest.of(ids, canvas.id, mode)      (null -> inline "at most 500")
  -> SnippetRepository.transfer(source, request)
     Success -> port.onTransferred(response, CanvasRef(id, title)) -> CanvasViewModel:
                  snippets = applyTransfer(...)    (move: drop placed + already; show: add target to canvases)
                  selection pruned to what is left
                Closed; TransferEvent.Done(transferMessage(mode, response, title), target)
                CanvasScreen: snackbar(text, "Open") -> ActionPerformed -> onOpenCanvas(target)
                  -> AppNavHost navigate(CanvasRoute(target))
     Failure -> Picking(error = toPlacementError(e).userMessage(), busy = false)
"New canvas…" -> startNewCanvas -> NewCanvasPrompt
  submitNewCanvas(text): checkCanvasTitle (blank -> "Untitled Canvas", too long -> prompt error)
  -> CanvasRepository.create -> publish on CanvasUpdates, prepend to choices -> choose-equivalent transfer
  failure -> prompt error
```

### Remove from this canvas / Delete everywhere

```
Ready.removalPlan = RemovalPlan.of(selectedSnippets)
  sharedIds   = selected snippets with otherCanvases(s) non-empty
  lastCopyIds = the rest
  label       = "Remove from this canvas" if sharedIds non-empty else "Delete"
trash / ⋮ label -> needsConfirm (lastCopyIds non-empty) ? RemovalConfirmDialog : deleteSelected()
  deleteSelected -> removeFromCanvas(canvasId, id) for each, in parallel
  succeeded ids leave snippets and selection; first failure -> snackbar (typed message)
⋮ Delete everywhere… -> DeleteEverywhereDialog(plan.otherCanvases named) -> deleteEverywhereSelected()
  -> deleteEverywhere(id) for each, same bookkeeping
```

### Badge and "Also on"

`cardLayout(snippet).linkBadge` is non-null when `linkBadgeCount(snippet)` (canvases.size > 1);
`drawCard` paints its pill and label. The canvas's layout cache also keys on `canvases.size`.
`Ready.selectionOtherCanvases` (sorted, distinct) feeds "On other canvases…" and
`OtherCanvasesDialog`. `AlsoOnLine` (editor header slot) shows `alsoOnText(snippet)` with one
button per `otherCanvases(snippet)`; tapping navigates to that canvas while the editor stays on the
back stack.

## Files

Paths are under `android/`.

| File | Role | Key exports |
|---|---|---|
| `core/model/.../model/Placements.kt` | Placement DTOs and error codes | `CanvasRef`, `TransferMode`, `TransferRequest.of`, `TransferRequest.MAX_SNIPPETS`, `TransferResponse`, `RemovePlacementResponse`, `PlacementErrorCode.parse` |
| `core/model/.../model/Snippet.kt` | Snippet in a canvas context | `Snippet.placementId`, `Snippet.canvases`, `BatchPositionRequest(canvasId, updates)` |
| `core/model/.../model/Ids.kt` | Ids | `PlacementId` |
| `core/data/.../data/ApiClient.kt` | HTTP client | `send(..., query)` |
| `core/data/.../repository/CanvasRepository.kt` | Canvases | `rename(id, title)` |
| `core/data/.../repository/SnippetRepository.kt` | Snippets and placements | `update(canvasId, id, body)`, `removeFromCanvas`, `deleteEverywhere`, `batchPosition(canvasId, updates)`, `transfer` |
| `core/logic/.../logic/placements/CanvasTitle.kt` | Local title rule | `checkCanvasTitle`, `CanvasTitleCheck`, `CanvasTitleProblem`, `CANVAS_TITLE_MAX` |
| `core/logic/.../logic/placements/PlacementError.kt` | Typed failures | `PlacementError`, `toPlacementError` |
| `core/logic/.../logic/placements/Placements.kt` | Pure placement rules | `otherCanvases`, `linkBadgeCount`, `linkBadgeLabel`, `alsoOnText`, `sortCanvasRefs`, `RemovalPlan`, `DeleteEverywherePlan`, `withCanvas`, `applyTransfer`, `withCanvasRenamed`, `transferMessage` |
| `core/logic/.../logic/placements/CanvasPicker.kt` | Picker rows | `pickerCanvases` |
| `app/.../canvaslist/CanvasUpdates.kt` | Process-wide broadcast of renamed / created canvases | `CanvasUpdates.publish`, `updates` |
| `app/.../canvaslist/CanvasListViewModel.kt` | List: load, create, rename, upsert from updates | `startRename`, `submitRename`, `dismissRename`, `rename` |
| `app/.../canvaslist/CanvasListScreen.kt` | List UI | `CanvasRow` (long-press / ⋮ Rename) |
| `app/.../canvas/rename/CanvasRenamer.kt` | Shared rename flow | `RenameDialog`, `CanvasRenamer` |
| `app/.../canvas/rename/RenameCanvasDialog.kt` | Rename dialog | `RenameCanvasDialog` |
| `app/.../canvas/placements/PlacementCanvasPort.kt` | Picker → canvas | `PlacementCanvasPort.onTransferred` |
| `app/.../canvas/placements/TransferUiState.kt` | Picker state | `TransferUiState`, `CanvasChoices`, `NewCanvasPrompt`, `TransferEvent.Done` |
| `app/.../canvas/placements/TransferViewModel.kt` | Picker + transfer + new canvas | `open`, `setQuery`, `choose`, `retryLoad`, `startNewCanvas`, `submitNewCanvas`, `close` |
| `app/.../canvas/placements/PlacementMessages.kt` | User text | `PlacementError.userMessage`, `CanvasTitleProblem.userMessage`, `RemovalPlan.actionLabel/confirmTitle/confirmText`, `DeleteEverywherePlan.confirmTitle/confirmText` |
| `app/.../canvas/placements/ui/CanvasPickerSheet.kt` | Picker bottom sheet and "New canvas" prompt | `CanvasPickerSheet` |
| `app/.../canvas/placements/ui/PlacementDialogs.kt` | Confirms and the badge's list | `RemovalConfirmDialog`, `DeleteEverywhereDialog`, `OtherCanvasesDialog` |
| `app/.../canvas/CanvasViewModel.kt` | Canvas: canvas-scoped saves, removal, delete everywhere, rename, transfer apply | `deleteSelected`, `deleteEverywhereSelected`, `startRename`, `submitRename`, `rename`, `onTransferred` |
| `app/.../canvas/CanvasUiState.kt` | Derived selection data | `Ready.selectedSnippets`, `removalPlan`, `deleteEverywherePlan`, `selectionOtherCanvases` |
| `app/.../canvas/CanvasTopBars.kt` | Title tap; selection ⋮ menu | `CanvasTopBar(onRename)`, `SelectionTopBar(placements)`, `SelectionPlacementActions` |
| `app/.../canvas/CanvasScreen.kt` | Wiring: picker, dialogs, snackbar Open | `CanvasScreen(onOpenCanvas)` |
| `app/.../canvas/render/CardLayout.kt`, `CardRenderer.kt` | Badge geometry and drawing | `LinkBadge`, `CardLayout.linkBadge`, `CardMetrics.HEADER_CHAR_WIDTH`, `BADGE_GAP` |
| `app/.../canvas/SnippetCanvas.kt` | Layout cache keyed on `canvases.size` too | — |
| `app/.../editor/ui/AlsoOnLine.kt`, `GridEditorScreen.kt` (`header` slot), `GridEditorRoute.kt` (`onOpenCanvas`) | "Also on: …" | `AlsoOnLine` |
| `app/.../editor/GridEditorViewModel.kt` | Saves with the snippet's canvas | — |
| `app/.../nav/AppNavHost.kt` | Navigation to another canvas from the canvas and editor | — |
| `app/.../di/AppContainer.kt` | `canvasUpdates` | — |

Tests: `core/model/.../PlacementDtoTest.kt`; `core/data/.../SnippetPlacementRepositoryTest.kt`
(+ rename in `CanvasRepositoryTest.kt`); `core/logic/.../placements/*Test.kt`;
`app/.../canvas/CanvasViewModelPlacementsTest.kt`, `canvas/placements/TransferViewModelTest.kt`,
`canvas/placements/PlacementMessagesTest.kt`, `canvas/render/CardLayoutBadgeTest.kt`,
`canvaslist/CanvasListRenameTest.kt`, `editor/GridEditorPlacementTest.kt`.

## Invariants and constraints

- Every `PUT /api/snippets/{id}` carries `?canvas_id=` and every batch-position body carries
  `canvas_id` (the open canvas); the repository signatures make a canvas-less call impossible.
- A `Snippet` without `placement_id` or `canvases` does not decode (no backwards compatibility: old
  servers and old APKs are not supported together, per the contract).
- `TransferRequest` exists only with 1..500 distinct ids; the picker never offers the source canvas.
- `RemovalPlan` / `DeleteEverywherePlan` exist only for a non-empty selection. A plain delete never
  calls the delete-everywhere route; the confirm appears exactly when a snippet would be deleted for
  good; Delete everywhere always confirms.
- `TransferUiState.Picking.ids` is never empty (`require`). Only server-confirmed results change the
  view (transfer, removal, delete, rename); a failure changes nothing locally.
- Titles are checked as the server checks them: Python `strip` whitespace, 1–255 code points.
- `canvases` stays sorted by lowercase title, then id, after local updates (show, rename).
- No undo exists on Android, so no history can reference a moved or deleted snippet.
