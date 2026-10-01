# State Management

Four Zustand stores managing authentication, canvas data, UI state, and the rhyme sidebar session.

## authStore

**File:** `src/stores/authStore.ts`
**Hook:** `useAuthStore`

### State

| Field | Type | Initial | Persistence |
|-------|------|---------|-------------|
| `user` | `User \| null` | null | Memory only |
| `token` | `string \| null` | from localStorage | localStorage |
| `loading` | boolean | false | Memory only |

### Actions

| Action | Signature | Side Effects |
|--------|-----------|--------------|
| `login` | `(email, password) -> Promise<void>` | Saves token to localStorage, fetches user |
| `register` | `(email, password, displayName) -> Promise<void>` | Saves token to localStorage, fetches user |
| `logout` | `() -> void` | Removes token from localStorage |
| `loadUser` | `() -> Promise<void>` | Validates existing token, clears on failure |

### Token Flow

```
login/register -> authApi call -> save token to localStorage
                -> authApi.me() -> set user state

loadUser (on app mount) -> check localStorage for token
                        -> authApi.me() -> set user state
                        -> on failure: clear localStorage and state

logout -> remove from localStorage -> clear user and token state
```

---

## canvasStore

**File:** `src/stores/canvasStore.ts`
**Hook:** `useCanvasStore`

The primary store managing all canvas data and interactions.

### State

| Field | Type | Initial | Notes |
|-------|------|---------|-------|
| `canvasId` | `string \| null` | null | Active canvas |
| `canvasTitle` | string | '' | |
| `viewport` | `{ x, y, zoom }` | `{ 0, 0, 1 }` | Pan/zoom |
| `snippets` | `Map<string, Snippet>` | empty | O(1) lookup |
| `groups` | `Map<string, Group>` | empty | O(1) lookup |
| `tags` | `Map<string, Tag>` | empty | O(1) lookup |
| `selectedSnippetIds` | `Set<string>` | empty | |
| `activeTagFilters` | `Set<string>` | empty | |
| `snippetScales` | `Map<string, number>` | empty | Per-snippet zoom (0.3-4x) |
| `past` | `Array<{snippets, groups}>` | [] | Undo stack (50 max) |
| `future` | `Array<{snippets, groups}>` | [] | Redo stack |
| `tsneMode` | boolean | false | |
| `savedPositions` | `Map \| null` | null | Pre-t-SNE positions |
| `mergeError` | `MergeError \| null` | null | Last merge's error or first warning; shown by the toolbar |

### Canvas Operations

| Action | API Call | Notes |
|--------|---------|-------|
| `loadCanvas(id)` | `GET /api/canvases/{id}` | Populates all Maps, restores viewport |
| `setViewport(v)` | None | Local only |

### Snippet Operations

| Action | API Call | Notes |
|--------|---------|-------|
| `addSnippet(x, y, type)` | `POST /api/canvases/{id}/snippets` | Pushes undo state |
| `addSnippetFromText(text, x, y, derived_from?)` | `POST /api/canvases/{id}/snippets` | Body `{ raw_text, position_x, position_y, snippet_type: 'freestyle', derived_from }`; inserts the returned snippet, pushes undo state, returns it (`null` when no canvas is loaded). Used by the rhyme sidebar's Materialize |
| `updateSnippet(id, data)` | `PUT /api/snippets/{id}` | Pushes undo state |
| `deleteSnippet(id)` | `DELETE /api/snippets/{id}` | Pushes undo state |
| `moveSnippet(id, x, y)` | None | Local only (optimistic) |
| `commitPositions()` | `PATCH /api/snippets/batch-position` | Sends all positions |
| `mergeSelected(mode)` | `POST /api/canvases/{id}/snippets` (with `grid_data`), then `POST /api/snippets/{id}/tags` per union tag and `DELETE /api/snippets/{id}` per original | Needs 2+ selected; orders by canvas position, merges via `lib/merge`, inserts the merged snippet, removes the deleted originals, selects the result, pushes one undo state. Returns `MergeResult` (never throws); sets `mergeError` to the error or first warning. See [merge_blocks.md](../features/merge_blocks.md) |
| `clearMergeError()` | None | Resets `mergeError` |

### Selection

| Action | Behavior |
|--------|----------|
| `selectSnippet(id, multi=false)` | If multi: toggle in set. If not: replace selection. |
| `clearSelection()` | Empty the set |

### Group Operations

| Action | API Call | Notes |
|--------|---------|-------|
| `createGroup(name)` | `POST /api/canvases/{id}/groups` | |
| `deleteGroup(id)` | `DELETE /api/groups/{id}` | |
| `moveGroup(id, dx, dy)` | None | Moves group + all contained snippets locally |

### Tag Operations

| Action | API Call | Notes |
|--------|---------|-------|
| `createTag(name, color)` | `POST /api/canvases/{id}/tags` | Returns new tag ID (`string \| null`) |
| `updateTag(id, data)` | `PUT /api/tags/{id}` | Updates tag in `tags` Map and in all snippets referencing it |
| `deleteTag(id)` | `DELETE /api/tags/{id}` | |
| `toggleTagFilter(id)` | None | Toggle in activeTagFilters set |
| `addTagToSnippet(sId, tId)` | `POST /api/snippets/{sId}/tags` | Reloads entire canvas |
| `removeTagFromSnippet(sId, tId)` | `DELETE /api/snippets/{sId}/tags/{tId}` | Reloads entire canvas |

### Undo/Redo

- `pushUndoState()`: Snapshots current snippets + groups Maps. Keeps last 50 states. Clears future.
- `undo()`: Restores from past, pushes current to future
- `redo()`: Restores from future, pushes current to past

### t-SNE Mode

- `enterTSNE(positions)`: Saves current positions to `savedPositions`, applies t-SNE coordinates, sets `tsneMode = true`
- `exitTSNE()`: Restores `savedPositions`, clears, sets `tsneMode = false`

---

## uiStore

**File:** `src/stores/uiStore.ts`
**Hook:** `useUIStore`

Lightweight store for UI panel visibility and editing state.

### State

| Field | Type | Initial |
|-------|------|---------|
| `rhymeSidebarOpen` | boolean | false |
| `similarityPanelOpen` | boolean | false |
| `searchModalOpen` | boolean | false |
| `exportDialogOpen` | boolean | false |
| `editingSnippetId` | `string \| null` | null |
| `similarityTargetId` | `string \| null` | null |
| `contextMenu` | `{ snippetId: string; x: number; y: number } \| null` | null |

### Actions

| Action | Side Effect |
|--------|-------------|
| `toggleRhymeSidebar()` | Toggle boolean; `CanvasPage` mounts `RhymeSidebar` while true, which runs `rhymeStore.enter()` / `exit()` |
| `toggleSimilarityPanel()` | Toggle boolean |
| `setSearchModalOpen(open)` | Set directly |
| `setExportDialogOpen(open)` | Set directly |
| `setEditingSnippet(id)` | Set editing state |
| `setSimilarityTarget(id)` | **Also opens similarity panel** |
| `setContextMenu(menu)` | Set context menu state (snippet ID + screen position) |
| `closeContextMenu()` | Set contextMenu to null |

---

## rhymeStore

**File:** `src/stores/rhymeStore.ts`
**Hook:** `useRhymeStore`

Session state for the rhyme sidebar: `active`, query inputs (`typedAnchor`, `excludedAnchorIds`, `k`,
`minScore`, `slot`, `slotMode`, `sources`, `includeRare`), results (`anchors`, `candidates`, `total`, `offset`),
`dismissed`, `materializeCounts` (per anchor snippet id), `viewSize`, `loading`, `error: RhymeError | null`.
Nothing in it is drawn on the canvas; `materialize` is the only path that creates a snippet.

| Action | API Call | Notes |
|--------|---------|-------|
| `enter()` | `POST /api/nlp/rhyme-suggest` | Activates and queries immediately |
| `exit()` | None | Clears all session state; params persist; snippets untouched |
| `runQuery()` | `POST /api/nlp/rhyme-suggest` | Builds the request from `canvasStore.selectedSnippetIds` minus exclusions + typed anchor; stale responses dropped |
| `loadMore()` | `POST /api/nlp/rhyme-suggest` | Next page (`offset + 40`), appended |
| `dismiss(id)` | None | Removes the row; id sent as `exclude_ids` afterwards |
| `materialize(id)` | via `canvasStore.addSnippetFromText` | Creates a snippet with `derived_from` beside the single anchor (or at the viewport centre), then dismisses the id |
| `excludeAnchor(id)` / `restoreAnchor(id)` | None | Chip removal / restore; exclusions are pruned to the selection on each query |
| setters | None | `setTypedAnchor`, `setK`, `setMinScore`, `setSlot`, `setSlotMode`, `toggleSource`, `setIncludeRare`, `setViewSize` |

Errors are stored as `RhymeError` (`kind`, `operation: 'query' | 'materialize'`, `status`) and never thrown.
Full description in [rhyme-view.md](./rhyme-view.md).

---

## Design Patterns

- **Maps for collections**: O(1) lookups instead of array scans
- **Sets for selections/filters**: O(1) membership checks
- **Optimistic updates**: `moveSnippet` is local-only; `commitPositions` batches to backend on mouseup
- **Cascade reloads**: Tag operations reload entire canvas for consistency
- **Auto-open panels**: Setting a similarity target automatically opens the similarity panel
- **Undo snapshots**: Deep copies of snippet + group Maps, 50-state limit
- **Typed errors for async views**: `rhymeStore` stores a `RhymeError` instead of throwing so the sidebar can render the failure; `canvasStore.mergeSelected` returns a `MergeResult` and mirrors a `MergeError` into `mergeError`
- **Create before delete**: `mergeSelected` only deletes the originals after the merged snippet exists; later failures are warnings, never rollbacks
