# Modals

Full-screen overlay dialogs for search and export.

## SearchModal

**File:** `src/components/modals/SearchModal.tsx`

Full-screen search dialog using ILIKE-based text search across canvas snippets.

### State

| State | Type | Purpose |
|-------|------|---------|
| `query` | string | Search input |
| `results` | SearchResult[] | Results from API |
| `loading` | boolean | Search in progress |

### Interactions

- Opened by Ctrl+F (handled in CanvasPage) or Search button in Toolbar
- Enter key in input triggers search
- Clicking a result navigates to that snippet and closes the modal
- Clicking the backdrop closes the modal
- Input auto-focuses on open via `inputRef`

### Navigation

```typescript
const s = snippets.get(snippetId);
setViewport({ x: -s.position_x + 400, y: -s.position_y + 300 });
selectSnippet(snippetId);
setSearchModalOpen(false);
```

### API Call

```
GET /api/canvases/{canvasId}/search?q=<query>
Response: SearchResult[]
```

### Result Display

Each result shows:
- Left border colored by snippet type
- Snippet type label (uppercase)
- First 120 chars of raw_text

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| uiStore | `searchModalOpen`, `setSearchModalOpen` |
| canvasStore | `canvasId`, `setViewport`, `selectSnippet`, `snippets` |

---

## ExportDialog

**File:** `src/components/modals/ExportDialog.tsx`

Export format selection dialog with download trigger.

### State

| State | Type | Purpose |
|-------|------|---------|
| `format` | string | Selected format (default: 'markdown') |
| `loading` | boolean | Export in progress |

### Formats

| Format | Extension | Description |
|--------|-----------|-------------|
| Markdown | .md | Formatted with `##` headers and `#tag` suffixes |
| Plain Text | .txt | Simple text, one section per snippet |
| PDF | .pdf | Printable with Helvetica font layout |

### Export Flow

```
User selects format -> Clicks "Export"
  -> POST /api/canvases/{canvasId}/export { format }
    -> Response blob received
      -> Create temporary <a> element with blob URL
        -> Trigger download with filename: "{canvasTitle}.{ext}"
          -> Revoke object URL
            -> Close dialog
```

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| uiStore | `exportDialogOpen`, `setExportDialogOpen` |
| canvasStore | `canvasId`, `canvasTitle` |

### Styling

- Selected format: #2a2a5a background, #6366f1 border
- Unselected format: default dark background
