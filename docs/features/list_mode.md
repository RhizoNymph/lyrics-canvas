# List Mode

A toggle on the canvas page that swaps the spatial canvas for a vertically scrollable list of the
canvas's snippets, last edited first. Each row shows the snippet's type header, tags and beat grid;
rows can be selected, merged, deleted and edited in place. The web and Android clients implement it
against the same contract.

## Shared contract

- **Order**: descending by `content_updated_at` (ISO-8601, set by the server; bumped only by content
  changes, meaning text, type or grid, never by moves), ties broken by `created_at` descending and then
  `id` ascending. Timestamps compare as instants, not strings. A missing or unparseable timestamp sorts
  as the oldest. The field comes from the backend change on `feat/snippet-content-timestamp`.
- **Visibility**: the same tag-filter rule as the canvas. With no active tag filter every snippet
  shows; otherwise a snippet shows when it carries at least one active tag.
- **Selection**: list and canvas share one selection, so switching views keeps it. Merge (vertical or
  horizontal) and delete act on that selection. Merge order is whatever the server merge defines
  (canvas position), not list order (see [merge_blocks.md](./merge_blocks.md)).
- **Re-sort timing**: a row whose content was just saved moves to the top once the save response is in
  the client's state. While an editor is open, rows keep their positions so nothing moves under the
  pointer.
- **Mode persistence**: the chosen view (canvas or list) is a per-viewer preference stored on the
  device, never on the server.

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

To be documented by the Android list-mode change (`feat/android-list-mode`).
