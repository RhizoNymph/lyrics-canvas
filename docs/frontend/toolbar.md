# Toolbar & Tag Filter

Top-level control bars for canvas editing and tag management.

## Toolbar

**File:** `src/components/toolbar/Toolbar.tsx`

Top bar with canvas title, zoom controls, undo/redo, and tool toggles.

### Button Groups

| Group | Controls |
|-------|----------|
| Zoom | `-` (zoom / 1.2), `%` display, `+` (zoom * 1.2), Reset |
| History | Undo (Ctrl+Z), Redo (Ctrl+Shift+Z) |
| Tools | Search (Ctrl+F), Rhymes (toggle), t-SNE (toggle) |
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

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| canvasStore | `canvasTitle`, `canvasId`, `viewport`, `setViewport`, `undo`, `redo`, `tsneMode`, `enterTSNE`, `exitTSNE` |
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
