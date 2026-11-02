# Editor Line Split / Join

Fix line breaks from the keyboard in the snippet grid editor, on web and Android with the same rules.
Mostly for voice takes, whose transcribed line breaks are often off by a word or two (contract:
voice-capture §4): Enter splits a row at the caret, Backspace at the start of a row joins it onto the
row above, and (web only) Delete at the end of a row pulls the next row up.

## Scope

- **Enter** in the active row's input splits at the caret: the text before stays (trailing whitespace
  trimmed), the text after becomes a new row directly below (leading whitespace trimmed), which becomes
  active with the caret at its start. A selected range is removed first.
  - Only whitespace after the caret (caret at the end): a blank row is inserted below and the row is
    untouched (the old Enter behaviour).
  - Only whitespace before the caret (caret at 0): a blank row opens above and the row moves down
    unchanged, keeping its grid line and `arranged` flag (the text did not change).
- **Backspace** with a collapsed caret at 0:
  - empty row and more than one row: the row is deleted (old behaviour, also for the first row); the
    caret goes to the end of the row above;
  - non-empty, non-first row: it is joined onto the previous row; one space is inserted between them
    unless either side is blank or there is already whitespace at the seam (multiple spaces are kept
    as they are). The caret lands at the join point (after the separator, i.e. at the start of the
    moved text), so Enter there splits the rows back exactly;
  - first non-empty row: nothing.
- **Delete** (web only) with a collapsed caret at the end of a non-last row joins the next row onto this
  one with the same separator rule; the caret stays at the old end of the row.
- Split and joined rows whose text changed lose `arranged`, get a local syllable estimate at once, and
  are refined by the debounced syllabify exactly like typed text. A blank side of a join contributes
  nothing: the other row is kept as is (grid line and `arranged` included).
- The cell selection is cleared by every split/join (as by insert/delete row).

## Non-scope

- Editor-level undo: neither editor has one (see `grid_rearrange.md`, `docs/android/editor.md`).
  Split/join are structural edits outside the browser's per-input undo stack; native undo inside the
  newly focused input only covers typing done there.
- Delete-at-end on Android (soft keyboards have no forward delete).
- Multi-row splitting of a pasted multi-line text: on Android the first newline splits and further
  newlines become spaces; the web input (`type="text"`) strips newlines itself.
- Any backend change.

## Data model

Both `EditorRowsState`s (`frontend/src/components/canvas/editorState.ts`,
`android/core/logic/.../editor/EditorRowsState.kt`) gain

```
caret: CaretRequest | null        CaretRequest = { row, offset }
```

a request for the view to put the caret of row `row` at `offset` when it focuses / rebuilds that row's
input. Structural reducers issue a **fresh object every time** (Kotlin: a plain class with identity
equality), so views key on identity and two equal requests in a row still fire:

| Reducer | `caret` |
|---|---|
| `initEditorState` | `null` |
| `insertRow(after)` | `{after + 1, 0}` |
| `deleteRow(row)` | `{max(0, row - 1), length of that row}` (`{0, 0}` when the last row was cleared) |
| `splitRow(row, caret)` | `{row + 1, 0}` |
| `joinWithPrevious(row)` | `{row - 1, join point}` |
| `joinWithNext(row)` (web view only; ported) | `{row, old length of row}` |
| `typeLine`, `moveChunkInRow`, `insertRestInRow`, `removeCellsInRow`, `mergeSyllabified` | kept (same object) |
| `setActiveRow` (to another row), `reorderRows` | `null` (caret at the end, the default) |

## Flow

### Web (`InlineGridEditor.tsx`)

```
<input onKeyDown> -> applyRowKey(e, row)
  rowKeyAction({key, text: input.value, selectionStart, selectionEnd, row, rowCount,
                composing: e.nativeEvent.isComposing})                      (editorKeys.ts, pure)
    Enter                         -> {split, text without the selected range, caret: selStart}
    Backspace, text '' && n > 1   -> {deleteEmpty}
    Backspace, caret 0, row > 0   -> {joinPrevious}
    Delete, caret at end, not last-> {joinNext}
    composing / anything else     -> {none}  (the input / old switch handles it: Tab, arrows, Esc)
  not none -> preventDefault, setSelection(null), setRows(prev =>
    split:        splitRow(text differs ? typeLine(prev, row, text) : prev, row, caret)
    joinPrevious: joinWithPrevious(prev, row)
    joinNext:     joinWithNext(prev, row)
    deleteEmpty:  deleteRow(prev, row))
useEffect([activeRow, caret]) -> rAF: focus input of activeRow;
    setSelectionRange(caret?.row === activeRow ? min(caret.offset, len) : len)
```

The effect is keyed on `caret` identity, so it also fires for a join that keeps the active row
(`joinWithNext`) but not on typing (typing keeps the same object).

### Android

Soft keyboards do not reliably report Backspace at the start of a non-empty field: Gboard and most
IMEs send `deleteSurroundingText(1, 0)`, which is a silent no-op with nothing before the caret, and no
key event reaches Compose. So the active row's field always holds a **zero-width space sentinel**
(`LINE_FIELD_SENTINEL`, U+200B) at index 0 with the caret kept after it. Backspace at the row's logical
start deletes the sentinel, which arrives as an ordinary `onValueChange` on every IME and on hardware
keyboards alike (Compose's Backspace deletes the character before the caret). Enter is the IME action
(`ImeAction.Next`, also fired by a hardware Enter in a single-line field); a newline that gets into the
value anyway (paste) splits too.

```
ActiveLineField(text, arranged, caret: CaretRequest?, onTextChange, onSplit, onBackspaceAtStart)
  value = remember(caret) { LineFieldState.of(text, caret?.offset ?: text.length) }  (sentinel + text)
  onValueChange(next) -> interpretLineFieldChange(previous, next)               (LineFieldEdit.kt, pure)
    sentinel gone && previous caret collapsed right after it && rest unchanged -> JoinPrevious
    logical text contains '\n'                -> Split(text without the first '\n', others -> ' ', at '\n')
    otherwise (incl. any other loss of the sentinel: select-all + type, IME range replace)
                                              -> Edit(normalised field: sentinel restored, selection >= 1,
                                                       stray sentinels dropped), textChanged
  JoinPrevious -> actions.backspaceAtStart(row)   (value not applied: the sentinel stays if nothing happens)
  Split        -> actions.splitRow(row, text, caret)
  Edit         -> value = next (keeps the IME composition) or the normalised field; typeLine if changed
  IME Next     -> enterSplit(value) -> actions.splitRow(row, text, caret)
GridEditorViewModel
  splitRow(row, text, caret)  -> typeLine first when text differs; core splitRow; selection = null
  backspaceAtStart(row)       -> '' && n > 1: deleteRow | row > 0: joinWithPrevious | else no change
GridEditorScreen -> RowModel.caret = rows.caret if it targets that row
```

`remember(caret)` rebuilds the field when a structural edit targets the row while its field stays in
the composition (e.g. deleting an empty first row keeps row 0 active with new text), and leaves it alone
while typing. Material's placeholder never shows on a non-empty value, so the field overlays its own
"Type lyrics…" when the logical text is empty.

Trade-offs of the sentinel: the IME sees one invisible character before the text, which can suppress
automatic sentence capitalisation at the start of a row; copying a selection never includes it (the
selection is clamped after it).

## Files

| File | Role / key exports |
|---|---|
| `frontend/src/components/canvas/editorState.ts` | `CaretRequest`, `EditorRowsState.caret`, `splitRow`, `joinWithPrevious`, `joinWithNext` (plus caret handling in the existing reducers) |
| `frontend/src/components/canvas/editorKeys.ts` | `rowKeyAction(RowKeyInput): RowKeyAction` (`split` / `joinPrevious` / `joinNext` / `deleteEmpty` / `none`) |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | `applyRowKey` in `onInputKeyDown`; focus effect keyed on `[activeRow, caret]` |
| `frontend/src/components/canvas/editorState.splitJoin.test.ts`, `editorKeys.test.ts` | Vitest |
| `android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/editor/EditorRowsState.kt` | Same reducers and `CaretRequest` (identity equality) |
| `android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/JsCompat.kt` | `jsTrimStart`, `jsTrimEnd`, `jsIsSpace` (JS whitespace semantics) |
| `android/app/src/main/kotlin/dev/nymph/lyrics/app/editor/LineFieldEdit.kt` | `LINE_FIELD_SENTINEL`, `LineFieldState` (`of`, `text`, `caret`), sealed `LineFieldEdit` (`Edit`, `JoinPrevious`, `Split`), `interpretLineFieldChange`, `enterSplit` |
| `android/app/src/main/kotlin/dev/nymph/lyrics/app/editor/GridEditorActions.kt`, `GridEditorViewModel.kt` | `splitRow(row, text, caret)`, `backspaceAtStart(row)` |
| `android/app/src/main/kotlin/dev/nymph/lyrics/app/editor/ui/ActiveLineField.kt`, `EditorRow.kt`, `GridEditorScreen.kt` | Sentinel field, `RowModel.caret` |
| `android/core/logic/src/test/.../editor/EditorRowsSplitJoinTest.kt`, `android/app/src/test/.../editor/LineFieldEditTest.kt`, `GridEditorSplitJoinTest.kt` | JUnit 5 |

## Invariants

- The `EditorRowsState` invariant holds after every split/join: one grid line per non-blank row, in
  order, `raw_text` = trimmed row text; `arranged` only on non-blank rows.
- Only rows whose text actually changed lose `arranged`; a row moved by a split at 0 or kept by a join
  with a blank row keeps its grid line and flag.
- `splitRow` at the join point of a `joinWithPrevious` restores the original two rows.
- Split/join of an out-of-range row (and join of the first / next of the last row) returns the same
  state object.
- Web and Android produce the same lines, `arranged` sets and caret requests for the same inputs (the
  test files mirror each other).
- The Android field's raw value always starts with exactly one sentinel and its selection never sits
  before it; the sentinel never reaches `EditorRowsState`.
