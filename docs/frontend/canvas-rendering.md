# Canvas Rendering

GPU-accelerated 2D rendering layer using Pixi.js v7 via `@pixi/react`.

## Component Hierarchy

```
CanvasViewport (root)
  Stage (Pixi.js root, bg: 0x12122a)
    Container
      InfiniteGrid        (background grid)
      GroupContainer[]     (group bounding boxes, behind snippets)
      SnippetCard[]        (snippet cards, in front)
      RhymeLayer           (ephemeral rhyme CandidateCards, above snippets; empty unless rhyme view is active)
  InlineGridEditor         (HTML overlay — inline beat grid editing)
  SnippetContextMenu       (HTML overlay — right-click tag assignment menu)
  RhymePanel               (HTML overlay — rhyme view controls, only while active)
  CandidateTooltip         (HTML overlay — hovered/pinned candidate, only while active)
```

The container div is `position: relative` so the rhyme overlays can be positioned in canvas-local pixels. See
[rhyme-view.md](./rhyme-view.md) for the rhyme components.

## CanvasViewport

**File:** `src/components/canvas/CanvasViewport.tsx`

Main orchestrator for the Pixi.js Stage. Handles pan/zoom, double-click snippet creation, tag filtering, drag coordination, and context menu rendering. The container div has `onContextMenu={(e) => e.preventDefault()}` to suppress the browser's default right-click menu.

### Interactions

| Input | Behavior |
|-------|----------|
| Middle-mouse drag / Alt+Left-drag | Pan viewport |
| Ctrl/Cmd + Wheel | Zoom (0.9x / 1.1x step, clamped 0.1-5) |
| Regular Wheel | Pan horizontally and vertically |
| Double-click empty area | Create new snippet at world coordinates |
| Click empty area (50ms delay) | Clear selection |
| Mouse up | Commits snippet positions to backend |
| Delete / Backspace (when not editing) | Delete all selected snippets |

### Snippet Interaction Flag

Pixi's `stopPropagation()` only prevents event bubbling within the Pixi scene graph — it does not prevent the DOM `pointerdown` event from firing on the `<canvas>` element. This means the Stage's `onPointerDown` handler (`onStageClick`) fires on every click, including clicks on snippet cards.

To distinguish snippet clicks from background clicks, a module-level `_snippetInteracted` flag is used:

1. `SnippetCard.onPointerDown` (and `CandidateCard.onPointerDown`) calls `markSnippetInteraction()` (exported from `CanvasViewport.tsx`)
2. `onStageClick` sets a 50ms timer; when it fires, it checks and resets the flag
3. If the flag was set, the click was on a card — skip `clearSelection()`
4. If the flag was not set, the click was on the background — clear selection

`markSnippetInteraction()` also records a timestamp; the double-click-to-create path skips creation when a card
was interacted with in the last 500ms, so double-clicking a rhyme candidate does not drop an empty snippet
under it.

### Coordinate Transform

```
screenPos = (canvasPos + viewport) * zoom
```

For snippet creation, client coordinates are converted back to world coordinates:
```typescript
worldX = (clientX - containerLeft) / zoom - viewport.x
worldY = (clientY - containerTop) / zoom - viewport.y
```

### Tag Filtering

When `activeTagFilters` is non-empty, only snippets whose tags intersect the filter set are rendered. Groups are only rendered when they contain at least one visible snippet.

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| canvasStore | `viewport`, `setViewport`, `snippets`, `groups`, `activeTagFilters`, `addSnippet`, `clearSelection`, `commitPositions` |
| uiStore | `editingSnippetId`, `setEditingSnippet` |
| rhymeStore | `active` (mount overlays), `setViewSize` (on resize, for the typed-anchor layout centre) |

---

## gridDraw

**Files:** `src/components/canvas/gridConstants.ts`, `src/components/canvas/gridDraw.ts`

The beat-grid drawing shared by `SnippetCard` and the rhyme `CandidateCard`, extracted from `SnippetCard`
without changing its output.

- `gridConstants.ts` holds `CELL_WIDTH` (60), `CELL_HEIGHT` (24), `PADDING` (14), `HEADER_HEIGHT` (30) and
  `COUNT_COLUMN_WIDTH` (28) with no Pixi imports, so pure modules (`lib/rhyme/candidateGeometry.ts`) and tests can
  use them.
- `drawBeatGrid(g, lines, { s, left, top, maxRight })`: alternating row backgrounds (`0x242448` at 50%) and grid
  lines (`0x4a4a6a` at 50%), clipped at `maxRight`. No-op for empty input. Leaves the 1px grid line style set,
  exactly as the inline code did.
- `placeCellText(cell, row, col, { s, left, top })` -> `{ text, x, y, kind }` where `kind` is `'rest'` (the `·`
  glyph offset 35% into the cell), `'stressed'` or `'normal'`.
- `createGridTextStyles(s)` -> `{ cell, stressed, rest, count }` `TextStyle`s; `styleForKind(styles, kind)`.

`SnippetCard` keeps its own shadow, header, tag pills, selection border, resize handle, and all drag/resize/select
logic.

---

## SnippetCard

**File:** `src/components/canvas/SnippetCard.tsx`

Pixi Graphics component rendering a single snippet with colored header, beat grid cells, stress indicators, rests, resize handle, and selection border.

### Props

```typescript
interface Props {
  snippet: Snippet;
  screenX: number;
  screenY: number;
  zoom: number;
}
```

### Constants

| Constant | Value | Purpose |
|----------|-------|---------|
| `CELL_WIDTH` | 60px | Beat grid cell width |
| `CELL_HEIGHT` | 24px | Beat grid cell height |
| `PADDING` | 14px | Inner card padding |
| `HEADER_HEIGHT` | 30px | Type label bar height |
| `RESIZE_HANDLE_SIZE` | 14px | Bottom-right resize handle |

### Two-Scale System

Each snippet has a combined scale: `s = zoom * snippetScale`. All dimensions (width, height, text, cells) scale by `s`, except the resize handle which only scales by canvas `zoom`.

Scale range: 0.3x - 4.0x per snippet.

### Interactions

| Input | Behavior |
|-------|----------|
| Click | Select (Shift for multi-select); calls `markSnippetInteraction()` to prevent background clear |
| Double-click (<350ms) | Enter edit mode |
| Right-click | Open context menu (tag assignment + delete) at cursor position via `uiStore.setContextMenu()` |
| Drag | Move snippet on canvas |
| Drag resize handle | Scale snippet (0.3x-4x) |

### Graphics Layers (draw order)

1. **Shadow**: Rounded rect at offset (4,4), black 25% opacity
2. **Background**: 0x1c1c38, rounded corners (8px radius)
3. **Header bar**: Type-specific color at 85% opacity
4. **Selection border**: 2.5px white at 90% opacity (if selected)
5. **Tag pills**: Colored rounded-rect pills in the header (right-aligned), one per assigned tag
6. **Grid rows**: Alternating backgrounds (0x242448 at 50%) — via `gridDraw.drawBeatGrid`
7. **Grid lines**: Horizontal + vertical (0x4a4a6a at 50%) — via `gridDraw.drawBeatGrid`
8. **Resize handle**: Triangle with diagonal lines

### Text Layers

| Layer | Font | Color | Content |
|-------|------|-------|---------|
| Header | 11px bold | White | `VERSE`, `CHORUS`, etc. |
| Tag labels | 9px bold | White | Tag names (positioned over header pill backgrounds) |
| Cell (normal) | 13px mono | #d0d0e8 | Syllable text (`gridDraw.placeCellText`) |
| Cell (stressed) | 13px bold mono | White | Stressed syllables |
| Cell (rest) | 13px mono | #555577 | "·" character |
| Count | 10px mono | #7a7a9a | Per-line syllable count |

Cell and count styles come from `gridDraw.createGridTextStyles(s)`.
| Fallback | 13px mono | #a0a0c0 | First 200 chars of raw_text |

---

## InlineGridEditor

**File:** `src/components/canvas/InlineGridEditor.tsx`

Inline HTML overlay that replaces the Pixi snippet card with an editable beat grid when a snippet is double-clicked. Positioned using viewport coordinates at z-index 1000, scaled to match the Pixi card (`s = zoom * snippetScale`).

### Editing Model

Each row of the snippet can be either **active** (being typed into) or **inactive** (displaying syllable cells). The grid cells expand dynamically as the user types, providing real-time syllable count feedback.

- **Active row**: Displays a live syllable grid (cells + count) above a compact text input. Syllabification uses the API result when fresh (raw_text matches current input), falling back to a local vowel-group heuristic during the ~300ms debounce gap.
- **Inactive row**: Displays API-syllabified cells from `gridData`, with hover highlight. Click to activate.

### Local Syllable Estimation

For instant visual feedback while typing, `localSyllabifyLine()` estimates syllable count per word by counting vowel groups (with silent-e adjustment) and splits each word into roughly equal character chunks. This is replaced by the accurate API result once the debounced `POST /api/nlp/syllabify` call returns.

### Keyboard Navigation

| Key | Action |
|-----|--------|
| Enter | Syllabify current row, insert new row below, focus it |
| Tab | Move to next row (create if on last) |
| Shift+Tab | Move to previous row |
| Escape (from input) | Deactivate row (show as cells) |
| Escape (no active row) | Save and close editor |
| ArrowUp/Down | Navigate rows |
| Backspace (empty row) | Delete row, focus previous |

### Save Behavior

- Click outside the editor or Escape (with no active row) triggers save
- Saves `{ raw_text, grid_data, snippet_type }` via `updateSnippet`, then clears `editingSnippetId`
- Click-outside listener is attached with a 100ms delay to avoid catching the opening double-click

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| canvasStore | `snippets`, `updateSnippet`, `snippetScales` |
| uiStore | `setEditingSnippet` |

Click and double-click events call `stopPropagation()` to prevent canvas interactions.

---

## SnippetContextMenu

**File:** `src/components/canvas/SnippetContextMenu.tsx`

Fixed-position HTML overlay (z-index 2000) rendered by CanvasViewport when `uiStore.contextMenu` is non-null. Provides tag assignment and snippet deletion via right-click.

### Features

- **Tag list**: All canvas tags shown as checkable items (checkbox + color dot + name). Clicking toggles `addTagToSnippet` / `removeTagFromSnippet`.
- **Tag editing**: Pencil icon (✎) or double-click a tag name to enter inline edit mode with color picker and name input. Saves on Enter or blur.
- **Tag creation**: "New tag..." row expands to color picker + name input. Saves on Enter or blur, and **auto-assigns** the new tag to the current snippet. Color dots use `onMouseDown={e => e.preventDefault()}` to prevent stealing focus from the input.
- **Delete snippet**: Red "Delete snippet" option at the bottom.
- **Click-outside**: Closes the menu. A cleanup effect flushes any pending tag create/edit when the menu closes.

### Double-Fire Prevention

Uses `createGuard` and `editGuard` refs to prevent duplicate saves when Enter triggers `handleCreateTag`/`handleSaveEdit` and then the input unmount triggers `onBlur` with the same handler. The cleanup effect on `contextMenu` change also checks these guards.

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| canvasStore | `snippets`, `tags`, `addTagToSnippet`, `removeTagFromSnippet`, `createTag`, `updateTag`, `deleteSnippet` |
| uiStore | `contextMenu`, `closeContextMenu` |

---

## InfiniteGrid

**File:** `src/components/canvas/InfiniteGrid.tsx`

Stateless background grid that scrolls and scales with the viewport.

### Props

```typescript
interface Props {
  viewport: { x: number; y: number; zoom: number };
  width: number;
  height: number;
}
```

### Rendering

- Grid spacing: 50px * zoom
- Lines: 1px, color 0x2a2a4a, opacity 0.3
- Offset wraps with modulo to create seamless infinite scrolling

---

## GroupContainer

**File:** `src/components/canvas/GroupContainer.tsx`

Bounding box around grouped snippets with indigo border, semi-transparent fill, and group name label.

### Props

```typescript
interface Props {
  group: Group;
  snippets: Snippet[];
  zoom: number;
  viewportX: number;
  viewportY: number;
}
```

### Bounding Box Calculation

```typescript
minX = min(snippet positions, group position) - 20px padding
minY = min(snippet positions, group position) - 20px padding
maxX = max(snippet right edges) + 20px padding
maxY = max(snippet bottom edges) + 20px padding + 24px (label height)
```

### Interactions

Dragging the group container moves the group and all contained snippets by the same delta. Uses incremental tracking (delta from last pointer position).

### Rendering

- Border: 2px, 0x6366f1 (indigo), 50% opacity, 12px radius
- Fill: 0x6366f1 at 5% opacity
- Label: 11px bold, #6366f1

---

## Color Reference

| Element | Color | Hex |
|---------|-------|-----|
| Canvas background | Dark navy | 0x12122a |
| Snippet body | Dark blue | 0x1c1c38 |
| Grid lines | Dark gray | 0x2a2a4a |
| Selection border | White | 0xffffff |
| Group border/fill | Indigo | 0x6366f1 |
| Rhyme candidate (note) | Sky | #0ea5e9 |
| Rhyme candidate (dictionary) | Teal | #14b8a6 |
| Rhyme candidate (generated) | Zinc grey | #71717a |
| Verse header | Blue | #3b82f6 |
| Chorus header | Amber | #f59e0b |
| Bridge header | Purple | #8b5cf6 |
| Hook header | Red | #ef4444 |
| Freestyle header | Gray | #6b7280 |
