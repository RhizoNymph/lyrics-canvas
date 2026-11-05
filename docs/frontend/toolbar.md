# Toolbar & Tag Filter

Top-level control bars for canvas editing and the tag expression filter.

## Toolbar

**File:** `src/components/toolbar/Toolbar.tsx`

Top bar with canvas title, zoom controls, undo/redo, and tool toggles.

### Button Groups

| Group | Controls |
|-------|----------|
| View | `List` / `Canvas` toggle (Ctrl+Shift+L): `uiStore.toggleViewMode`, highlighted in list mode; see [list_mode.md](../features/list_mode.md) |
| Zoom | `-` (zoom / 1.2), `%` display, `+` (zoom * 1.2), Reset (hidden in list mode) |
| History | Undo (Ctrl+Z), Redo (Ctrl+Shift+Z) |
| Voice | `● Record` (Alt+Shift+R): `voiceStore.openRecorder()`; appends to the open editor, else a new snippet. Disabled with the tooltip "Recording needs HTTPS" outside a secure context; see [web_voice_capture.md](../features/web_voice_capture.md) |
| Tools | Search (Ctrl+F), Rhymes (toggle), Words (toggle, Ctrl/Cmd+Shift+W or Alt+Shift+W), t-SNE (toggle; hidden in list mode) |
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

### Words Toggle

```
uiStore.toggleWordBank()       also Ctrl/Cmd+Shift+W and Alt+Shift+W (CanvasPage keydown, works while typing)
  open  -> rightPanel = 'words' (closes the rhyme sidebar) -> CanvasPage mounts WordBankPanel
  close -> rightPanel = 'none'
```

`uiStore.rightPanel` is `'none' | 'rhymes' | 'words'`, so the rhyme sidebar and the word bank are never
open together; Rhymes and Words each use the active style while theirs is open. Browsers reserve
Ctrl/Cmd+Shift+W in normal tabs, hence the Alt+Shift+W alternative. See
[web_word_bank.md](../features/web_word_bank.md).

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
| uiStore | `setSearchModalOpen`, `setExportDialogOpen`, `toggleRhymeSidebar`, `toggleWordBank`, `rightPanel` |

### Styling

- Background: #16162a
- Standard button: #2a2a4a
- Active toggle: #6366f1 (indigo)

---

## TagExpressionBar

**File:** `src/components/toolbar/TagExpressionBar.tsx`

The canvas page's tag filter, below the toolbar; it filters both the canvas and list mode. It is a thin
wrapper over the shared `components/tags/TagFilterBar.tsx` (also used by the word bank panel, compact
layout) bound to `tagFilterStore`. Full description in [web_tag_expressions.md](../features/web_tag_expressions.md); language in
[tag_expressions.md](../features/tag_expressions.md).

### Layout

| Part | Behaviour |
|------|-----------|
| Expression field (`TagExpressionInput`) | Live validation (red border, message, marker at the error's code point; click the marker to move the caret there); autocomplete at the caret: tag names, `type:` values, `and`/`or` where an operator fits, `not`; arrows, Enter/Tab, Esc, Ctrl+Space |
| Clear (✕) | `dispatch({type: 'clear'})` |
| Presets ▾ (`PresetPicker`) | Apply a preset, Save as preset (name prompt, 409 inline), rename, overwrite with the current text, delete |
| Count | `n of m shown` while a filter applies |
| Stale line | When the text is invalid: "Not applied. Still filtering by ..." (the last valid text keeps applying) |
| Tag pills | All of the user's tags (user-wide); click inserts the tag at the caret, joined with ` & ` where needed; pills mentioned by the applied filter are filled |

### Store Dependencies

| Store | State/Actions Used |
|-------|-------------------|
| tagFilterStore | `filter` (`text`, `error`, `appliedText`, `applied`), `dispatch` |
| tagStore | `tags`, `loadState`, `load`, presets (via `PresetPicker`) |
| canvasStore | `snippets` (count) |

Tags are created, renamed, recolored and deleted from the snippet context menu's tag picker (and the
word bank's Tags menu), not here. Both pickers are `components/tags/TagChecklist.tsx` over different
targets.
