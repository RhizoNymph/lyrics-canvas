# List Mode

A toggle on the canvas screen that swaps the spatial canvas for a vertically scrolling list of the
canvas's snippets, last edited first. Rows show the same card as the canvas (type header and colour,
beat grid, stress, counts) and keep the canvas's tap-to-edit and selection actions.

## Shared contract

_Placeholder; the web branch (`feat/web-list-mode`) writes the full section, and the merge reconciles
it with this one._

- Order: `content_updated_at` descending (the snippet field added by `feat/snippet-content-timestamp`;
  it changes only on content edits — text, type, grid — never on moves or resizes), then `created_at`
  descending, then `id` ascending.
- Rows do not re-sort while the user is looking at them; the order is captured when the list is shown
  and after returning from the editor.
- Tapping a row opens the snippet's editor; selection, merge and delete are the canvas's.

## Web

_Placeholder; written by `feat/web-list-mode`._

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
