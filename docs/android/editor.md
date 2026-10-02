# Android Grid Editor

The full-screen grid editor of the Android app (milestone B2): type lines, see live syllabification,
select chunks of cells, drag them along their line, insert or remove rests, reorder lines, and save. It
is the touch port of the web `InlineGridEditor` (`frontend/src/components/canvas/{InlineGridEditor,
GridRow,useChunkDrag,editorState}.ts(x)`); the row state and every grid reducer are the `:core:logic`
ports, not reimplementations. Contract: `docs/features/android_app.md` (touch mapping, grid editor
rows); behaviour: `docs/features/grid_rearrange.md`.

## Scope

- `GridEditorRoute` (the canvas navigates here with a `Snippet`; `onSaved(updated)` / `onClose()`).
- `GridEditorViewModel` (`StateFlow<EditorUiState>`) wrapping `EditorRowsState`.
- Screen: top app bar (snippet type, Discard, Save), vertically scrolling rows, per-row cell strip,
  row menu, chunk toolbar, active-row text field above the IME, hint line.
- Gestures: tap select, extend mode, selection handles, long-press chunk drag with drop indicator and
  edge auto-scroll, long-press row drag to reorder.

## Non-scope

- Undo (none in v1, as the web), cross-line chunk moves, editing a syllable's text or stress, fixed grid
  mode / `beats_per_line` editing, tags.
- Vertical auto-scroll while dragging a row (rows are moved within the visible area, or with ▲ / ▼).
- Any change to `:core:*`.

## Screen

```
TopAppBar   [VERSE ▾]                                  Discard  [Save]
Rows (Column + verticalScroll, imePadding below the Scaffold insets)
  ≡  1  | hel | lo | · | world |  →scrolls→         3  ⋮
        ●                   ●        <- selection handles (row with a selection)
        +·  −  ◀  ▶  [Extend]  Clear <- chunk toolbar (row with a selection)
  ≡  2  | ... |                                     4  ⋮
        [ Type lyrics…                 ]  <- active row only, IME action Next = new line
  + Add line
Hint line (+ "local estimates" note after a syllabify failure)
```

- Cells are 56 dp x 48 dp (contract: >= 48 dp), monospace, stressed syllables bold, rests `·`,
  selected cells highlighted (dimmed while dragging), the active row has a trailing empty cell (web).
- Row number: tap to open the row's text field. ≡: long-press and drag to reorder. ⋮: Edit text,
  ▲ Move up, ▼ Move down, Insert line below, Delete line.
- Blank rows show "(empty line)"; tapping one opens its field.
- Back: clears the selection, else closes the active field, else saves (the web's Escape /
  click-outside). Discard closes without saving.
- Save failure: snackbar with the server message; edits are kept and Save can be retried.

## ViewModel

`GridEditorViewModel(snippet, nlp: NlpRepository, snippets: SnippetRepository)` implements
`GridEditorActions` (the screen's only dependency on it).

```
init      EditorUiState.initial(snippet): initEditorState(raw_text, grid_data)
            (matching saved lines kept and arranged; active row -1, or 0 for an empty snippet)
          viewModelScope: state.map(joined text).distinctUntilChanged().debounce(300 ms)
            .filter(non-blank && needsSyllabify).mapLatest(nlp.syllabify)
            Success -> mergeSyllabified (arranged rows untouched), syllabifyError = null
            Failure -> syllabifyError = error (rows keep their local estimates)
typeLine  typeLine reducer (local estimate now, arranged cleared); clears a selection in that row
tapCell   tapSelection(sel, row, col, extendMode)
beginChunkDrag(row, col)  long-press: keep the selection if it holds col, else select col
resizeSelection(edge, col) handle drag: resizeSelection (never crosses the other edge)
dropSelectionAt(gap)      moveTargetFromGap(gap, start, end) ?: no-op -> moveSelection(to)
nudgeSelection(±1)        moveSelection(start ± 1)
moveSelection(to)         landing = clamp(to, 0, cells - size); landing == start -> no-op;
                          moveChunkInRow (text rebuilt, row arranged); selection follows the landing
insertRestBeforeSelection insertRestInRow(row, start); selection shifts right by one
removeSelectedRests       only when every selected cell is a rest: removeCellsInRow; selection cleared
reorderRow(from, to)      reorderRows (text, grid line, arranged, active row move together);
moveRow(row, ±1)          selection row via indexAfterReorder
save      SnippetUpdateRequest(snippet_type, grid_data, raw_text = rawTextForSave) -> PUT
            Saving -> Saved(updated snippet) | Failed(ApiError); ignored while Saving / after Saved
```

Every mutation goes through one `update` that, after the reducer, drops a selection that no longer fits
its row's cells (e.g. a syllabify result with fewer cells for a row that was only selected, never
arranged).

`GridEditorRoute` creates the ViewModel with `viewModel(key = "grid-editor-<id>-<visit>")`, where
`visit` is a `rememberSaveable` UUID: the ViewModel survives rotation but a reopened editor starts
fresh even if the host keeps one `ViewModelStore` across visits. It calls `onSaved(snippet)` once when
the state becomes `Saved`.

## Gestures (CellStrip)

All strip gestures run in one `pointerInput` on the strip's content (inside `horizontalScroll`), so
pointer positions are content coordinates and the scroll offset is already applied. Geometry comes from
the first cell's `onGloballyPositioned` bounds (`CellMetrics(originX, cellWidth, cellHeight)`).

```
down below the cells near a handle (handleAt, 24 dp slop)  -> drag: resizeSelection(edge, cellIndexClamped(x))
down on the cells, lifted before the long-press timeout     -> tap: tapCell(cellIndexAt(x)) or, past the cells, activateRow
down, moved past touch slop first                           -> nothing (horizontalScroll takes it)
down held for the long-press timeout on a cell              -> haptic, beginChunkDrag(col), dragging = true
  move                                                      -> pointerX = x; dropGap = dropGapAt(x) (core gapFromX)
  every frame while dragging                                -> edgeAutoScrollDelta(pointerX - scroll, width, 48 dp, 14 dp)
                                                               scrollBy(delta); pointerX += consumed
  up                                                        -> dropSelectionAt(dropGapAt(pointerX)); cancel -> nothing
```

The drop indicator is a 2 dp bar at `gapX(dropGap)`; handles are dots under the selection's edges.
Horizontal scrolling is disabled while a chunk drag is in progress.

Row reorder: `detectDragGesturesAfterLongPress` on the ≡ handle; the dragged row is translated with
`graphicsLayer` and raised with `zIndex`; row bounds come from `onGloballyPositioned`; the target is
`reorderTargetIndex(spans, from, dy)` (the dragged row passes a neighbour once its centre enters that
neighbour's span)
and a 3 dp line marks where it will land; drop calls `reorderRow(from, target)`.

Active row: `ActiveLineField` keeps its own `TextFieldValue` (cursor, IME composition) and pushes text
to the ViewModel; it takes the row's text back only when the row became arranged (a chunk edit rebuilt
the text). Typing always clears `arranged`, so a stale frame can never overwrite typed text. It
requests focus when it appears; the row calls `bringIntoView` when it becomes active; the screen
column has `imePadding()` (the activity uses `adjustResize`).

## Invariants

- All row/grid state changes go through the `:core:logic` reducers; `EditorRowsState`'s invariant
  (one grid line per non-blank row, arranged rows never replaced by syllabify) holds on Android.
- `EditorUiState.selection` always lies within its row's cells (`CellSelection` is never empty).
- A chunk drop or nudge that would not move anything leaves the state object unchanged (no arranged
  flag, no recomposition).
- The syllabify request is keyed on the joined text; a newer text cancels the in-flight request
  (`mapLatest`) and a merged result never re-triggers it.
- Save sends `raw_text` from `rawTextForSave` (arranged rows spelled from their grid, others as typed)
  together with `grid_data` and `snippet_type`; nothing else.
- No ViewModel call throws; failures are `SaveStatus.Failed` / `syllabifyError`.

## Files

Paths under `android/app/src/main/kotlin/dev/nymph/lyrics/app/editor/`.

| File | Role / key exports |
|---|---|
| `GridEditorRoute.kt` | `GridEditorRoute(container, snippet, onSaved, onClose, modifier)`: ViewModel per visit, `onSaved` on `Saved` |
| `GridEditorViewModel.kt` | `GridEditorViewModel`, `SYLLABIFY_DEBOUNCE_MS`, `saveRequest(state)`, `selectionIsAllRests(state)` |
| `GridEditorActions.kt` | `GridEditorActions`: every user intent the screen can send |
| `EditorUiState.kt` | `EditorUiState` (`rows`, `selection`, `extendMode`, `snippetType`, `save`, `syllabifyError`, derived `rowLines`), sealed `SaveStatus` (`Idle`, `Saving`, `Saved(snippet)`, `Failed(error)`) |
| `CellSelection.kt` | `CellSelection` (private constructor; `of`, `cell`, `contains`, `shiftedBy`, `inRow`, `fitsIn`), `SelectionEdge`, `tapSelection`, `resizeSelection`, `indexAfterReorder` |
| `DragGeometry.kt` | `CellMetrics`, `cellIndexAt`, `cellIndexClamped`, `dropGapAt` (core `gapFromX`), `handleAt`, `edgeAutoScrollDelta`, `RowSpan`, `reorderTargetIndex` |
| `ui/GridEditorScreen.kt` | Scaffold, back handling, save-error snackbar, reorderable rows column, hint line |
| `ui/EditorTopBar.kt` | Snippet type selector, Discard, Save (progress while saving) |
| `ui/EditorRow.kt` | `RowModel`, `EditorRow` (handle, number, strip, count, menu, toolbar, field) |
| `ui/CellStrip.kt` | Cells, gestures (tap / handles / long-press drag), drop indicator, auto-scroll |
| `ui/RowToolbar.kt` | `+·`, `−`, ◀, ▶, Extend chip, Clear |
| `ui/ActiveLineField.kt` | Active row `OutlinedTextField` (IME Next = new line, Backspace on empty deletes the row) |
| `ui/EditorDimens.kt` | Cell, handle, auto-scroll and control sizes |

Tests under `android/app/src/test/kotlin/dev/nymph/lyrics/app/editor/` (JUnit 5, coroutines-test,
Turbine; no Robolectric): `GridEditorViewModelTest` (fake repositories in `Fakes.kt`),
`CellSelectionTest`, `DragGeometryTest`.
