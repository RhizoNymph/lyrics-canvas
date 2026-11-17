# Android List Order (sort menu, drag to reorder)

List mode on Android can be sorted four ways, and rows can be dragged into a custom order. The
custom order is saved per canvas on the server (`snippet_placements.list_rank`), so web and Android
share it. On Android it is a layout field of the offline copy, so it works offline and syncs. The
sort choice is remembered per device. The server side is `feat/list-order-backend`; the web side is
`feat/web-list-order`.

## Scope

- **Sort menu.** A "Sort: <current> ▾" row sits above the list: **Last edited**, **Created**,
  **A-Z** and **Custom**. The choice is remembered per device (Preferences DataStore `list_view`,
  key `list_sort`), not per canvas.
  - Last edited: `content_updated_at` desc, `created_at` desc, id asc. This is the original order.
  - Created: snippet `created_at` desc, id asc.
  - A-Z: display text (rest marks removed), `Collator` of the default locale at `PRIMARY`
    strength (case and accents ignored, like `localeCompare` with `sensitivity: "base"`), then id.
  - Custom: `list_rank` asc in plain character order (never locale), then `placement_id` asc.
- **Drag to reorder.** Each row has a drag handle (≡) on its left. Long-press on the card still
  means "select". While a row is dragged it moves through the list as it passes its neighbours'
  centres, and the list scrolls when it nears the top or bottom.
  - **In Custom**, the drop between the visible neighbours P (above) and N (below) sets
    `rank = keyBetween(P.rank, N.rank)`. That is one layout update: `PUT /api/snippets/{id}?canvas_id=`
    with `list_rank` only. Hidden (filtered-out) rows are not considered.
  - **In another sort**, the drop adopts the order on screen as the custom order for **all**
    placements on the canvas. Filtered-out ones are included, in the same captured order. The
    moved row goes right after its visible upper neighbour, or right before its lower one when
    dropped on top. Every placement gets `keysBetween(null, null, n)`. This is one batch-position
    call with all ranks, and the sort switches to Custom (remembered).
  - **Fallback.** In Custom, the drop re-ranks the whole canvas the same way when no key fits
    between the neighbours. This happens with equal keys (two offline devices picked the same
    one), a stored key that is not valid, or a key that would pass 64 characters.
  - The change shows at once. A failure restores the previous keys, order and sort, and shows
    the error in the snackbar. There is no undo on Android.
  - "Move up" / "Move down" accessibility actions on the handle do the same drop one row away.
- **Offline.** `list_rank` is a layout field.
  - It lives in `LayoutPatch` (last write wins, field by field, when coalescing) and in the local
    placement. The snapshot decodes it.
  - An offline create takes `keyBetween(null, local minimum)` and sends it with the create.
  - A re-rank goes through the outbox as one layout operation per placement.
- **New snippets** go to the top of the custom order. That covers the FAB, word bank drafts,
  voice takes and rhyme materializations; the server or the local edit picks the key. A merge
  result keeps the first block's key. Transfers ("Move to" / "Also show on") put the snippets on
  top of the target's order.

## Non-scope

- Live re-sorting. As before, rows never move under the finger. The order is captured when the
  list is shown, re-sorted, reordered, reloaded or an editor save returns. Another device's
  reorder shows at the next capture.
- Undo of a reorder (Android has no undo stack).
- Dragging on the spatial canvas, or more than one row at once.
- A drag library. The gesture is `detectDragGestures` on the handle plus `LazyListState` layout
  info. No reorder library was in the offline Gradle cache, and none was added.
- Per-canvas sort choice, and the sort on the server.

## Data and control flow

```
Sort menu "Custom"  -> CanvasViewModel.setListSort(CUSTOM)
                        listSort = CUSTOM; Ready.mode = SnippetList(ListOrder.of(snippets, CUSTOM))
                        persistScope: ListSortStore.save(CUSTOM)   (DataStore list_view / list_sort = "custom")
ListSortStore.sort  -> applyListSort (stored value on start, or changed): re-sorts a shown list
setMode(LIST)       -> SnippetList(ListOrder.of(snippets, listSort))

Handle drag (SnippetList)
  onDragStart  -> RowDrag.start(id, rows)              (rows copied; the LazyColumn shows RowDrag.rows)
  onDrag(dy)   -> offset += dy; edgeScroll -> listState.dispatchRawDelta (offset += consumed)
                  dragStep(row, offset, above, below) -> Swap(index, shift): rows.moved(from, index), offset += shift
  onDragEnd    -> onMove(id, index of id in RowDrag.rows)  -> CanvasViewModel.moveListRow(id, index)

moveListRow(id, toIndex)
  planReorder(order, all snippets, listRows, id, toIndex)        (core/logic list/ListReorder.kt)
    null                -> nothing (dropped in place / not shown / not list mode / busy)
    Move(id, rank)      -> Custom and keyBetween(P, N) fits
    Rerank([(id, rank)])-> another sort, or no key between P and N
  optimistic: snippets[ids].listRank = new; listSort = CUSTOM; mode = SnippetList(ListOrder.of(next, CUSTOM))
  Move   -> SnippetRepository.update(canvas, id, SnippetUpdateRequest(listRank))
  Rerank -> SnippetRepository.batchPosition(canvas, [PositionUpdate(id, listRank = r)...])
  failure -> restore the previous ranks and sort (and the stored sort); snackbar (PlacementError message)

Local-first repositories (core/sync)
  update(listRank)     -> LocalEdits.updateSnippet: validate (422 invalid_list_rank), placement.listRank,
                          enqueue MoveSnippet(layout = LayoutPatch(listRank))
  batchPosition(ranks) -> LocalEdits.batchPosition: validate all first (any invalid -> 422, nothing changes),
                          per placement: placement update + MoveSnippet(LayoutPatch(listRank)) (coalesces)
  createSnippet        -> rank = body.listRank (validated) ?: topRank(ranks on canvas); local placement gets it
  merge                -> merged placement takes the first source's (sourceIds.first()) rank
  transfer             -> new target placements: topRanks(target ranks, n) in stacking order
  Resolver Save mine as new -> topRank on the canvas, sent in the create body
SyncEngine pass
  nextBatch().withLocalRanks(d)  -> a CreateSnippet without list_rank carries the local placement's rank
  HttpOpSender MoveSnippet       -> PUT /api/snippets/{id}?canvas_id= {list_rank, ...layout fields}
  answers                        -> LocalPlacement.of(snippet) takes the server's list_rank
Snapshot   -> SnapshotPlacement.listRank -> LocalPlacement.listRank -> Views.assemble -> Snippet.listRank
```

## Files and key exports

| File | Role / key exports |
|---|---|
| `android/core/logic/.../logic/list/ListRank.kt` | Port of `fractional-indexing` 3.2.0. `ListRank` (value class; only from `parse` or generation; character-order `compareTo`), `LIST_RANK_DIGITS`, `LIST_RANK_MAX_LENGTH` (64), `keyBetween(a, b)`, `keysBetween(a, b, n)`. Failures: `RankResult` (`Ok`, `Failed`) with `ListRankError` (`NotAscending`, `Exhausted`, `TooLong`). Also `getOrNull`, `topRank(existing)` and `topRanks(existing, n)`. |
| `android/core/logic/.../logic/list/ListSort.kt` | `ListSort` (`LAST_EDITED`, `CREATED`, `A_Z`, `CUSTOM`; `wire`, `parse`, `DEFAULT`), `listCollator(locale)`, `sortForList(snippets, sort, collator)` |
| `android/core/logic/.../logic/list/ListOrder.kt` | `ListOrder(ids, sort)` (`of(snippets, sort)`, `arrange` sorts unseen snippets by the order's sort). `sortForList(snippets)` is Last edited. |
| `android/core/logic/.../logic/list/ListReorder.kt` | `ReorderPlan` (`Move(id, rank)`, `Rerank(ranks)`), `planReorder(order, all, shown, moved, toIndex)` |
| `android/core/model/.../model/Snippet.kt` | `Snippet.listRank` (`list_rank`, default `DEFAULT_LIST_RANK` = `"a0"`), `SnippetCreateRequest.listRank`, `SnippetUpdateRequest.listRank`, `PositionUpdate(id, positionX?, positionY?, listRank?)` |
| `android/core/model/.../model/Sync.kt` | `SnapshotPlacement.listRank` |
| `android/core/model/.../model/Placements.kt` | `PlacementErrorCode.INVALID_LIST_RANK` |
| `android/core/sync/.../store/LocalData.kt` | `LocalPlacement.listRank` (an older store file decodes `"a0"`), `LocalData.listRanksOn(canvas)` |
| `android/core/sync/.../store/Views.kt` | Snippets carry their placement's `listRank` |
| `android/core/sync/.../outbox/Patches.kt` | `LayoutPatch.listRank` (in `of`, `then`) |
| `android/core/sync/.../apply/LocalEdits.kt` | Ranks on create, update, batch, merge and transfer; `invalid_list_rank` validation |
| `android/core/sync/.../conflict/Resolver.kt` | Save mine as new: top rank, sent in the create |
| `android/core/sync/.../engine/LocalRanks.kt` | `Batch.withLocalRanks(data)` |
| `android/core/sync/.../engine/OpSender.kt` | `MoveSnippet` sends `list_rank` |
| `android/app/.../app/canvas/list/ListSortStore.kt` | `ListSortStore` (`sort: Flow`, `save`), `DataStoreListSortStore`, `InMemoryListSortStore`, `ListSort.label` |
| `android/app/.../app/canvas/list/ListSortMenu.kt` | `ListSortBar(sort, onSort)` |
| `android/app/.../app/canvas/list/ListDrag.kt` | Pure drag geometry: `RowSpan`, `DragStep` (`Stay`, `Swap(index, offsetShift)`), `dragStep`, `List.moved`, `edgeScroll` |
| `android/app/.../app/canvas/list/SnippetList.kt` | `SnippetList(..., onMove)`: drag handles, `RowDrag` state, accessibility actions |
| `android/app/.../app/canvas/CanvasViewModel.kt` | `listSorts` parameter, `setListSort`, `moveListRow`; list orders are captured with the current sort |
| `android/app/.../app/canvas/CanvasScreen.kt` | `ListSortBar` above the list; `onMove` disabled while busy |
| `android/app/.../app/di/AppContainer.kt` | `listSorts` (DataStore `list_view`) |
| `spec/list_rank_vectors.json`, `android/core/logic/src/test/resources/list_rank_vectors.json` | The shared vectors, byte-identical to the backend's |

Tests:

- `core/logic`: `ListRankTest` (the upstream cases, limits, errors), `ListRankVectorsTest` (every
  vector: `between`, `n_between`, `invalid`), `ListSortTest` and `ListReorderTest`.
- `core/model`: `ListRankDtoTest`.
- `core/sync`: `listrank/ListRankEditsTest` (edits, coalescing, batch, merge, transfer, views,
  snapshot, old store file) and `listrank/ListRankHttpTest` (create and re-rank on the wire).
- `app`: `CanvasViewModelListOrderTest` (sorts, persistence, custom move, re-rank, filtered rows,
  rollback, long-press) and `list/ListDragTest` (drag geometry).

## Invariants and constraints

- **A `ListRank` is always a key the server accepts.** It is a valid `validateOrderKey` key made
  of base-62 characters only, and at most 64 characters long. Generation returns a typed failure
  instead of a longer key.
- **Keys compare by character order.** That is byte order for these ASCII keys, never the
  locale. Custom order ties break by `placement_id`.
- **Every sort is total and deterministic.** It ends with the id (Custom: the placement id).
- **A drop sends at most one call.** Custom sends one `list_rank` PUT; otherwise one
  batch-position with every placement of the canvas.
- **The list never shows ranks the copy does not hold.** The optimistic ranks equal what the
  local edit writes, and a failed edit restores the previous ones.
- **`list_rank` is layout.** It never changes `version` or `content_updated_at`, never carries
  `base_version`, and never parks a conflict.
- **New placements go to the top of the custom order**, offline as on the server. The only
  exception is a merge result, which keeps the first block's key.
- **Rows still never move under the finger.** The order is captured only at the moments listed
  in Non-scope (Live re-sorting) and after a drop.
- **The sort is per device.** It is never sent to the server and never stored per canvas.

## Decisions

1. **A re-rank is one layout operation per placement in the outbox, not one batch operation.**
   The repository call is one `batchPosition`. The local layer queues a `MoveSnippet` per
   placement, so on the wire it is a layout PUT per placement. This reuses the existing
   coalescing (last value wins per placement), ordering and answer handling. Each answer also
   returns the placement, which a batch-position answer does not. The cost is n requests for an
   n-snippet re-rank, sent in one sync pass.
2. **The create's rank is filled in when it is sent** (`Batch.withLocalRanks`), not stored in the
   queued body. The queued `CreateSnippet` stays as the screen asked for it. The local placement
   holds the rank, so a reorder before the create syncs still sends the newest key.
3. **Valid keys are restricted to base-62 characters.** The contract says "what
   `validateOrderKey` accepts". The 3.2.0 `validateOrderKey` does not check the characters after
   the head, but the backend's `validate_rank` and the shared vectors (`"a0!"`, `"a0é"`) refuse
   them. The client follows the server.
4. **`list_rank` decodes with a default (`"a0"`)** on `Snippet`, `SnapshotPlacement` and
   `LocalPlacement`. This is the same choice as `version = 1`: a phone's stored copy from before
   ranks existed still loads, and the next snapshot brings the real keys. The server always
   sends one.
5. **A drop in another sort keeps the moved row next to its visible upper neighbour** in the
   adopted order (or right above its lower neighbour when dropped on top). This places it
   relative to hidden rows deterministically.
6. **Drag is disabled while a merge or delete is in flight** (`busy`). The Android editor is a
   separate screen, so "no drag while an editor is open" holds by construction.
