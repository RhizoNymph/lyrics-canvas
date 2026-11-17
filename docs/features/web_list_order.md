# Web list order: sort menu and drag reorder

The web client of custom list order (round 3 contract, section A). List mode gets a sort menu
(**Last edited**, **Created**, **A-Z**, **Custom**) and manual reordering: a drag handle on each row and
Alt+Up / Alt+Down on a focused row. The custom order is the placement's `list_rank` (a fractional index
key) on the open canvas. It is saved on the server and shared with Android. The chosen sort is
remembered per device. The backend side (column, migration 010, rank assignment for new placements,
validation) is documented by the backend branch. List mode itself is [list_mode.md](./list_mode.md).

## Scope

- `list_rank: string` on `Snippet` (the placement on `canvas_id`). It is a layout field: per canvas, last
  write wins, no `base_version`.
- Typed wire: `SnippetUpdateBody.list_rank`, `SnippetCreateBody.list_rank?` (the web never sets it;
  the server puts new snippets at the top), `BatchPositionItem` (a position, a rank, or both; never
  neither), `placementApi.setListRank` (layout PUT with `list_rank` only) and
  `placementApi.setListRanks` (one batch-position call whose items carry only `list_rank`). Both reject
  with `PlacementError` (operation `save_list_order`; server code `invalid_list_rank` is typed).
- `lib/listMode/rank.ts`: a port of `fractional-indexing` 3.2.0 `generateKeyBetween` /
  `generateNKeysBetween` (base 62), `validateRank`, `compareRanks` (code unit order). It passes all of
  `spec/list_rank_vectors.json` (the backend's file, byte-identical copy).
- Sort menu above the rows. The four sorts:
  - **Last edited**: `content_updated_at` desc, `created_at` desc, `id` (the original list order).
  - **Created**: `created_at` desc (as instants; unreadable sorts last), `id`.
  - **A-Z**: display text (rest marks dropped, trimmed), `Intl.Collator(undefined, {sensitivity: 'base'})`,
    then `id`.
  - **Custom**: `list_rank` asc (code unit order, never locale), `placement_id` asc, then `id`.
- The sort is stored in `localStorage` under `lyrics-canvas.listSort` (per viewer and device, not per
  canvas, never on the server). Default: Last edited. Storage failures fall back silently (logged).
- Drag to reorder with pointer events (no library): the `⠿` handle at the left of the row header;
  pointer capture, a drop line between rows, auto-scroll near the list's top and bottom edges, Escape
  cancels. Alt+Up / Alt+Down on a focused row moves it by one visible row and keeps it focused.
- Optimistic: the local order changes at once. On failure the ranks (and a switched sort) roll back and an
  error notice is shown (`placementNoticeStore`, the bottom toast).

## Non-scope

- Undo / redo of a reorder. The canvas undo stack is local snapshots only. Undo restores the snippet map
  and never calls the server, and there is no layout op to replay. So a reorder is not undoable. Instead
  every snapshot is patched with the new ranks, so undoing an earlier edit never brings back an old order
  (the same rule as tag links and "Also show on").
- Moving a multi-selection as a block. A drag or Alt+arrow moves only the row it starts on.
- Reordering on the canvas view, in shared (read-only) views, or by touch long-press (the handle takes touch
  input too, since it uses pointer events with `touch-action: none`).
- Per-canvas sort choice.
- Backend and Android (sibling branches).

## Data and control flow

```
ListView render
  visibleSnippets(snippets, tagFilter, search)                 (unchanged visibility)
  -> sortSnippets(listOrderStore.sort, ...)                     lib/listMode/sort
  -> useFrozenOrder(ids, editingSnippetId !== null)            (unchanged freeze)
  -> rows; each gets `reorder` = { dragging, onHandlePointerDown, onStep } or null while an editor is open

Sort menu (ListSortMenu) -> listOrderStore.setSort(sort)
  -> saveListSort(browserStorage(), sort)                       typed result, never throws
  -> set { sort }

Drag (useRowDrag)
  pointerdown on handle (button 0): preventDefault, stopPropagation, setPointerCapture
     -> drag = { id, fromIndex, slot = fromIndex }
  pointermove: auto-scroll within 48 px of the list edges; slot = dropSlot(row midpoints, clientY)
     -> DropIndicator rendered before row `slot` (or after the last row); the dragged row is faded
  pointerup: slot = dropSlot(...); unless isNoopSlot(slot, fromIndex):
     onDrop(id, slotToIndex(slot, fromIndex))
  pointercancel / Escape: drag = null, nothing sent
  the rendered order changes under the drag (a snippet arrives or leaves) -> drag cancelled
  handle click / context menu are stopped, so a drag never opens the editor or the menu

Alt+Up / Alt+Down on a focused row (not with Ctrl / Meta / Shift)
  -> stepTarget(order, id, ±1) (null at an edge) -> the same onDrop; the row is refocused after re-render

onDrop(id, toIndex) -> listOrderStore.reorder({ visible: order, movedId: id, toIndex })
  refuse: no canvas | editingSnippetId !== null ('editing')
  planReorder({ sort, visible, all: every snippet on the canvas, movedId, toIndex })
    refuse: 'not_visible' | 'not_on_canvas' | 'unchanged' (same place)
    rest = visible without the moved row; P = rest[to - 1], N = rest[to]   (visible neighbours only)
    sort == custom:  keyBetween(P.rank, N.rank)                   -> { kind: single, change }
                     (RankError, e.g. P and N tie after two offline devices picked one key:
                      fall through to a re-rank of the custom order; logged at warn)
    otherwise:       base = sortSnippets(sort, all) without the moved row (filtered-out rows included)
                     insert right after P (or right before N when dropped at the top)
                     keys = nKeysBetween(null, null, n)           -> { kind: rerank, adopted: sort, changes }
  optimistic: canvasStore.applyListRanks(changes -> to)           live map + every undo/redo snapshot
              rerank from another sort -> setSort('custom')
  queue (one request at a time, in order):
    single: placementApi.setListRank(id, canvasId, to)   PUT /api/snippets/{id}?canvas_id=  { list_rank }
            the echoed rank is taken when it differs (expect: to)
    rerank: placementApi.setListRanks(canvasId, all)     PATCH /api/snippets/batch-position
                                                          { canvas_id, updates: [{ id, list_rank }...] }
  failure (canvas still open): applyListRanks(changes -> from, expect: to)
           rerank: back to the previous sort if the sort is still Custom
           notice "Could not save the list order. <reason>" (error tone); logged at warn
```

New snippets (create, voice take, materialize, merge result) come from the server with a rank at the top of
the custom order (merge keeps the first block's rank). In Custom they show first, like in Last edited.

### Interactions

- **Filters (tag expression, in-canvas search)**: only visible rows are neighbours. In Custom the new key
  lies between the visible rows above and below the drop. Hidden rows keep their ranks, so a hidden row
  between P and N stays on whichever side its rank puts it. In another sort the re-rank includes the hidden
  rows, in the same sort, so they keep their relative place once the filter clears.
- **Frozen order while editing**: with an editor open there are no handles, Alt+arrows do nothing, and
  `reorder` refuses with `editing`. An open editor also cancels a drag. So the frozen order can never be
  the basis of a reorder.
- **Multi-select**: a drag or Alt+arrow neither reads nor changes the selection, and the handle's pointer
  and click events never reach the row's click handler. Shift-range selection uses the rendered order, so
  after a reorder the range follows the new order.
- **Canvas switch mid-flight**: the request still goes to the canvas it was made on. A rollback is skipped
  when another canvas is open (its reload brings the server's ranks).
- **Concurrent reorders**: each one is applied at once and sent in order. A rollback only restores ranks
  that still hold that reorder's value, so it never undoes a later move of the same row. If a re-rank fails
  after later single moves were sent, those moves keep their (still valid) keys.

## Files

| File | Role | Key exports |
|------|------|-------------|
| `frontend/src/lib/listMode/rank.ts` | fractional-indexing 3.2.0 port | `keyBetween`, `nKeysBetween`, `validateRank`, `compareRanks`, `RankError` (`kind`: `invalid_key` / `out_of_order` / `exhausted`), `RankCheck`, `RankInvalidReason`, `BASE_62_DIGITS`, `MAX_RANK_LENGTH` |
| `frontend/src/lib/listMode/sort.ts` | The four sorts | `ListSort`, `LIST_SORTS`, `LIST_SORT_LABELS`, `DEFAULT_LIST_SORT`, `parseListSort`, `compareForSort`, `sortSnippets`, `sortText`, `Sortable` |
| `frontend/src/lib/listMode/sortPref.ts` | Per-device persistence | `LIST_SORT_STORAGE_KEY`, `loadListSort`, `saveListSort` |
| `frontend/src/lib/listMode/reorder.ts` | Pure reorder planning, keyboard step, pointer slots | `planReorder`, `ReorderRequest`, `ReorderPlan`, `RankChange`, `ReorderRefusal`, `stepTarget`, `dropSlot`, `slotToIndex`, `isNoopSlot` |
| `frontend/src/lib/listMode/rankPatch.ts` | Rank patches on a snippet map (conditional with `expect`) | `patchRanks`, `RankPatch` |
| `frontend/src/lib/listMode/index.ts` | Barrel | |
| `frontend/src/stores/listOrderStore.ts` | Sort state, optimistic reorder, serial send, rollback, notice | `useListOrderStore` (`sort`, `setSort`, `reorder`), `ReorderMove`, `ReorderOutcome` |
| `frontend/src/stores/canvasStore.ts` | `applyListRanks(patches)`: live map and snapshots, no history entry | |
| `frontend/src/api/placements.ts` | `setListRank`, `setListRanks`, `batchPosition(BatchPositionItem[])`, `SnippetUpdateBody.list_rank`, `ListRankUpdate` | |
| `frontend/src/api/client.ts` | `SnippetCreateBody.list_rank?` | |
| `frontend/src/lib/placements/errors.ts` | `invalid_list_rank` code, `save_list_order` operation | |
| `frontend/src/types/index.ts` | `Snippet.list_rank`, `BatchPositionItem` (replaces `PositionUpdate`) | |
| `frontend/src/components/list/ListSortMenu.tsx` | Sort select | default `ListSortMenu`, `ListSortMenuView` |
| `frontend/src/components/list/useRowDrag.ts` | Pointer drag on a handle | `useRowDrag`, `RowDrag` |
| `frontend/src/components/list/ListRow.tsx` | Handle, Alt+Up/Down, dragging fade | `RowReorder` |
| `frontend/src/components/list/ListView.tsx` | Sort, drop indicator, refocus after a keyboard move | |
| `spec/list_rank_vectors.json` | Shared vectors (copied from the backend branch, identical bytes) | |

Tests: `lib/listMode/{rank,rank.vectors,sort,sortPref,reorder,rankPatch}.test.ts`,
`stores/{listOrderStore,canvasStore.listRank}.test.ts`, `api/placements.listRank.test.ts`,
`components/list/listViews.test.tsx`.

## Invariants

- Keys are generated only by `keyBetween` / `nKeysBetween` and compared only by `compareRanks` (code unit
  order). Locale collation is used for A-Z text only, never for ranks.
- Every sort is total and deterministic (the last tie-break is `id`).
- A reorder sends exactly one request: one layout PUT in Custom, or one batch-position call holding every
  placement on the canvas when adopting another sort (or when neighbours cannot be bisected).
- No reorder is planned or sent while an editor is open.
- Reorders reach the server in the order they were made (serial queue).
- A rollback only touches ranks that still hold the failed reorder's value.
- A reorder never creates an undo entry. Every undo/redo snapshot carries the current ranks.
- Reading or writing the sort never throws. The fallback is Last edited.

## Decisions

- **Rank characters**: `validateRank` (and the bounds of `keyBetween`) also reject characters outside
  0-9A-Za-z. Plain 3.2.0 `validateOrderKey` does not check them. The backend does, and the shared vectors
  require it (`"a0!"`, `"a0é"` are invalid).
- **Tie fallback**: in Custom, when the visible neighbours cannot be bisected (equal ranks), the drop
  re-ranks the whole custom order with one batch call instead of failing.
- **Same-place drop**: refused locally (`unchanged`), in every sort, so it sends nothing and keeps the sort.
- **No undo**: see Non-scope.
- **A-Z text**: display text, trimmed, so leading rest marks and whitespace do not sort first. Android
  should use the same rule.
