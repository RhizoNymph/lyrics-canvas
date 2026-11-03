# Android: Tag Expressions (tags, filter bar, presets, rhyme tag filter)

The Android client of [tag_expressions.md](./tag_expressions.md) (the canonical spec, written with the
backend: expression language, data model, HTTP API). Tags are user-wide (one list per user, shared by
every canvas). On Android this feature adds tag assignment, a canvas filter bar driven by a text
expression, server-side presets, and the rhyme sheet's "Only my lines matching" restriction.

## Scope

- **Expression engine** in `:core:logic` (`dev.nymph.lyrics.logic.tags`): lexer, parser, sealed AST,
  normalizing formatter, evaluation, rename, completion at the caret and tag insertion at the caret.
  Positions are Unicode code points. Passes every vector of `spec/tag_expression_vectors.json`, read
  in place by the module's test task.
- **Model / API** (`:core:model`, `:core:data`): `Tag` (embedded `{id, name, color}`), `UserTag`
  (`GET /api/tags`: `{id, name, color, snippet_count, created_at}`), tag CRUD, link
  (`POST /api/snippets/{id}/tags?tag_id=`) and unlink, preset CRUD (`/api/tag-presets`),
  `RhymeSuggestRequest.tagFilter` (`tag_filter`), `CanvasDetail` without `tags`, the nine tag error
  codes (`TagErrorCode`) and `detail.expression_error` (`ExpressionErrorBody`).
- **Tag picker** (bottom sheet) from the selection bar (canvas and list mode) and the editor's top bar:
  searchable checklist with coloured pills; tapping a row links / unlinks at once on every picked
  snippet (mixed state shown with a tri-state checkbox); "Create '<query>'" when no tag has that name
  (case-insensitively), which creates the tag and links it to the picked snippets; per tag, an edit
  dialog (rename, colour from a fixed palette, delete with confirm).
- **Filter bar** under the canvas top bar (canvas and list mode): a single-line expression field with
  live validation (the offending character underlined, message below, and what still applies),
  suggestion chips for the word at the caret (tag names, `type:` values, `and` / `or` / `not` where an
  operator fits), tag pills that insert at the caret (joined with ` & `), a clear button, and a
  "Presets ▾" menu: one tap applies a preset; "Save filter as preset…" (name prompt, a taken name shows
  inline); "Manage presets…" (rename, overwrite with the current filter, delete). An invalid text does
  not change the applied filter. Snippets the applied filter does not match are not drawn, not
  hit-testable and not listed; hidden snippets leave the selection.
- **Remembered filter**: the applied (last valid) filter text per canvas on this device, in
  Preferences DataStore (`tag_filters`, key `filter:<canvasId>`).
- **Rhyme sheet**: "Only my lines matching", the same expression field plus "Use canvas filter" and the
  preset menu. A valid non-empty expression is sent normalized as `tag_filter`; a known-invalid one
  blocks the query locally (results cleared, "Fix the tag filter to search"); with the notes source off
  it is neither sent nor blocking (it only restricts notes).
- Typed errors for every new server code with user-facing strings.

## Non-scope

- The expression language, migration and HTTP routes themselves (backend; see
  [tag_expressions.md](./tag_expressions.md)). The web client (separate feature doc).
- Free colour input: colours come from `TAG_COLORS` (all `#rrggbb`), so `invalid_color` is only
  mapped, never expected.
- Offline caching of tags or presets; a failed load shows a retry line under the filter bar.
- Rewriting the rhyme sheet's own expression on a tag rename (only the canvas filter bar is rewritten;
  the server rewrites presets).
- Keeping a just-created snippet visible when the filter does not match it: a FAB, voice-take or merged
  snippet that the filter excludes is hidden like any other (the editor still opens it).

## Data and control flow

### Expression engine (`:core:logic`)

```
text -> lex (code points; lexer errors win; >500 cp -> too_long@500)
     -> tokens: ( ) & | ! Name(name, quoted) TypeTerm(SnippetType)        (and/or/not are & | !)
     -> Parser (recursive descent, ! > & > |, juxtaposition = &, paren depth <= 32)
     -> TagExprParse.Valid(TagFilter.Everything | TagFilter.Where(TagExpr)) | Invalid(TagExprError)
TagExpr = Or(children>=2) | And(children>=2) | Not | Tag(name) | Type(SnippetType)   (flattened)
TagFilter.format()            normalized text; format(parse(format(x))) == format(x)
TagFilter.matches(type, tags) lowercase(Locale.ROOT) name match, exact type match, unknown names match nothing
TagFilter.renameTag(from, to) case-insensitive replace of Tag terms
completeAt(text, caret, tagNames) -> Completion(start, end, suggestions); Completion.apply -> TextEdit
insertTagAt(text, caret, name)    -> TextEdit (moves past a word/quote, joins with " & ")
```

Whitespace follows Python's `str.isspace` (`isExprSpace`: `Character.isWhitespace`, `isSpaceChar`,
U+0085), so no-break spaces separate tokens exactly as on the server.

### Field and filter-bar reducers

```
ExpressionField(text, caret)          parse computed on construction; edit / accept / insertTag / replaced / cleared
FilterBarState(input, applied, appliedText)
  edit/accept/insertTag/applyPreset/clear -> with(next): valid -> applied = filter, appliedText = text
                                                         invalid -> only input changes
  renameTag(from, to) -> valid text mentioning `from` rewritten (normalized), else null
  restored(saved)     -> applyPreset(saved)  (an invalid saved text applies nothing)
rhymeTagFilter(field, notesEnabled) -> None | Send(normalized) | Blocked(error)
```

### Canvas filter bar

```
TagsViewModel.init: tags.list() || presets.list(); filterStore.load(canvasId)
  -> (unless the user already typed) FilterBarState.restored(saved) -> port.setTagFilter(applied)
FilterBar field / chip / pill / preset / clear -> TagsViewModel.editFilter | acceptFilterSuggestion |
                                                   insertFilterTag | applyPreset | clearFilter
  -> setFilterBar(next): applied changed     -> port.setTagFilter(applied)   (CanvasViewModel)
                         appliedText changed -> toPersist (conflated channel) -> filterStore.save
CanvasViewModel.setTagFilter(filter): kept for before-load; Ready.filter = filter;
  selection pruned to visible ids
Ready.visibleSnippets = snippets.filter(filter.matches) -> SnippetCanvas draw, hitTest, listRows
```

`ExpressionInput` keeps a local `TextFieldValue` and reports `(text, caret)` in code points
(`codePointOffsetOf`); it follows the `ExpressionField` only when that changes (a preset, pill or
completion), so typing never races the state. The error marker is a `VisualTransformation` that
underlines the code point at `error.position` (`utf16IndexOf`).

### Tag picker

```
Selection bar "Tags" / editor "Tags" -> TagsViewModel.openPicker(ids)
  -> PickerSession(ids, links = tagLinks(port.currentSnippets(), ids))
rows = pickerRows(tags, links, query)          sorted by lowercase name, contains(query), NONE/SOME/ALL
row tap -> planToggle(tag, links): Link(missing) | Unlink(all, when every picked snippet has it)
  -> link / unlink each snippet in parallel (row pending meanwhile)
  -> each success: port.onTagLinked / onTagUnlinked (CanvasViewModel updates the embedded tags)
  -> links re-read from the port; first failure shown in the sheet
"Create '<q>'" (createOffer: valid trimmed name, no case-insensitive match)
  -> POST /api/tags {name} -> tags (sorted) -> planToggle(new tag) -> link to every picked snippet
Edit dialog -> saveTagEditor: validateTagName; PUT only changed fields
  -> tags updated, port.onTagEdited, links refreshed;
     on rename: filterBar.renameTag(old, new) applied, presets reloaded (the server rewrote them)
  409 tag_name_taken -> inline error
Delete (confirm) -> DELETE /api/tags/{id} (404 tag_not_found counts as gone)
  -> removed from tags, port.onTagDeleted, links refreshed
```

The editor destination shares the canvas back-stack entry's `TagsViewModel` (as it shares the
`CanvasViewModel`), so a link made from the editor reaches the canvas at once; the editor's own save
(`PUT /api/snippets/{id}`) returns the server's tags too.

### Presets

```
Save as: PresetEditor(SaveAs) -> name validated; current filter must be valid and non-empty
  (else inline "invalid" / "needs a filter", no request) -> POST {name, expression: normalized}
Rename:  PresetEditor(Rename(id)) -> PUT {name}
Overwrite (confirm): PUT {expression: normalized current filter} (invalid/empty -> snackbar, no request)
Delete (confirm): DELETE; preset_not_found also drops the preset locally
409 preset_name_taken / 422 invalid_tag_expression -> inline in the name prompt
```

### Rhyme sheet

```
RhymeUiState.tagFilter: ExpressionField (kept when the sheet closes)
editTagFilter / acceptTagFilterSuggestion / insertTagFilterTag / setTagFilterText (preset, canvas filter) / clearTagFilter
RhymeControls includes tagFilter -> planRhymeQuery(..., tagFilter)
  rhymeTagFilter(field, sources.notes): Blocked -> RhymeQueryPlan.Blocked (no request, results cleared)
                                         Send(e) -> request.tagFilter = e
                                         None    -> no tag_filter
```

A caret-only change re-plans to the same request, which the plan identity check drops.

## Files

| File | Role / key exports |
|---|---|
| `spec/tag_expression_vectors.json` | Shared conformance vectors (byte-identical across branches; never copied) |
| `android/core/logic/build.gradle.kts` | Test task: `systemProperty("tagExprVectors", rootProject.file("../spec/tag_expression_vectors.json"))` and the file as a task input |
| `android/core/logic/.../logic/tags/CodePoints.kt` | `codePointLength`, `codePointOffsetOf`, `utf16IndexOf`; internal `isExprSpace`, `isWordChar` |
| `.../logic/tags/TagExprError.kt` | `TagExprErrorCode` (wire codes, `parse`), `TagExprError(code, position, message)`, `defaultMessage`, `MAX_EXPRESSION_LENGTH`, `MAX_PAREN_DEPTH` |
| `.../logic/tags/TagExpr.kt` | `TagExpr` (`Or`, `And`, `Not`, `Tag`, `Type`; `or`/`and` flattening factories), `TagFilter` (`Everything`, `Where`), `TagExprParse` (`Valid`, `Invalid`), `SnippetType.wire` |
| `.../logic/tags/TagExprLexer.kt` | internal `Token`, `lex`, `lexLenient` |
| `.../logic/tags/TagExprParser.kt` | `parseTagExpression` |
| `.../logic/tags/TagExprFormat.kt` | `isBareTagName`, `formatTagName`, `TagFilter.format`, `TagExpr.format` |
| `.../logic/tags/TagExprEval.kt` | `TagFilter.matches(type, names)`, `TagFilter.matches(snippet)`, `renameTag`, `mentionsTag` |
| `.../logic/tags/TagCompletion.kt` | `ExprKeyword`, `Suggestion` (`TagName`, `TypeValue`, `Keyword`), `TextEdit`, `Completion` (`apply`), `completeAt`, `insertTagAt`, `COMPLETION_LIMIT` |
| `.../logic/tags/ExpressionField.kt` | `ExpressionField`, `FilterBarState` (`restored`, `renameTag`), `RhymeTagFilter`, `rhymeTagFilter` |
| `.../logic/tags/TagPicker.kt` | `TagCheck`, `PickerRow`, `pickerRows`, `TagToggle` (`Link`, `Unlink`), `planToggle`, `createOffer` |
| `.../logic/tags/TagNames.kt` | `validateTagName` (`TagNameCheck`, `TagNameProblem`), `MAX_TAG_NAME_LENGTH`, `DEFAULT_TAG_COLOR`, `TAG_COLORS`, `isValidTagColor` |
| `.../logic/tags/SnippetTags.kt` | `Snippet.withTagLinked`, `withTagUnlinked`, `List<Snippet>.withTagEdited`, `withTagDeleted`, `tagLinks` |
| `.../logic/tags/TagError.kt` | `TagError` (`Rejected(code, status, serverMessage, expressionError)`, `SessionExpired`, `Http`, `Network`, `Unexpected`), `toTagError` |
| `.../logic/rhyme/RhymeQuery.kt` | `planRhymeQuery(..., tagFilter)`, `RhymeQueryPlan.Blocked` |
| `android/core/model/.../model/Tags.kt` | `Tag`, `UserTag` (`toTag`), `TagCreateRequest`, `TagUpdateRequest`, `TagLinkResponse`, `TagPresetId`, `TagPreset`, `TagPresetCreateRequest`, `TagPresetUpdateRequest`, `TagErrorCode` |
| `.../model/Canvas.kt` | `CanvasDetail` without `tags` |
| `.../model/Rhyme.kt` | `RhymeSuggestRequest.tagFilter` (`tag_filter`, omitted when null) |
| `.../model/error/ApiError.kt` | `ApiError.Validation.expressionError`, `ApiError.expressionErrorFromBody`, `ExpressionErrorBody` |
| `android/core/data/.../data/ApiClient.kt` | `sendQuery(method, path, query, response)` (bodiless call with query parameters) |
| `.../data/repository/TagRepository.kt` | `TagRepository` (`list`, `create`, `update`, `delete`, `link`, `unlink`), `HttpTagRepository` |
| `.../data/repository/TagPresetRepository.kt` | `TagPresetRepository` (`list`, `create`, `update`, `delete`), `HttpTagPresetRepository` |
| `android/app/.../app/tags/TagCanvasPort.kt` | `TagCanvasPort` (implemented by `CanvasViewModel`) |
| `.../app/tags/TagsViewModel.kt` | `TagsViewModel` (filter bar, picker, tag edits, presets; `messages`) |
| `.../app/tags/TagsUiState.kt` | `TagsUiState` (`tagNames`, `pickerRows`, `createOffer`), `PickerSession`, `TagEditor`, `PresetEditor` (`Target.SaveAs`, `Target.Rename`) |
| `.../app/tags/TagsViewModelFactory.kt` | `tagsViewModel(container, canvasId, canvas, owner)` |
| `.../app/tags/TagFilterStore.kt` | `TagFilterStore`, `DataStoreTagFilterStore` |
| `.../app/tags/TagMessages.kt` | `TagError.userMessage`, `TagExprError.userMessage`, `TagNameProblem.userMessage`, `invalidFilterMessage`, `EMPTY_PRESET_MESSAGE` |
| `.../app/tags/ui/ExpressionInput.kt` | `ExpressionInput` (shared field with marker, chips, pills) |
| `.../app/tags/ui/FilterBar.kt` | `FilterBar`, `PresetNameDialog`, `ConfirmDialog` |
| `.../app/tags/ui/PresetMenu.kt` | `PresetMenu`, `MenuAction` |
| `.../app/tags/ui/TagPickerSheet.kt` | `TagPickerSheet` (+ tag edit dialog) |
| `.../app/tags/ui/TagPill.kt` | `TagPill` |
| `.../app/canvas/CanvasUiState.kt` | `Ready.filter`, `Ready.visibleSnippets`, `listRows` over visible snippets |
| `.../app/canvas/CanvasViewModel.kt` | `TagCanvasPort` implementation (`setTagFilter`, `onTagLinked`, ...) |
| `.../app/canvas/CanvasScreen.kt`, `CanvasTopBars.kt`, `SnippetCanvas.kt`, `list/SnippetList.kt` | Filter bar above the body, "Tags" in the selection bar, picker sheet, visible snippets only, "No snippets match the filter" |
| `.../app/rhyme/RhymeUiState.kt`, `RhymeViewModel.kt`, `rhyme/ui/QueryControls.kt`, `rhyme/ui/RhymePanel.kt` | "Only my lines matching" state, actions and controls |
| `.../app/editor/GridEditorRoute.kt`, `nav/AppNavHost.kt` | `extraTopBarActions` ("Tags") and the picker sheet in the editor destination |
| `.../app/di/AppContainer.kt` | `tags`, `tagPresets`, `tagFilters` |

Tests: `core/logic/.../tags/TagExpressionVectorsTest.kt` (every vector, as dynamic tests),
`TagExpressionTest`, `TagCompletionTest`, `ExpressionFieldTest`, `TagPickerTest`, `SnippetTagsTest`,
`TagErrorTest`, `logic/rhyme/RhymeTagFilterPlanTest`; `core/model/.../TagDtoTest`;
`core/data/.../repository/TagRepositoryTest`; `app/.../tags/TagsViewModelTest`, `TagMessagesTest`
(fakes in `TagFakes.kt`), `app/.../canvas/CanvasViewModelTagsTest`,
`app/.../rhyme/RhymeViewModelTagFilterTest`.

## Invariants

- Every position the engine reports or accepts is a code point offset; UTF-16 indices exist only at
  the Compose boundary (`codePointOffsetOf` / `utf16IndexOf`).
- `TagExpr.And` / `Or` have at least two children and none of their own kind (constructor `require`,
  internal constructors; built through the flattening factories).
- `format(parse(format(x))) == format(x)` for every valid `x`; every conformance vector passes.
- The applied canvas filter is always the filter of some valid text; an invalid text never changes it.
- A snippet hidden by the applied filter is not drawn, not hit-testable, not listed and not selected.
- The canvas only shows tag changes the server confirmed (link, unlink, edit, delete).
- The rhyme sheet never sends a known-invalid `tag_filter`; with notes off it sends none.
- Nothing throws: failures are `TagError` / `TagExprError` state or snackbar text.
