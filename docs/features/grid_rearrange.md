# Grid Rearrange

Manual correction of the beat grid inside the inline editor: drag a syllable (or a contiguous chunk of
cells) along its line, insert or remove rests, and move whole lines up or down. The syllabifier gets the
first guess; this feature lets the writer fix where things land.

## Scope

- Chunk selection in a row: click a cell, shift-click to extend to a contiguous range, Escape clears.
- Chunk drag within one line, as a **free reorder**: rests are ordinary cells, so dragging a syllable over
  rests shifts its timing and dragging it past other syllables reorders words. The line's text is rebuilt
  from the new cell order. Left/Right arrows nudge the selection one cell; Right with no cell after the
  selection inserts a rest before it instead (the selection moves one cell right, as with `+·`), and Left
  at index 0 does nothing.
- Rest marks in text: each `_` in a row is a rest cell, and rests made in the grid (`+·`, the nudge,
  drags) are written back into the row text as `_`, so the text always matches the grid (rules and vectors:
  [rest_marks.md](./rest_marks.md), web side: [web_rests_and_search.md](./web_rests_and_search.md)).
- Rest insert before the selection (`+·` button, Insert or `+`) and removal of selected rests (`−`,
  Delete / Backspace / `-`).
- Line reorder with the per-row `▲` / `▼` buttons or Alt+Up / Alt+Down (from the active row's input or
  with a selection).
- Rows laid out by hand are **arranged**: the debounced API syllabify never overwrites them. Typing into
  an arranged row discards its arrangement (expected; the text changed).
- Line breaks are fixed from the keyboard (Enter splits at the caret, Backspace at the start joins onto
  the row above, Delete at the end pulls the next row up): see
  [Editor Line Split / Join](./editor_line_split_join.md). Split and joined rows lose `arranged`.
- Save sends `raw_text` rebuilt from the grid for arranged rows (typed text for the rest) together with
  `grid_data`; the backend keeps the layout and recomputes phonetics from them (grid stress wins).
- Rests live in the text: the rebuilt text writes each rest as `_`, and a typed `_` is a rest cell after
  syllabify (backend and local estimate), so the text always matches the grid. Legacy rows saved with
  grid rests but no `_` are matched on display text in the arranged check and get the canonical text on
  their next save. See [rest_marks.md](./rest_marks.md).

## Non-scope

- Cross-line chunk moves (a chunk never leaves its row).
- Editing a syllable's text or stress in place.
- Any backend change: `PUT /api/snippets/{id}` already accepts `raw_text` + `grid_data` together.
- The standalone `BeatGridEditor` (unchanged).

## Data model

`GridCell` gains an optional `word?: string`, the surface word (as typed, punctuation included) the
syllable belongs to. It is populated whenever a grid line's `raw_text` and `word_index`es agree (a fresh
API result, a local estimate, a saved grid on open) via `attachWords`, and it is what lets `raw_text` be
rebuilt faithfully after the API's approximate letter split (`hul lab alo o` -> `hullabaloo`). When a
syllable is removed from a word, the word's other cells drop `word` and fall back to their concatenated
syllables.

## Flow

```
click cell                 -> selection {row, start, end}        (InlineGridEditor state)
shift-click                -> selection extended to cover both
pointer-down on selected   -> useChunkDrag: capture pointer, wait for 4 px of travel
pointer-move               -> gapFromX(x - strip.left, cellWidth, cellCount) -> drop indicator
pointer-up                 -> moveTargetFromGap(gap, start, end) -> `to` or null (no move)
                           -> moveChunkInRow(state, row, start, end, to)
                                moveChunk: splice cells, rebaseWordIndexes, countSyllables
                                withRebuiltText: raw_text = rebuildRawText(line)
                                row.text = raw_text; row.arranged = true
                           -> selection follows the chunk to its landing index
+· / Insert                -> insertRestInRow(state, row, selection.start); selection shifts right by 1
← / →                      -> nudgeSelection(state, selection, ±1): moveChunkInRow one cell, or at the
                              line end (→, end == cells.length) insertRestInRow before the selection;
                              ← at index 0 is a no-op
− / Delete                 -> removeCellsInRow(state, row, start, end) (only when all selected are rests)
▲▼ / Alt+arrows            -> reorderRows(state, from, to): rows carry text, grid line, arranged flag;
                              activeRow and selection follow their line
typing                     -> typeLine: local estimate (each `_` a bare rest cell), arranged cleared for that row
Enter / Backspace at 0 /   -> rowKeyAction (editorKeys.ts) -> splitRow / joinWithPrevious / joinWithNext /
Delete at end                 deleteRow; caret placed from state.caret (editor_line_split_join.md)
300 ms after text change   -> POST /api/nlp/syllabify (skipped when every non-blank row is arranged)
                           -> mergeSyllabified: unarranged rows take the first unconsumed result line
                              with the same text; arranged rows and rows whose text changed keep theirs
save (click-outside / Esc) -> updateSnippet({ raw_text: rawTextForSave(state), grid_data, snippet_type })
```

## Files

| File | Role |
|------|------|
| `frontend/src/types/index.ts` | `GridCell.word` |
| `frontend/src/lib/grid/wordIndex.ts` | `rebaseWordIndexes`, `countSyllables`, `attachWords`, `attachWordsAll` |
| `frontend/src/lib/grid/moveChunk.ts` | `moveChunk(line, start, end, to)` |
| `frontend/src/lib/grid/rawText.ts` | `rebuildRawText` (canonical text: rests as `_`, each word at its first syllable), `withRebuiltText`, `rebuildRawTextAll` |
| `frontend/src/lib/grid/restMarks.ts` | `REST_MARK`, `tokenizeLine`, `lineWords`, `displayLine`, `displayText`, `hasWords` |
| `frontend/src/lib/grid/reorderLines.ts` | `reorderLines(grid, from, to)` |
| `frontend/src/lib/grid/rests.ts` | `insertRest(line, at)`, `removeCell(line, at)` |
| `frontend/src/lib/grid/dropIndex.ts` | `gapFromX`, `moveTargetFromGap` (drag geometry) |
| `frontend/src/lib/grid/localSyllabify.ts` | `localSyllabifyLine` and the vowel-group estimate (moved out of the editor) |
| `frontend/src/lib/grid/index.ts` | Barrel |
| `frontend/src/components/canvas/editorState.ts` | `EditorRowsState` (incl. `caret: CaretRequest`) and the pure reducers: `initEditorState`, `gridLineForRow`, `setActiveRow`, `typeLine`, `insertRow`, `deleteRow`, `splitRow`, `joinWithPrevious`, `joinWithNext`, `moveChunkInRow`, `insertRestInRow`, `nudgeSelection` (+ `CellSelection`), `removeCellsInRow`, `reorderRows`, `mergeSyllabified`, `needsSyllabify`, `rawTextForSave` |
| `frontend/src/components/canvas/editorKeys.ts` | `rowKeyAction`: what Enter / Backspace / Delete in a row input mean (split, join, delete empty) |
| `frontend/src/components/canvas/useChunkDrag.ts` | Pointer logic for a chunk drag: capture, 4 px threshold, drop gap, `consumeDrag` to swallow the trailing click |
| `frontend/src/components/canvas/GridRow.tsx` | One row's cells strip: selection highlight, drag handlers, drop indicator, `+· − ▲ ▼` buttons; exports `ROW_CONTROLS_WIDTH` |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | Orchestration: row state, selection state, debounced syllabify, keyboard, save |

Every `lib/grid` module and `editorState.ts` has a sibling `*.test.ts` (vitest).

## Invariants

- `gridData.lines` has exactly one line per non-blank row of `lines`, in order, and each grid line's
  `raw_text` (trimmed) equals its row's text (trimmed). `gridData` is `null` when no row is non-blank.
  Every reducer maintains this, so a row never maps to another row's cells.
- `word_index` values in a line are re-based by order of first appearance after every edit: the first
  appearances are strictly increasing from 0 and all syllables of one word share one index, even when
  another word's syllables sit between them. The backend's `reconcile` groups by index, so interleaving is
  safe.
- `raw_text` is always derivable from the grid: `rebuildRawText` walks the cells, writing `_` per rest and
  each word at its first syllable (its `word` when present, else its concatenated syllables), and the
  rebuilt text's word tokens (`lineWords`, rest marks excluded; [rest_marks.md](./rest_marks.md)) match the re-based `word_index`es.
- Rows matched to a saved grid line on open compare trimmed text or, failing that, display text (rest marks
  and spacing ignored; a rest-only row needs the exact text), so rows saved before rest marks open arranged
  with their rests; the kept line takes the row's text as `raw_text`, and the next save writes the canonical
  text with `_`.
- Arranged rows survive the debounce: `mergeSyllabified` never touches a row in `arranged`; only
  `typeLine` (and a split or join that changes the row's text) clears the flag. Rows loaded from a saved grid whose text still matches start arranged, so a
  saved layout survives reopening the editor.
- A chunk move is a no-op (same state object) when it would not move anything; `moveTargetFromGap` returns
  null for drops inside or at either edge of the chunk.
