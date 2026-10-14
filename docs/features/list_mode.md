# List Mode

A toggle on the canvas screen that swaps the spatial canvas for a vertically scrollable list of the
canvas's snippets, last edited first. Each row shows the snippet's type header and beat grid; rows can
be selected, merged, deleted and opened in the editor. The web and Android clients implement it
against the same contract; platform differences are noted per item.

## Shared contract

- **Order**: descending by `content_updated_at` (set by the server; bumped only by content changes,
  meaning text, type or grid, never by moves; see
  [snippet_content_timestamp.md](./snippet_content_timestamp.md)), ties broken by `created_at`
  descending and then `id` ascending. Timestamps compare as instants, not strings (the backend sends
  `str(datetime)`, e.g. `2026-10-13 16:49:57.893996+00:00`). A missing or unparseable timestamp sorts
  as the oldest.
- **Visibility**: the same tag-filter rule as the canvas. With no active tag filter every snippet
  shows; otherwise a snippet shows when it carries at least one active tag. Android has no tag filter
  yet, so it lists every snippet.
- **Selection**: list and canvas share one selection, so switching views keeps it. Merge (vertical or
  horizontal) and delete act on that selection. Merge order is whatever the server merge defines
  (canvas position), not list order (see [merge_blocks.md](./merge_blocks.md)).
- **Re-sort timing**: existing rows never move under the pointer or finger. A row whose content was
  just saved moves to the top once the save response is in the client's state (web: when the in-row
  editor closes; Android: when the editor screen's save returns). Snippets created in the meantime
  (merge results, materialized rhymes) appear at the top; deleted ones drop out.
- **Mode persistence**: the chosen view is per viewer and never stored on the server (web:
  `localStorage`; Android: the screen's `SavedStateHandle`, so not across app launches).

## Web

### Scope

- Toolbar toggle (`List` / `Canvas`) and Ctrl/Cmd+Shift+L. The mode is persisted in `localStorage`
  under `lyrics-canvas.viewMode`. Every storage access is guarded, so a missing or throwing storage
  falls back to the canvas.
- `ListView` replaces `CanvasViewport` in `CanvasPage` while the mode is `list`. Zoom and t-SNE controls
  are hidden in list mode because they only affect the canvas.
- Each row has the card's header (type colour and label, tag pills) and a read-only DOM beat grid
  (`GridPreview`) drawn with the canvas card's geometry, labels and tones. Snippets with no grid fall
  back to their raw text.
- Clicking a row opens the existing `InlineGridEditor` in place of the row, using the `inline`
  placement (see below). Text and type editing, chunk drag, rests and line reorder all work as on the
  canvas. Enter on a focused row also opens it.
- Multi-select, merge, delete, the context menu and undo/redo all use the existing store actions,
  toolbar buttons and page shortcuts.

### Non-scope

- Creating snippets from the list (use the canvas: double-click).
- Reordering snippets by hand, and sort orders other than last-edited.
- Moving, resizing, grouping and t-SNE (canvas-only spatial features).
- Virtualising long lists.

### Data and control flow

```
Toolbar "List" / Ctrl+Shift+L
  -> uiStore.toggleViewMode -> setViewMode(mode)
       saveViewMode(browserStorage(), mode)        (typed result; failures logged, never thrown)
       set { viewMode, editingSnippetId: null, contextMenu: null }
  -> CanvasPage renders <ListView/> instead of <CanvasViewport/>

ListView render
  visibleSnippets(snippets, activeTagFilters)      lib/canvas/visibility (also used by CanvasViewport)
  -> sortForList(...)                              lib/listMode/order: content_updated_at desc,
                                                   created_at desc, id asc
  -> useFrozenOrder(sortedIds, editingSnippetId !== null)
       captures the order when an editor opens; applyFrozenOrder keeps it while open
       (removed ids drop out, ids added since the freeze go on top)
  -> ListRow per id (selected = selectedSnippetIds.has(id), editing = editingSnippetId === id)

row click (e)
  -> listClick({ selection, anchor, order }, id, { shift, toggle: ctrl || meta })
       plain:     selection = {id}, anchor = id, openEditor
       ctrl/cmd:  toggle id (same as a shift-click on the canvas), anchor = id
       shift:     add the visible range anchor..id; with no anchor in the list it toggles id
  -> canvasStore.setSelection(result.selection)    (drops ids not on the canvas)
  -> uiStore.setEditingSnippet(id) when openEditor

row right-click -> uiStore.setContextMenu(...) and select the row unless it is already selected
                   (the same SnippetContextMenu as the canvas: tags, merge, delete)
background click (not editing) -> clearSelection

editing (editingSnippetId === row id)
  ListRow renders <InlineGridEditor placement={{ kind: 'inline' }}/>
  save (click outside / Esc) -> canvasStore.updateSnippet -> server response replaces the snippet
                                (new content_updated_at)
                             -> uiStore.closeEditor(id)   (no-op if another editor has opened)
  editor closes -> unfreeze -> the edited row sorts to the top

merge / delete / undo (toolbar, context menu, Ctrl+Shift+V/H, Delete, Ctrl+Z / Ctrl+Shift+Z)
  -> the existing canvasStore actions, which act on selectedSnippetIds. Undo restores the snapshot,
     including the old content_updated_at, so the list order goes back with it.
```

#### How the editor opens

On the canvas, `InlineGridEditor` is a `position: fixed` overlay at the card's screen position,
scaled by zoom and card scale. It now takes a placement:

```ts
type EditorPlacement =
  | { kind: 'canvas'; viewport; containerRef }   // fixed overlay, as before
  | { kind: 'inline' };                          // normal flow inside the row, scale 1, full width,
                                                 // the grid body scrolls sideways when wide
```

Opening the editor inside the row reuses all of the editor's state, keyboard, syllabify and save code
without measuring the DOM. An overlay would have needed row rectangles and scroll tracking. The editor
is keyed by snippet id in both views, so switching straight from one snippet to another starts fresh.

### Files

| File | Role | Key exports |
|------|------|-------------|
| `frontend/src/lib/canvas/visibility.ts` | Tag-filter visibility used by both views | `isSnippetVisible`, `visibleSnippets` |
| `frontend/src/lib/listMode/order.ts` | Sort and freeze logic | `compareForList`, `sortForList`, `applyFrozenOrder` |
| `frontend/src/lib/listMode/selection.ts` | Row click into a selection result | `listClick`, `ListClickModifiers`, `ListSelectionState`, `ListClickResult` |
| `frontend/src/lib/listMode/viewMode.ts` | Mode type and guarded persistence | `ViewMode`, `VIEW_MODE_STORAGE_KEY`, `parseViewMode`, `browserStorage`, `loadViewMode`, `saveViewMode`, `ViewModeStorageError`, `SaveViewModeResult` |
| `frontend/src/lib/listMode/index.ts` | Barrel | |
| `frontend/src/stores/uiStore.ts` | `viewMode`, `setViewMode`, `toggleViewMode`, `closeEditor(id)` | |
| `frontend/src/stores/canvasStore.ts` | `setSelection(ids)` | |
| `frontend/src/types/index.ts` | `Snippet.content_updated_at` | |
| `frontend/src/components/list/ListView.tsx` | List orchestration: order, clicks, context menu, empty states | default `ListView` |
| `frontend/src/components/list/ListRow.tsx` | One row: header, tags, `GridPreview`, or the inline editor | default `ListRow` |
| `frontend/src/components/list/GridPreview.tsx` | Read-only DOM beat grid | default `GridPreview` |
| `frontend/src/components/list/useFrozenOrder.ts` | Freezes row order while an editor is open | `useFrozenOrder` |
| `frontend/src/components/canvas/gridCellText.ts` | Cell label, tone kind and colours shared by `gridDraw` (Pixi) and `GridPreview` (DOM) | `cellTextKind`, `cellLabel`, `GRID_TEXT_COLORS`, `GRID_ROW_BAND`, `GRID_LINE_COLOR` |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | Editor, now with `placement` | `EditorPlacement` |
| `frontend/src/components/canvas/CanvasViewport.tsx` | Uses `visibleSnippets`; passes the `canvas` placement | |
| `frontend/src/components/toolbar/Toolbar.tsx` | Toggle button; hides zoom and t-SNE in list mode | |
| `frontend/src/components/pages/CanvasPage.tsx` | Switches the view; Ctrl/Cmd+Shift+L | |

Tests: `lib/canvas/visibility.test.ts`, `lib/listMode/{order,selection,viewMode}.test.ts`,
`components/canvas/gridCellText.test.ts`, `stores/uiStore.listMode.test.ts`,
`stores/canvasStore.selection.test.ts`.

### Invariants

- The list and the canvas use the same visibility predicate (`isSnippetVisible`); neither has its own
  copy.
- There is one selection (`canvasStore.selectedSnippetIds`). List mode never keeps a separate one; the
  shift-click anchor is only view-local UI state.
- While `editingSnippetId` is non-null, the relative order of rendered rows does not change. Only ids
  that are new since the freeze can appear, at the top.
- Changing the view closes the editor and the context menu, because their placement belongs to the old
  view. A pending edit is saved first by the editor's click-outside handler, since the toggle is a
  click outside it.
- A finished save only closes the editor it belongs to (`closeEditor(id)`).
- Reading or writing the view mode never throws. The fallback is `canvas`.

## Android

### Scope

- A "List" / "Canvas" text button in the canvas top bar (shown once the canvas has loaded) switches the
  body of the canvas screen between `SnippetCanvas` and `SnippetList` (a `LazyColumn`).
- Every snippet of the canvas is listed: Android has no tag filter yet, so there is nothing to filter by.
- Order: `sortForList` (`content_updated_at` desc, `created_at` desc, `id` asc), compared as parsed
  instants, not strings.
- Rows are the canvas card (`cardLayout` + `drawCard`), resized for the row: the full grid width plus
  the count gutter (scaled down to fit the row, at most to 0.5×; past that the row scrolls
  horizontally), the full grid height, or the measured raw text for snippets without grid lines.
- Tap a row: opens the grid editor (`CanvasEvent.OpenEditor`, the same route as from the canvas).
  While selecting, a tap toggles the row.
- Long-press a row: enters selection mode / adds the row (with the platform long-press haptic). The
  selection is the canvas's `Selection`, so the contextual bar (Merge ⇣ / ⇢, Delete, Rhymes, ✕), the
  rhyme sheet and the back gesture work the same in both modes, and the selection survives switching.
- The mode is kept in the canvas ViewModel's `SavedStateHandle` (rotation and process death).
- The FAB "New snippet" stays; it creates at the canvas viewport centre as on the canvas.

### Non-scope

- A tag filter (none on Android yet), search, or any other ordering.
- Moving, resizing or reordering snippets from the list (long-press does not start a move).
- Remembering the mode across app launches (it lives in the back-stack entry's saved state only).
- Live re-sorting: rows that change in place keep their position until the next capture.

### Data and control flow

```
GET /api/canvases/{id} -> CanvasViewModel.reload
  -> Ready(mode = modeFor(saved kind, snippets))       saved kind = SavedStateHandle["canvas_mode"]
       CANVAS -> CanvasMode.Canvas
       LIST   -> CanvasMode.SnippetList(ListOrder.of(snippets))      (sortForList, ids captured)
Top bar "List" / "Canvas" -> setMode(kind)
  -> SavedStateHandle["canvas_mode"] = kind.name (also before the canvas has loaded)
  -> Ready.mode = modeFor(kind, current snippets)                  (showing the list sorts afresh)
CanvasScreen: Ready.mode
  Canvas      -> SnippetCanvas (unchanged)
  SnippetList -> SnippetList(rows = Ready.listRows, selected = selection.ids)
                 Ready.listRows = order.arrange(snippets)   (lazy; unseen ids first, sorted; gone ids dropped)
Row tap        -> CanvasViewModel.onTap(id): Idle -> CanvasEvent.OpenEditor(id) -> EditorRoute
                                             Selecting -> toggle id
Row long-press -> CanvasViewModel.onListLongPress(id): select(id) (no move origin recorded)
Merge / Delete -> unchanged ViewModel actions; Ready.snippets changes, the order does not:
                  the merged snippet is unseen, so it shows on top; deleted ids drop out
Editor save    -> CanvasViewModel.replaceSnippet(saved) -> resortList(): a new ListOrder
Materialize    -> insertMaterialized -> unseen, shown on top until the next capture
```

Row drawing (`SnippetList.kt`): `BoxWithConstraints` gives the row width (minus the 16 dp gutters
and the 4 dp shadow). Per row, remembered on (snippet, width, styles):
`listCardWidth(snippet, availableDp)` -> `ListCardWidth(width, scale)`; without grid lines, the
fallback text from `cardLayout` is measured with the card's fallback style at the inner card width;
`listCardHeight(snippet, measured)`; then `cardLayout(listCard(snippet, width, height))` and a Compose
`Canvas` of `(width × scale + 4 dp, height × scale + 4 dp)` that calls `drawCard(zoom = scale)`, with
the selection border when the row is selected. The `LazyColumn` items are keyed by snippet id.

### Files and key exports

| File | Role / key exports |
|---|---|
| `android/core/model/.../model/Snippet.kt` | `Snippet.contentUpdatedAt` (`content_updated_at`, non-null string like `updated_at`) |
| `android/core/logic/.../logic/list/ListOrder.kt` | `parseServerTimestamp` (ISO-8601 and the backend's `str(datetime)` form; no offset = UTC; null if unreadable), `sortForList`, `ListOrder` (private constructor; `of`, `ids`, `arrange`) |
| `android/app/.../app/canvas/CanvasUiState.kt` | `CanvasModeKind` (`CANVAS`, `LIST`, `parse`), sealed `CanvasMode` (`Canvas`, `SnippetList(order)`), `Ready.mode`, `Ready.listRows` |
| `android/app/.../app/canvas/CanvasViewModel.kt` | `CANVAS_MODE_KEY`, `setMode`, `onListLongPress`, `replaceSnippet` (re-sorts the list), `savedState` constructor parameter |
| `android/app/.../app/canvas/CanvasScreen.kt` | Picks `SnippetCanvas` or `SnippetList` by `Ready.mode`; `canvasViewModel` passes `createSavedStateHandle()` |
| `android/app/.../app/canvas/CanvasTopBars.kt` | `CanvasTopBar(title, mode, rhymesOpen, onBack, onSetMode, onToggleRhymes)` |
| `android/app/.../app/canvas/list/ListCardSize.kt` | `ListCardMetrics`, `ListCardWidth`, `listCardNeededWidth`, `listCardWidth`, `listCardHeight`, `listCard` (pure) |
| `android/app/.../app/canvas/list/SnippetList.kt` | `SnippetList(rows, selected, onTap, onLongPress)` |

Tests: `core/logic/.../logic/list/ListOrderTest.kt` (timestamp parsing, ordering, tie-breaks,
`ListOrder.arrange`), `app/.../canvas/list/ListCardSizeTest.kt` (row sizing),
`app/.../canvas/CanvasViewModelListModeTest.kt` (mode toggle, saved state, capture points, row tap /
long-press, shared selection, merge and delete from the list).

### Invariants

- A `CanvasMode.SnippetList` always carries a captured `ListOrder`; a `ListOrder` only comes from
  `ListOrder.of` (sorted), so the list can never be shown unsorted.
- The order is captured only when the list is shown (toggle, load / reload, restore) and on an editor
  save; no other state change reorders existing rows.
- `listRows` is exactly the current `snippets` (each id once, current copies): new ids first, then the
  captured order minus deleted ids.
- Sorting is total and deterministic: equal timestamps fall back to `created_at`, then `id`; an
  unreadable timestamp sorts after every readable one.
- List mode never changes snippet geometry: rows draw a resized copy that is never sent, and a list
  long-press records no move origin, so no batch-position request can come from the list.
- Selection is one `Selection` for both modes; switching modes neither clears nor changes it.
