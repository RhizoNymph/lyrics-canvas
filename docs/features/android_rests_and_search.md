# Android: Rest Marks, Nudge-Right Rest and Search

The Android client of the rest-mark and search contract shared with the backend and the web client
(canonical rules: [rest_marks.md](./rest_marks.md) and [global_search.md](./global_search.md), written
with the backend). Three user requests:

1. `_` in lyric text is a rest: an intentional empty grid cell, written back into the text when the
   grid makes one, so the text always matches the grid.
2. The editor's ▶ nudge at the end of a line inserts a rest before the selection.
3. Search: a live filter inside a canvas, and a search across all canvases from the canvas list.

## Scope

- **Rest marks** in `:core:logic` (`logic.rests`): tokenizer (`_` is one rest and a word separator;
  `word_index` counts words only), display text (strip), and every vector of
  `rest_mark_vectors.json` (a byte-for-byte copy of the backend's
  `backend/tests/fixtures/rest_mark_vectors.json` in `core/logic/src/test/resources/`).
- **Grid helpers**: the local syllable estimate emits `{"rest": true}` cells for `_`; the grid→text
  rebuild emits `_` for each rest; `attachWords` and the rhyme word ranges (`lineWords`) read word
  tokens; the editor's "saved grid still matches its row" check on open compares display text, so
  legacy rows (grid rests, no `_` in the text) open arranged with their rests and the next save writes
  the canonical text.
- **Nudge right** (`nudgeSelection(+1)`, the ▶ button): when nothing follows the selection, it does
  `insertRestInRow(row, selection.start)`: the selection shifts one cell right and the row becomes
  arranged, exactly like `+·`. Nudge left at index 0 stays a no-op. Android has no undo and no
  fixed-beat editor mode, so nothing else changes.
- **Display text consumers on Android**: the rhyme anchor chip label, the card / list-row text fallback
  (cards without grid lines), and "Add to word bank" from an editor text selection. Rhyme word ranges
  use word tokens (so ranges stay valid with rests in the text).
- **In-canvas search (3a)**: a search box above the tag filter bar (canvas and list mode), debounced
  150 ms (a clear or an emptied field applies at once), ANDed with the tag filter and hiding
  non-matching snippets exactly like it (not drawn, not hit, not listed, deselected), "N of M" and ×
  while a search applies, Escape on a hardware keyboard clears. The text lasts per canvas for the app
  process (not persisted).
- **Search across canvases (3b)**: `GET /api/search?q=&limit=` (typed DTOs, `HttpSearchRepository`,
  typed `SearchError` with `invalid_search_query`); a search field on the canvas list screen,
  debounced 250 ms, Canvases and Snippets sections, highlighted excerpts, canvas-title tap targets,
  "No matches", error + Retry.
- **Open focused on a snippet**: `CanvasRoute.focusSnippetId` (nav argument). On load the snippet is
  selected and centred (canvas) or scrolled to (list). An in-canvas search that hides it is cleared;
  if the tag filter hides it, a snackbar says "Hidden by the tag filter".

## Non-scope

- The backend endpoint, the server-side syllabifier / phonetics / exports and their rest handling
  (`feat/rests-search-backend`); the web client (`feat/web-rests-search`).
- Export, similarity, t-SNE and embeddings: not on Android.
- Persisting the in-canvas search, Ctrl+F (web only), the old per-canvas search endpoint.
- Rewriting legacy text on open: a legacy row shows its stored text until it is saved.

## Data and control flow

### Rest marks (`:core:logic`)

```
tokenizeLine(line): replace "_" with " _ ", JS trim + split on whitespace
  -> LineToken.Rest | LineToken.Word(text, wordIndex)      (wordIndex counts words only)
lineWordTokens(line)  = the Word texts                      (replaces every whitespace split of a line)
displayLine(line)     = words joined by " ", or null for a line of rests only
displayText(text)     = lines -> displayLine, nulls dropped, joined by "\n" (stanza breaks kept)

localSyllabifyLine(text): tokens -> Rest => GridCell.REST; Word => splitWord cells (wordIndex, word)
  syllableCount = non-rest cells; rawText = trimmed text
rebuildRawText(line): walk cells; rest -> "_"; first cell of a word (wordIndex or anonymous) -> its
  surface word or concatenated syllables; later cells of that word -> nothing; join with " "
```

### Editor

```
open: initEditorState(rawText, savedGrid)
  row matches its saved line when displayLine(saved.rawText) == displayLine(row text)
  -> kept (attachWords), arranged; the line's rawText is set to the row's trimmed text
typing "_"       -> typeLine -> localSyllabifyLine (rest cells) -> debounced API syllabify (text unchanged)
+· / chunk drag  -> updateGrid -> withRebuiltText -> row text = canonical text with "_"
▶ nudge          -> GridEditorViewModel.nudgeSelection(+1)
                      selection.end == cells.size -> insertRestBeforeSelection() (selection +1)
                      else                        -> moveSelection(start + 1) (unchanged)
◀ nudge at 0     -> moveSelection clamps to the same place -> no-op
save             -> rawTextForSave: arranged rows rebuilt (with "_"), others as typed
```

### In-canvas search

```
CanvasSearchBar -> CanvasViewModel.editSearch(text)
  -> searchInput (StateFlow, shown by the field) + CanvasSearchMemory.set(canvasId, text)
  -> debounce(blank ? 0 : 150 ms) -> applySearch(SearchQuery.parse(text))
       Ready.search = query; selection pruned to visibleSnippets
× / Escape -> clearSearch(): text "" and applySearch(EVERYTHING) at once
Ready.visibleSnippets = snippets where filter.matches(s) && search.matches(s.rawText)
Ready.searchCount     = SearchCount(visible, total) while a search applies ("N of M")
SearchQuery.parse: NFC, trim, split whitespace, lowercase(ROOT)
SearchQuery.matches(raw): every term in lowercase(NFC(displayText(raw)))
```

### Search across canvases

```
GlobalSearchField -> GlobalSearchViewModel.edit(text)
  combine(text, retry attempts) -> checkGlobalSearch -> distinctUntilChanged
  -> debounce(Blank ? 0 : 250 ms) -> collectLatest (a newer query cancels the request in flight)
     Blank        -> Off (the normal canvas list)
     TooLong(n)   -> Failed(InvalidQuery) without a request
     Valid(q)     -> Loading -> SearchRepository.search(q, 50)
                       Success -> Loaded(SearchQuery.parse(q), canvases, snippets) ("No matches" when both empty)
                       Failure -> Failed(toSearchError(error))                      (Retry -> attempts + 1)
Canvas hit tap            -> onOpen(canvasId)         -> CanvasRoute(id)
Snippet hit row tap       -> first canvas             -> CanvasRoute(id, focusSnippetId)
Snippet hit canvas title  -> that canvas              -> CanvasRoute(id, focusSnippetId)
excerpt highlight: NFC excerpt, highlightSpans(excerpt, query) (case-insensitive, merged);
  positions are found in the excerpt (it may start / end with "…"); "" shows "(no text)"
```

### Focus on a snippet

```
CanvasRoute(canvasId, focusSnippetId) -> nav args -> SavedStateHandle["focusSnippetId"]
CanvasViewModel.reload() success -> applyFocus() (once: SavedStateHandle["focus_handled"])
  planFocus(snippets, id, tagFilter, search)
    Missing -> snackbar "That snippet is no longer on this canvas"
    Reveal(clearSearch, hiddenByTags)
      clearSearch  -> clearSearch()
      hiddenByTags -> snackbar "Hidden by the tag filter" (not selected)
      else         -> selection = {id}; watch the tag filter's first say on it
      canvas mode  -> centreOn(id): centredOn(viewport, card centre, viewSize), deferred until
                      setViewSize reports a non-zero size; the viewport save is scheduled as for a pan
      list mode    -> CanvasEvent.RevealSnippet(id) -> the screen scrolls the LazyColumn
setTagFilter (the DataStore filter restored after load) -> if it hides the watched snippet:
  snackbar "Hidden by the tag filter" (once); the watch ends either way
```

## Files

| File | Role / key exports |
|---|---|
| `android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/rhyme/SlotFit.kt` | `lastNonEmptyLineIndex` skips grid lines made only of rests, matching the backend's default snippet anchor (last line with words) |
| `android/core/logic/.../logic/rests/RestMarks.kt` | `REST_MARK`, sealed `LineToken` (`Word(text, wordIndex)`, `Rest`), `tokenizeLine`, `lineWordTokens`, `displayLine`, `displayText` |
| `.../logic/grid/LocalSyllabify.kt` | `localSyllabifyLine` emits rest cells for `_` |
| `.../logic/grid/RawText.kt` | `rebuildRawText` emits `_` per rest (vectors `rebuild`) |
| `.../logic/grid/WordIndex.kt` | `attachWords` over word tokens |
| `.../logic/rhyme/AnchorRange.kt` | `lineWords` = word tokens |
| `.../logic/editor/EditorRowsState.kt` | `initEditorState`: display-text match on open |
| `.../logic/search/SearchQuery.kt` | `SearchQuery` (`parse`, `terms`, `isEmpty`, `matches`, `EVERYTHING`) |
| `.../logic/search/GlobalSearch.kt` | `MAX_SEARCH_QUERY_LENGTH`, `GlobalSearchInput` (`Blank`, `TooLong`, `Valid`), `checkGlobalSearch`, `TextSpan`, `highlightSpans`, sealed `SearchError` (`InvalidQuery`, `SessionExpired`, `Http`, `Network`, `Unexpected`), `toSearchError` |
| `.../logic/canvas/Viewport.kt` | `centredOn(viewport, world, viewSize)` |
| `android/core/model/.../model/Search.kt` | `CanvasSearchHit`, `SnippetSearchHit`, `SearchResponse`, `SearchLimits`, `SearchErrorCode` |
| `android/core/data/.../repository/SearchRepository.kt` | `SearchRepository`, `HttpSearchRepository` (`GET /api/search`, limit clamped to 1..200) |
| `android/app/.../app/editor/GridEditorViewModel.kt` | `nudgeSelection` rest at the end of the line |
| `.../app/canvas/search/CanvasSearch.kt` | `CANVAS_SEARCH_DEBOUNCE_MS`, `HIDDEN_BY_TAG_FILTER`, `FOCUS_SNIPPET_MISSING`, `CanvasSearchMemory`, `SearchCount`, `FocusPlan` (`Missing`, `Reveal`), `planFocus` |
| `.../app/canvas/search/CanvasSearchBar.kt` | `CanvasSearchBar` (field, "N of M", ×, Escape) |
| `.../app/canvas/CanvasUiState.kt` | `Ready.search`, `visibleSnippets` (tag filter AND search), `searchCount`, `isFiltered` |
| `.../app/canvas/CanvasViewModel.kt` | `searchInput`, `editSearch`, `clearSearch`, focus (`FOCUS_SNIPPET_KEY`, `FOCUS_HANDLED_KEY`), centring |
| `.../app/canvas/CanvasScreen.kt` | Search bar above the filter bar; `searchMemory = container.canvasSearches` |
| `.../app/canvas/render/CardLayout.kt`, `canvas/list/ListCardSize.kt` | `fallbackTextOf` (display text) for cards without grid lines |
| `.../app/rhyme/AnchorChips.kt` | `chipLabel` shows display text |
| `.../app/wordbank/WordBankQuickAdd.kt` | `add` strips rest marks before splitting |
| `.../app/canvaslist/search/GlobalSearchViewModel.kt` | `GLOBAL_SEARCH_DEBOUNCE_MS`, `GlobalSearchResults` (`Off`, `Loading`, `Loaded`, `Failed`), `GlobalSearchUiState`, `GlobalSearchViewModel` (`edit`, `clear`, `retry`) |
| `.../app/canvaslist/search/GlobalSearchResultsView.kt` | `GlobalSearchField`, `GlobalSearchResultsView` (sections, type chip, highlighted excerpt, canvas title targets) |
| `.../app/canvaslist/search/SearchMessages.kt` | `SearchError.userMessage`, `NO_MATCHES`, `INVALID_SEARCH_MESSAGE` |
| `.../app/canvaslist/CanvasListScreen.kt` | Search field; results replace the list while a query is present; `onOpenSnippet` |
| `.../app/nav/Routes.kt`, `nav/AppNavHost.kt` | `CanvasRoute(canvasId, focusSnippetId = null)`; snippet hits navigate with it |
| `.../app/di/AppContainer.kt` | `canvasSearches`, `search` |

Tests: `core/logic/.../rests/RestMarkVectorsTest` (every vector as dynamic tests, plus the local
estimate over every tokenize vector), `RestMarksTest`, `search/SearchQueryTest`,
`canvas/CentredOnTest`; `core/data/.../repository/SearchRepositoryTest`;
`app/.../editor/GridEditorRestsTest`, `app/.../canvas/CanvasViewModelSearchTest`,
`app/.../canvas/RestConsumersTest`, `app/.../canvaslist/search/GlobalSearchViewModelTest`.
Updated for the new rule (rests now appear in rebuilt text, nudge right at the end no longer a
no-op): `RawTextTest`, `EditorRowsStateTest`, `GridEditorViewModelTest`.

## Invariants

- One tokenizer: every place that needs a line's words uses `lineWordTokens` / `tokenizeLine`, so
  `word_index` never counts rests and existing `(line_index, word_index)` overrides stay valid.
- An arranged row's saved text is `rebuildRawText` of its grid: rests appear as `_`, so the text
  matches the grid; an unarranged row keeps its typed `_`s.
- A grid line's `rawText` equals its row's trimmed text (also for a display-text match on open).
- `rest_mark_vectors.json` in the test resources is byte-identical to the backend's canonical copy.
- The applied search and tag filter are ANDed; a snippet either hides it is not drawn, hit, listed
  or selected.
- The focus from a nav argument is applied at most once per back-stack entry; a hidden snippet is
  never selected.
- No search request is sent for a blank or over-200-code-point query, or with a limit outside 1..200.
- Nothing throws: failures are `SearchError` state or snackbar text.
