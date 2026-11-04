# Web Tag Expressions

The web client side of user-wide tags, tag filter expressions and presets. The language, the HTTP API and
the data model are specified in [tag_expressions.md](./tag_expressions.md) (canonical, written with the
backend) and checked by the shared vectors in `spec/tag_expression_vectors.json`.

## Scope

- A typed TypeScript implementation of the expression language (`frontend/src/lib/tagExpr/`): lexer,
  parser, AST, normalized format, evaluation, rename, caret context, autocomplete and tag insertion. It
  passes every vector from a test that reads the repo-root file.
- User-wide tags loaded from `GET /api/tags` (`tagStore`), created with `POST /api/tags`, renamed,
  recolored and deleted with `PUT/DELETE /api/tags/{id}`. Canvas detail no longer carries tags.
- The tag picker in the snippet context menu: search, immediate link/unlink, "Create '<query>'",
  per-tag edit (name, color) and delete, multi-selection with a mixed state.
- The expression filter bar on the canvas page (canvas and list view), replacing the old tag-pill filter:
  live validation with a marker at the error's code point, autocomplete at the caret, tag pills that
  insert at the caret, presets, clear. The filter text is persisted per canvas per browser.
- Presets (`/api/tag-presets`): pick to apply, save as preset, rename, overwrite, delete.
- The rhyme sidebar's "Only my lines matching" control, sent as `tag_filter` on rhyme-suggest.
- Typed errors for every new server code.

## Non-scope

- The backend (migration, endpoints, preset rewrite on rename, rhyme filtering) and Android.
- Undo of tag links. Tag changes are server state, so the local undo history is patched to match the
  server instead of recording them.
- Following a tag rename into filter texts persisted for canvases other than the open one. Those keep the
  old name, which then matches nothing, until edited. Presets are rewritten by the server.
- Tag management outside the picker (there is no separate tag manager page).

## Expression language (client implementation)

All offsets are Unicode code points. DOM inputs report UTF-16 offsets, so `lib/tagExpr/text.ts` has
branded `CpOffset` and `Utf16Offset` types and the converters `cpToUtf16` and `utf16ToCp`. Components
convert at the DOM boundary. The pure modules never use UTF-16 indices.

```
text --lex()--> Token[] (or TagExprError; lexer errors win)
     --parse()--> { ok: true, expr: TagExpr | null } | { ok: false, error: TagExprError }
TagExpr = or(children>=2) | and(children>=2) | not(child) | tag(name) | type(value: SnippetType)
format(expr)        normalized text; format(parse(format(x))) == format(x)
evaluate(expr, {type, tagNames})   tag names compare under toLowerCase; null matches everything
renameTag(expr, from, to) / renameInText(text, from, to)
```

Whitespace is exactly Python's `str.isspace` set (a fixed code point list in `chars.ts`), not JS `\s`,
so `﻿` is a word character on every implementation. Keywords `and` `or` `not` are matched with
`toLowerCase`. The parser throws a module-private `ParseFailure` that only `parse` catches.

### Caret context and completion

`caretToken(chars, caret)` finds the token under the caret: a bare word (replaced whole) or a quoted
name (found by scanning from the start with the lexer's escape rules; the prefix is unescaped and the
replacement runs through the closing quote). `slotAt(chars, offset)` decides from the last significant
character before the token whether only an operand fits (`operand`: start, `(`, `&`, `|`, `!`,
`and`/`or`/`not`) or an operator fits too (`operand_or_operator`: after a tag, type, `)` or quoted name).
It looks only at the neighbourhood, so completion works in invalid text.

`suggestionsFor(ctx, tags, limit = 10)`:

- inside quotes: tag names only;
- prefix starting with `type:`: the matching type terms only;
- otherwise: `and`/`or` first when an operator fits (and match the prefix), then tags (prefix matches
  before substring matches), type terms (all of them for an empty prefix or a prefix of `type:`, else
  those whose value starts with the prefix), then `not` for a non-empty prefix of it.

Tag suggestions insert `formatTagName(name)`, so names that are keywords, start with `type:` or contain
spaces or operator characters are quoted. `applySuggestion` replaces the token, adds a space before it
unless it follows whitespace, `(` or `!`, and leaves the caret after a following space.

`insertTagAtCaret(text, caret, name)` (tag pill click) moves the caret out of a word or quoted name it is
inside, then joins the term with ` & ` after a complete operand and before a following operand; no space
is added before `)`.

## Data and control flow

### Tags and presets

```
CanvasPage mount
  -> tagStore.load(): GET /api/tags + GET /api/tag-presets (parallel), sorted by lower(name)
  -> tagFilterStore.open(canvasId): restore { text, appliedText } from localStorage
  -> canvasStore.loadCanvas(id): snippets embed tags as SnippetTag {id, name, color}

TagPicker (context menu, keyed by snippet id)
  targets = pickerTargets(contextId, selection, snippets)     whole selection if it contains contextId
  row state = checkState(tagId, targets)                      all | some | none
  click / Enter -> tagStore.applyToggle(planToggle(tagId, targets))
       all -> unlink from every target; some/none -> link where missing
       POST /api/snippets/{id}/tags?tag_id= | DELETE /api/snippets/{id}/tags/{tagId}, in parallel
       successes -> canvasStore.updateEmbeddedTags(setEmbeddedTag(...)), snippet_count +/- n
       first failure -> TagApiError shown inline (the successes stay)
  "Create '<q>'" (createOffer: valid name, no case-insensitive match)
       -> tagStore.createTag(name, color) POST /api/tags -> applyToggle(link, all targets)
  edit -> tagStore.updateTag(id, {name?, color?}) PUT /api/tags/{id}
       -> patch embedded copies (live map and every undo/redo snapshot)
       -> on rename: tagFilterStore renameTag, rhymeStore.renameTagInFilter,
          refreshPresets() (the server rewrote them in the same transaction)
       tag_not_found -> drop the tag locally
  delete (two clicks) -> DELETE /api/tags/{id} -> drop it from the list and every snippet
       tag_not_found counts as done
```

### Filter bar

```
TagExpressionInput onChange(text)
  -> tagFilterStore.dispatch({type: 'edit', text})
       filterReducer: parse(text)
         ok    -> { text, error: null, appliedText: text, applied: expr }
         error -> { ...state, text, error }          (applied keeps the last valid filter)
       saveFilter(localStorage, canvasId, { text, appliedText })  ('' and '' removes the key)
CanvasViewport / ListView
  visibleSnippets(snippets, filter.applied)
    -> evaluate(applied, { type: snippet_type, tagNames: tags.map(name) })
tag pill click (mousedown prevented, caret kept) -> input.insertTag(name) -> edit
preset click -> dispatch({type: 'applyPreset', expression})
clear -> dispatch({type: 'clear'})
```

The bar shows `n of m shown` while a filter applies. While the text is invalid the field has a red
border, the message and a marker line (`before`, the character at the error, `after`; clicking it moves
the caret there), and "Not applied. Still filtering by ..." below.

### Presets

```
PresetPicker(currentText, onApply)       shared by the filter bar and the rhyme sidebar
  row click -> onApply(expression)
  Save as preset (name prompt) -> tagStore.createPreset(name, text)
       checkName + checkPresetExpression locally (invalid_preset_name / empty_preset /
       invalid_tag_expression without a request) -> POST /api/tag-presets {name, expression: normalized}
       409 preset_name_taken shown inline
  rename -> updatePreset(id, {name});  overwrite -> updatePreset(id, {expression: currentText})
  delete (two clicks) -> deletePreset(id); preset_not_found counts as done
```

### Rhyme sidebar

```
NotesTagFilter: TagExpressionInput bound to rhymeStore.tagFilterText (+ PresetPicker,
                "Use canvas filter" = tagFilterStore.filter.appliedText, Clear)
useRhymeQuery: tagFilterText change -> debounced runQuery
rhymeStore.buildRequest:
  parse(tagFilterText) fails -> RhymeError('invalid_tag_filter', expressionError), no request,
                                candidates cleared, CandidateList shows "Not searched: ..."
  parses to an expression    -> request.tag_filter = format(expr)
  empty                      -> tag_filter omitted
422 invalid_tag_expression from the server -> RhymeError('invalid_tag_filter') with its expression_error
```

The dictionary source is never filtered (server side). The control notes when the notes source is off.

## Wire shapes the client assumes

- `GET /api/tags` -> `Tag[] = {id, name, color, snippet_count, created_at}[]`, sorted by lower(name).
- Snippet responses embed `tags: SnippetTag[] = {id, name, color}[]` (extra fields are ignored).
- `POST /api/tags {name, color?}` -> `201 Tag`; `PUT /api/tags/{id} {name?, color?}` -> `200 Tag`.
- `GET /api/tag-presets` -> `TagPreset[] = {id, name, expression, created_at, updated_at}[]`.
- Errors: `{detail: {error, message}}`, and for expression errors
  `detail.expression_error = {code, position, message}` with `position` in code points.
- `RhymeSuggestRequest.tag_filter?: string` (normalized; omitted when empty).

## Files

| File | Role | Key exports |
|------|------|-------------|
| `spec/tag_expression_vectors.json` | Shared conformance vectors (byte-identical across branches) | |
| `frontend/src/lib/tagExpr/ast.ts` | AST union, snippet types | `TagExpr`, `TagExprOrEmpty`, `SNIPPET_TYPES`, `isSnippetType` |
| `frontend/src/lib/tagExpr/text.ts` | Code point offsets | `CpOffset`, `Utf16Offset`, `cp`, `u16`, `codePoints`, `cpLength`, `cpToUtf16`, `utf16ToCp` |
| `frontend/src/lib/tagExpr/chars.ts` | Character classes, keywords | `isSpace`, `isWordChar`, `isOperatorChar`, `asKeyword`, `TYPE_PREFIX` |
| `frontend/src/lib/tagExpr/errors.ts` | Error codes, limits, messages | `TagExprError`, `TagExprErrorCode`, `TAG_EXPR_ERROR_CODES`, `tagExprError`, `MAX_EXPRESSION_LENGTH`, `MAX_PAREN_DEPTH` |
| `frontend/src/lib/tagExpr/lexer.ts` | Tokens with code point spans | `lex`, `Token`, `LexResult` |
| `frontend/src/lib/tagExpr/parser.ts` | Recursive descent with flattening | `parse`, `ParseResult` |
| `frontend/src/lib/tagExpr/format.ts` | Normalized text, quoting | `format`, `formatTagName`, `isBareTagName` |
| `frontend/src/lib/tagExpr/evaluate.ts` | Match a snippet | `evaluate`, `EvalSubject` |
| `frontend/src/lib/tagExpr/rename.ts` | Rename in AST or text | `renameTag`, `renameInText`, `mentionsTag` |
| `frontend/src/lib/tagExpr/caretContext.ts` | Token under the caret, grammar slot | `caretToken`, `slotAt`, `startsOperandAt`, `GrammarSlot` |
| `frontend/src/lib/tagExpr/completion.ts` | Suggestions and applying them | `completionContextAt`, `suggestionsFor`, `applySuggestion`, `Suggestion`, `TextEdit` |
| `frontend/src/lib/tagExpr/insert.ts` | Tag pill insertion | `insertTagAtCaret` |
| `frontend/src/lib/tagExpr/vectors.test.ts` | Reads `spec/tag_expression_vectors.json` via `node:fs` | |
| `frontend/src/testSupport/node-shims.d.ts` | Typings for `node:fs` in tests (no `@types/node`) | |
| `frontend/src/lib/tags/errors.ts` | Typed tag/preset API errors | `TagApiError`, `TAG_SERVER_ERROR_CODES`, `TagServerErrorCode`, `TagApiErrorKind`, `toTagApiError`, `describeTagError`, `TagResult` |
| `frontend/src/lib/tags/serverDetail.ts` | Parse `{detail: {error, message, expression_error}}` (also used by rhyme errors) | `parseServerDetail` |
| `frontend/src/lib/tags/names.ts` | Name and color rules, ordering | `checkName`, `isHexColor`, `byName`, `sameName`, `TAG_COLOR_CHOICES`, `DEFAULT_TAG_COLOR` |
| `frontend/src/lib/tags/picker.ts` | Picker targets, check state, toggle plan, search, create offer | `pickerTargets`, `checkState`, `planToggle`, `searchTags`, `createOffer` |
| `frontend/src/lib/tags/snippetTags.ts` | Immutable updates of embedded tags | `embedTag`, `patchEmbeddedTag`, `removeEmbeddedTag`, `setEmbeddedTag` |
| `frontend/src/lib/tags/presets.ts` | Preset expression check | `checkPresetExpression` |
| `frontend/src/lib/tagFilter/state.ts` | Filter bar reducer | `filterReducer`, `FilterState`, `FilterAction`, `restoreFilter`, `toPersisted`, `EMPTY_FILTER` |
| `frontend/src/lib/tagFilter/persistence.ts` | Guarded per-canvas localStorage | `loadFilter`, `saveFilter`, `filterStorageKey`, `browserFilterStorage` |
| `frontend/src/lib/tagFilter/validation.ts` | Error marker split at a code point | `markerFor`, `errorMarker`, `ErrorMarker` |
| `frontend/src/lib/tagFilter/menuNav.ts` | Suggestion list keyboard navigation | `moveHighlight`, `menuKeyEffect` |
| `frontend/src/lib/canvas/visibility.ts` | Evaluates the applied filter per snippet | `isSnippetVisible`, `visibleSnippets` |
| `frontend/src/stores/tagStore.ts` | User tags and presets, all tag/preset API calls | `useTagStore` |
| `frontend/src/stores/tagFilterStore.ts` | Canvas filter state + persistence | `useTagFilterStore` |
| `frontend/src/stores/canvasStore.ts` | `updateEmbeddedTags(update)` patches snippets and undo/redo snapshots | |
| `frontend/src/stores/rhymeStore.ts` | `tagFilterText`, `setTagFilterText`, `renameTagInFilter`; blocks invalid filters | |
| `frontend/src/api/client.ts` | `tagApi` (list/create/update/delete/link/unlink), `tagPresetApi` | `TagUpdateBody`, `TagPresetUpdateBody` |
| `frontend/src/types/index.ts` | `Tag`, `SnippetTag`, `TagPreset`; `Snippet.tags: SnippetTag[]`; no `CanvasDetail.tags` | |
| `frontend/src/types/rhyme.ts` | `RhymeSuggestRequest.tag_filter`, `RhymeErrorKind 'invalid_tag_filter'`, `RhymeError.expressionError` | |
| `frontend/src/components/tags/TagExpressionInput.tsx` | Expression field: validation, autocomplete, `insertTag` handle | default, `TagExpressionInputHandle` |
| `frontend/src/components/tags/PresetPicker.tsx` | Preset popover | default |
| `frontend/src/components/tags/TagPicker.tsx` | Context menu tag checklist | default |
| `frontend/src/components/tags/tagStyles.ts` | Shared styles | |
| `frontend/src/components/toolbar/TagExpressionBar.tsx` | Canvas page filter bar | default |
| `frontend/src/components/rhyme/NotesTagFilter.tsx` | Rhyme "Only my lines matching" | default |
| `frontend/src/components/canvas/SnippetContextMenu.tsx` | Hosts `TagPicker`; merge, delete; Esc closes | default |

## Invariants and constraints

- Every position the client shows or sends is a code point offset. UTF-16 offsets exist only between
  `TagExpressionInput` and the DOM.
- `filter.applied` only ever holds a parse of `filter.appliedText`, which is the last text that parsed.
  An invalid edit never changes visibility.
- The rhyme store never calls rhyme-suggest with a `tag_filter` that does not parse locally, and sends
  the normalized form.
- Presets are created and overwritten with normalized, non-empty expressions only.
- `tagStore.tags` and `presets` stay sorted by lower(name), as the server sorts them.
- Embedded tags in snippets (live and in undo/redo snapshots) follow every successful link, unlink,
  rename, recolor and delete. A partially failed multi-snippet toggle keeps the links that succeeded.
- Store actions return `TagResult` and never throw. Server codes map to `TagApiError.kind` exactly. Other
  failures map to `not_found`, `validation`, `network`, `server` or `http`.
- Storage failures are logged and never break filtering (the in-memory filter keeps working).
