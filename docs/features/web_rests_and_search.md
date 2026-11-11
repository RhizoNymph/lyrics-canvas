# Web Rests and Search

Web client of the rest-search contract: `_` rest marks in lyric text (two-way with the beat grid), the
nudge-right rest insert in the inline editor, the in-canvas search filter, and the canvas list page's search
across canvases. The text rules are canonical in [rest_marks.md](./rest_marks.md) and checked by the shared
vectors `spec/rest_mark_vectors.json`; the endpoint is [global_search.md](./global_search.md).

## Scope

- Rest marks: tokenizer, word tokens, display text (strip) in `lib/grid/restMarks.ts`; `localSyllabifyLine`
  emits a bare `{rest: true}` per `_`; `rebuildRawText` writes `_` per rest; `attachWords` maps
  `word_index` to word tokens.
- Inline editor: rows keep the `_` the user typed; grid edits (`+·`, drags, nudge) write rests back as `_`;
  rows saved before rest marks (grid rests, no `_`) still open arranged (display-text match) and the next
  save writes the canonical text.
- Nudge: ArrowRight on a selection with no cell after it inserts a rest before the selection (selection
  moves right); ArrowLeft at index 0 does nothing. The web editor has no ▶ button.
- Display text where the client consumes text as words: rhyme anchor words, chip labels and the
  "has words" check; word bank "Add to word bank" from an editor selection; list row and Pixi card text
  fallbacks; similarity panel previews.
- In-canvas search (3a): a box at the start of the filter row under the toolbar; 150 ms debounce; filters the
  canvas and list mode by display text, AND the tag filter, hiding non-matches like the tag filter; "N of M";
  × and Escape clear; Ctrl/Cmd+F focuses it; text per canvas for the session only.
- Search across canvases (3b): typed `searchApi.searchAll` (`GET /api/search`), a header input on the canvas
  list page with a 250 ms debounce, Canvases and Snippets sections, highlighted excerpts, canvas title links,
  "No matches", typed errors.
- Focus route `/canvas/:id?snippet=<sid>`: select and centre (list mode: scroll), clear the in-canvas search
  if it hides the snippet, toast "Hidden by the tag filter" if the tag filter still hides it.
- Removed: `SearchModal`, its toolbar button, `uiStore.searchModalOpen` / `setSearchModalOpen` and
  `nlpApi.search` (the backend `GET /api/canvases/{id}/search` stays).

## Non-scope

- Server-side work: syllabify, export, phonetics, similarity, embeddings, rhyme notes and search all apply the
  rest rules on the backend (sibling PR).
- Highlighting matches inside canvas cards or list rows (non-matches are hidden, matches are not marked).
- Pruning the selection when a filter hides selected snippets (as with the tag filter).
- Persisting the in-canvas search, or searching on the server from inside a canvas.
- Android ([android_rests_and_search.md](./android_rests_and_search.md)).

## Data and control flow

### Rest marks

```
typing "I _ go"          -> typeLine -> localSyllabifyLine: tokenizeLine -> [I] [rest] [go]
                            cells [{I, wi0, word I}, {rest:true}, {go, wi1, word go}], syllable_count 2
300 ms debounce          -> POST /api/nlp/syllabify (server returns rest cells for _) -> mergeSyllabified
                            (match on trimmed raw_text, which carries the _) -> attachWords (word tokens)
+· / → at end / drag     -> insertRestInRow / moveChunkInRow -> withRebuiltText -> row text "I _ go"
save                     -> rawTextForSave: arranged rows = rebuildRawText(grid) (canonical, with _)
open editor              -> initEditorState: a saved line matches its row on equal trimmed text, or on
                            equal displayLine (non-empty); the kept line's raw_text := row text trimmed
```

`rebuildRawText`: walk the cells; a rest emits `_`; the first syllable of a word key (`word_index`, or a
unique key per index-less cell) emits the whole word (its `word`, else all its syllables concatenated); later
syllables emit nothing; pieces joined by one space; words without text are dropped. So a rest between the
syllables of "hello" gives `hello _`.

### Nudge

`nudgeSelection(state, selection, direction)` (editorState.ts) returns the next state and selection:

- `→` with `end == cells.length`: `insertRestInRow(row, start)`, selection `[start+1, end+1)`;
- `←` with `start == 0`: unchanged (same state object);
- otherwise `moveChunkInRow(row, start, end, start ± 1)`, selection shifted by one.

`InlineGridEditor` applies it on ArrowLeft / ArrowRight when a selection exists; the change is saved with the
editor's other edits, exactly like `+·`.

### In-canvas search

```
input onChange           -> canvasSearchStore.setText(text)  (text now; query after 150 ms, same canvas only)
Escape / ×               -> canvasSearchStore.clear()        (text and query at once)
Ctrl/Cmd+F (CanvasPage)  -> canvasSearchStore.requestFocus() -> CanvasSearchBox focuses + selects
CanvasViewport, ListView -> visibleSnippets(snippets, tagFilter.applied, canvasSearchStore.query)
TagExpressionBar         -> filterCountLabels: tag bar "N of M shown", search box "N of M" (both filters)
CanvasPage mount / id    -> canvasSearchStore.open(id)       (restores this session's text for the canvas)
```

Matching (`lib/search/match.ts`): the query is NFC, trimmed, split on whitespace and lowercased; a snippet
matches when every term is a substring of `displayText(raw_text)` NFC lowercased. No terms match everything.

### Search across canvases

```
header input             -> useGlobalSearch.setInput(raw) -> createGlobalSearch.setInput
                              checkGlobalQuery: blank -> view off (normal list, lastResult reset)
                                                > 200 code points -> failed(invalid_search_query), no request
                                                else -> loading{q, previous} then after 250 ms
                              searchApi.searchAll(q, {signal}) (older request aborted; late answers dropped)
                              -> results{q, query, result} | failed{q, SearchError}
GlobalSearchResultsView  -> Canvases (title, highlighted; click opens /canvas/:id)
                            Snippets (type chip, every canvas title a link, excerpt highlighted with
                            highlightSegments; row click opens the first canvas; empty excerpt "No text")
                            "No matches" when both lists are empty; describeSearchError for failures
```

Highlights are computed on the client with the matching rule (the server excerpt may be cut with `…`). Canvas
titles are highlighted literally (`_` in a title is a character, as on the server).

### Focus on a snippet

```
navigate(canvasPath(cid, sid))        -> /canvas/cid?snippet=sid
CanvasPage                            -> loadCanvas(id).then(setLoadedId(id))   (fresh snippets only)
useSnippetFocus                       -> runSnippetFocus(sid, ports):
                                           planSnippetFocus: missing | {clearSearch, hiddenByTag}
                                           missing -> toast "That snippet is no longer on this canvas"
                                           clearSearch -> canvasSearchStore.clear()
                                           setSelection({sid})
                                           list mode -> canvasSearchStore.requestReveal(sid) -> ListView scrolls
                                           canvas    -> setViewport(viewportCentredOn(snippet, view, zoom))
                                           hiddenByTag -> toast "Hidden by the tag filter"
                                      -> remove ?snippet= (replace)
```

## Files

| File | Role | Key exports |
|------|------|-------------|
| `spec/rest_mark_vectors.json` | Shared vectors (byte-identical to the backend's `backend/tests/fixtures/` copy and Android's) | |
| `frontend/src/lib/grid/restMarks.ts` | Tokenizer and display text | `REST_MARK`, `LineToken`, `tokenizeLine`, `lineWords`, `displayLine`, `displayText`, `hasWords` |
| `frontend/src/lib/grid/restMarks.vectors.test.ts` | Every vector (tokenize, word indexes, rebuild, display, match, plus syllabify) read from `spec/` | |
| `frontend/src/lib/grid/localSyllabify.ts` | Rest cells for `_` | `localSyllabifyLine` |
| `frontend/src/lib/grid/rawText.ts` | Canonical text from cells | `rebuildRawText`, `withRebuiltText`, `rebuildRawTextAll` |
| `frontend/src/lib/grid/wordIndex.ts` | `attachWords` over word tokens | `attachWords` |
| `frontend/src/components/canvas/editorState.ts` | Display-text match on open; nudge | `initEditorState`, `nudgeSelection`, `CellSelection` |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | Arrow keys -> `nudgeSelection`; word bank add strips rest marks; hint line | |
| `frontend/src/lib/rhyme/anchorRange.ts`, `queryFromSelection.ts`, `components/rhyme/AnchorChips.tsx` | Anchor words, labels and the anchor check use word tokens / display text | `lineWords`, `anchorSnippets` |
| `frontend/src/components/list/ListRow.tsx`, `canvas/SnippetCard.tsx`, `sidebar/SimilarityPanel.tsx` | Text fallbacks and previews show display text | |
| `frontend/src/lib/search/match.ts` | Query parsing and matching | `SearchQuery`, `EMPTY_SEARCH`, `parseSearchQuery`, `isSearchActive`, `matchesSearch`, `searchableText` |
| `frontend/src/lib/search/highlight.ts` | Excerpt runs to highlight | `highlightSegments`, `HighlightSegment` |
| `frontend/src/lib/search/counts.ts` | Count lines of the filter row | `filterCountLabels` |
| `frontend/src/lib/search/errors.ts` | Typed failures and the local query check | `SearchError`, `SearchErrorKind`, `toSearchError`, `describeSearchError`, `checkGlobalQuery`, `MAX_GLOBAL_QUERY_LENGTH` |
| `frontend/src/lib/search/globalSearch.ts` | Debounced latest-only search controller | `createGlobalSearch`, `GlobalSearchView`, `GLOBAL_SEARCH_DEBOUNCE_MS` |
| `frontend/src/lib/search/focus.ts` | Focus plan, viewport centring, route helpers | `planSnippetFocus`, `runSnippetFocus`, `FocusPorts`, `viewportCentredOn`, `snippetFocusParam`, `canvasPath`, `HIDDEN_BY_TAG_FILTER` |
| `frontend/src/lib/canvas/visibility.ts` | Tag filter AND search | `isSnippetShown`, `visibleSnippets(snippets, tag, search?)` |
| `frontend/src/types/search.ts` | Wire shapes of `GET /api/search` | `GlobalSearchResponse`, `SearchCanvasHit`, `SearchSnippetHit` |
| `frontend/src/api/search.ts` | Typed endpoint over an injected axios (`client.ts` exports `searchApi`) | `createSearchApi`, `SearchApi` |
| `frontend/src/stores/canvasSearchStore.ts` | In-canvas search state | `useCanvasSearchStore`, `CANVAS_SEARCH_DEBOUNCE_MS`, `resetCanvasSearchSession` |
| `frontend/src/components/search/CanvasSearchBox(View).tsx` | The in-canvas search box | |
| `frontend/src/components/search/GlobalSearchResultsView.tsx` | Canvas list results | |
| `frontend/src/components/toolbar/TagExpressionBar.tsx` | Filter row: search box + tag filter bar, combined counts | |
| `frontend/src/hooks/useGlobalSearch.ts`, `hooks/useSnippetFocus.ts` | Bind the controller / the focus to React and the stores | `useGlobalSearch`, `useSnippetFocus` |
| `frontend/src/components/pages/CanvasListPage.tsx`, `CanvasPage.tsx` | Header search input and results; Ctrl/Cmd+F, store open, focus after load | |

## Invariants

- The editor's grid line `raw_text` (trimmed) equals its row text (trimmed) after every reducer, including
  rows matched to a legacy saved line; `word_index` addresses word tokens only, so a `_` never shifts it.
- `rebuildRawText` output tokenizes back to the same rests and words in cell order of first appearance.
- A rest cell is exactly `{rest: true}` when produced locally; `syllable_count` excludes rests.
- Display text never contains `_`; a `_` search term matches no snippet.
- The in-canvas query applies only to the canvas it was typed on; a debounce pending when the canvas changes
  is dropped.
- The global search shows only the answer to the latest input; blank input never sends a request; more than
  200 code points is refused locally.
- Focusing never changes the tag filter; it clears the in-canvas search only when that search hides the
  snippet, and it runs only after this page's own load of the canvas resolved.
