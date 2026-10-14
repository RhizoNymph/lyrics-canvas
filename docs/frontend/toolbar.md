# Toolbar & Tag Filter

Top-level control bars for canvas editing and tag management.

## Toolbar

**File:** `src/components/toolbar/Toolbar.tsx`

Top bar with canvas title, zoom controls, undo/redo, and tool toggles.

### Button Groups

| Group | Controls |
|-------|----------|
| View | `List` / `Canvas` toggle (Ctrl+Shift+L): `uiStore.toggleViewMode`, highlighted in list mode; see [list_mode.md](../features/list_mode.md) |
| Zoom | `-` (zoom / 1.2), `%` display, `+` (zoom * 1.2), Reset (hidden in list mode) |
| History | Undo (Ctrl+Z), Redo (Ctrl+Shift+Z) |
| Tools | Search (Ctrl+F), Rhymes (toggle), t-SNE (toggle; hidden in list mode) |
| Merge | `Merge ⇣` (vertical, Ctrl+Shift+V), `Merge ⇢` (horizontal, Ctrl+Shift+H); disabled unless 2+ snippets are selected; followed by the merge notice when `mergeError` is set |
| Export | Export button (right-aligned) |

### Zoom Behavior

- Zoom in: `zoom * 1.2`, capped at 5
- Zoom out: `zoom / 1.2`, minimum 0.1
- Reset: `{ x: 0, y: 0, zoom: 1 }`

### t-SNE Toggle

```
If tsneMode active -> exitTSNE() (restores saved positions)
If tsneMode inactive -> POST /api/canvases/{id}/tsne
                     -> enterTSNE(positions)
```

### Rhymes Toggle

```
uiStore.toggleRhymeSidebar()
  open  -> CanvasPage mounts RhymeSidebar -> rhymeStore.enter() (queries immediately with a selection)
  close -> RhymeSidebar unmounts -> rhymeStore.exit() (list cleared; snippets untouched)
```

Uses the active style while the sidebar is open. See [rhyme-view.md](./rhyme-view.md).

### Merge Buttons

```
canvasStore.mergeSelected('vertical' | 'horizontal')
  -> replaces the selected snippets with one merged snippet (see features/merge_blocks.md)
```

`canMerge = selectedSnippetIds.size >= 2`; otherwise the buttons are `disabled` with a dimmed style. When
`canvasStore.mergeError` is non-null a red status span follows the buttons: "Merge failed: ..." (the
server merge is one transaction, so a failure changed nothing). Clicking it calls `clearMergeError()`.

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| canvasStore | `canvasTitle`, `canvasId`, `viewport`, `setViewport`, `undo`, `redo`, `tsneMode`, `enterTSNE`, `exitTSNE`, `selectedSnippetIds`, `mergeSelected`, `mergeError`, `clearMergeError` |
| uiStore | `setSearchModalOpen`, `setExportDialogOpen`, `toggleRhymeSidebar`, `rhymeSidebarOpen` |

### Styling

- Background: #16162a
- Standard button: #2a2a4a
- Active toggle: #6366f1 (indigo)

---

## TagFilterBar

**File:** `src/components/toolbar/TagFilterBar.tsx`

Horizontal tag pills below the toolbar for filtering and managing tags.

### State

| State | Type | Purpose |
|-------|------|---------|
| `showCreate` | boolean | Inline creation form visibility |
| `newName` | string | New tag name input |
| `newColor` | string | Color picker value (default #6366f1) |

### Tag Display

Each tag is a pill-shaped button:

| State | Background | Text |
|-------|------------|------|
| Active (filtered) | Tag's color | White |
| Inactive | Dark (#2a2a4a) | Gray |

### Interactions

| Action | Effect |
|--------|--------|
| Click tag pill | Toggle tag filter (shows only snippets with that tag) |
| Click x on tag | Delete tag |
| Click "+ Tag" | Show inline create form |
| Enter in name input | Create tag with name and color |

### Visibility

- Hidden entirely when no tags exist and create form is closed
- Shows "+ Add Tag" minimally when no tags but not creating

### Store Dependencies

Uses `canvasStore`: `tags`, `activeTagFilters`, `toggleTagFilter`, `createTag`, `deleteTag`
