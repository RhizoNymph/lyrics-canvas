# Rhyme Sidebar

Engine-backed rhyme search in a right-hand sidebar. Select a snippet (or type a phrase) and engine-scored
candidates appear as a list; nothing reaches the canvas until a row is materialized into a real snippet.
Close the sidebar and nothing else has changed. Feature contract:
[`docs/features/rhyme_view.md`](../features/rhyme_view.md).

## Files

| File | Role |
|------|------|
| `src/types/rhyme.ts` | TS mirrors of the API contract (`RhymeUnit` incl. `word_end`, `RhymeCandidate`, `RhymeSuggestRequest/Response`, `AnchorRef` = `SnippetAnchorRef` (`line_index`, optional `word_start` / `word_end`) \| `TextAnchorRef`, `Slot`, `DerivedFrom`, `DialectMap`, `ScoreWeights`), `AnchorRange` (`lineIndex`, `wordStart`, exclusive `wordEnd`), constants (`RHYME_PAGE_SIZE = 40`, `STRESS_PATTERN`), and the `RhymeError` class (`operation: 'query' \| 'materialize'`) |
| `src/lib/rhyme/anchorRange.ts` | Word-range anchors: `RangeClickState` / `NO_RANGE`, `clickWord(state, lineIndex, wordIndex)` (start / end / clear transitions), `wordSpan` (inclusive clicks to an exclusive range), `sliceGridLine(line, range)` (cells of the range, re-based, leading/trailing rests dropped, as the server slices the echoed `grid_line`), `cellInRange`, `lineWords` / `lineWordCount`, `gridLineOf`, `rangeText`, `rangeFits`, `anchorRefFor(snippet, range?)` |
| `src/lib/rhyme/errors.ts` | `toRhymeError(err, operation)` maps axios/unknown failures to `RhymeError` (`network`, `not_found`, `invalid_request`, `server`, `unknown`) |
| `src/lib/rhyme/geometry.ts` | `Point`, `Viewport`, `ViewSize`, `viewportCenter(viewport, viewSize)` (world point under the canvas centre) |
| `src/lib/rhyme/materializePosition.ts` | `materializePosition({ anchors, counts, viewport, viewSize })`, `MATERIALIZE_GAP_X = 40`, `MATERIALIZE_STEP_Y = 60` |
| `src/lib/rhyme/candidateList.ts` | `listRows(candidates, offset, dismissed)`, `shownRange(rowCount, total)`, `canLoadMore(loading, offset, total)` |
| `src/lib/rhyme/slotFit.ts` | `slotFromGridLine`, `slotFromSnippet`, `slotFromRange(snippet, range)`, `lastNonEmptyLineIndex`, `fits`, `parseManualSlot` |
| `src/lib/rhyme/queryFromSelection.ts` | `selectedSnippets`, `anchorSnippets(selected, snippets, excluded)`, `anchorsFromSelection(selected, snippets, typed, excluded?, ranges?)` |
| `src/lib/rhyme/debounce.ts` | `createDebouncedRunner(fn, ms)` with `schedule` / `runNow` / `cancel` |
| `src/stores/rhymeStore.ts` | `useRhymeStore`: session, params, anchors/candidates, dismissed, exclusions, anchor word ranges, materialize counts, loading/error |
| `src/hooks/useRhymeQuery.ts` | 150 ms debounced re-query on selection / anchor / chip / word range / param changes while the sidebar is open |
| `src/components/sidebar/RhymeSidebar.tsx` | The sidebar shell (380 px, full canvas height): header with close, `QueryControls`, `CandidateList`; `enter()` on mount, `exit()` on unmount |
| `src/components/rhyme/QueryControls.tsx` | Search box (typed anchor), anchor chips, k, min score, slot (from selection / manual), source toggles, rare-words toggle |
| `src/components/rhyme/AnchorChips.tsx` | One chip per selected non-blank snippet with its echoed rhyme key; × excludes it for this query, + restores; ▸ expands the chip to the snippet's lines as clickable `SyllableStrip`s to pick a word range (label becomes the picked text, "part" marker, "whole line" resets) |
| `src/components/rhyme/CandidateList.tsx` | Status line ("Showing a–b of total"), typed error line, scrollable rows, More |
| `src/components/rhyme/CandidateRow.tsx` | One result: text (click = chain search), syllable strip, score, depth, fits badge, source label; click row to expand; Materialize / Dismiss |
| `src/components/rhyme/SyllableStrip.tsx` | Compact one-row grid from `grid_line.cells` (stressed bold, rests "·"); optional `selected` range highlight and `onWordClick(word_index)` (rests never clickable) |
| `src/components/rhyme/AlignmentTable.tsx` | Aligned tail vs anchor, cell by cell, coloured by per-cell total |
| `src/components/rhyme/rhymeColors.ts` | Provenance colours/labels, `NON_FIT_ALPHA`, `alignmentColor` |
| `src/components/rhyme/sidebarStyles.ts` | `SIDEBAR_WIDTH` and shared inline styles |

Tests (vitest, node environment): `src/lib/rhyme/*.test.ts`, `src/stores/rhymeStore.test.ts`. React components are
not unit-tested; the store test mocks `@/api/client`.

## Store (`useRhymeStore`)

| Field | Type | Notes |
|-------|------|-------|
| `active` | boolean | True while the sidebar is mounted |
| `typedAnchor` | string | Search box; becomes a `{kind: 'text'}` anchor |
| `excludedAnchorIds` | `Set<id>` | Selected snippets removed via their chip; pruned to the current selection on every query |
| `anchorRanges` | `Map<snippetId, AnchorRange>` | Word range picked on a chip (`lineIndex`, `wordStart`, exclusive `wordEnd`); a snippet without one anchors on its whole last line. Pruned on every query to selected snippets whose text still has those words (`rangeFits`); kept while the chip is excluded |
| `k` | `1 \| 2 \| 3 \| 4` | Default 2 |
| `minScore` | number | Default 0.5, clamped to [0, 1] |
| `slot` | `Slot \| null` | Derived from the first anchor snippet's picked word range (its words' cells, interior rests included) or else its last non-empty line in `from-selection` mode; user-entered in `manual` mode |
| `slotMode` | `'from-selection' \| 'manual'` | |
| `sources` | `{ notes, dictionary }` | Both true by default |
| `includeRare` | boolean | Sent as `include_rare`; default false |
| `anchors` | `RhymeUnit[]` | Echoed back by the server with phonetics (chips show their `rhyme_key`) |
| `candidates` | `RhymeCandidate[]` | All loaded pages, dismissed ids removed |
| `total`, `offset` | number | Server total; offset of the last loaded page |
| `dismissed` | `Set<id>` | Sent as `exclude_ids`; also receives materialized ids |
| `materializeCounts` | `Map<snippetId, number>` | Materializations per anchor snippet this session (row step-down) |
| `viewSize` | `{ width, height }` | Canvas element size (set by `CanvasViewport`) for the viewport-centre fallback |
| `loading`, `error` | boolean, `RhymeError \| null` | Errors are stored, never thrown |

Actions: `enter`, `exit`, `setTypedAnchor`, `excludeAnchor`, `restoreAnchor`, `setAnchorRange(snippetId, range | null)`,
`setK`, `setMinScore`, `setSlot`, `setSlotMode`, `toggleSource`, `setIncludeRare`, `setViewSize`, `runQuery`, `loadMore`,
`dismiss`, `materialize`. `setAnchorRange` always replaces the `anchorRanges` Map, which is what `useRhymeQuery` watches.

### enter / exit

`enter()` sets `active` and runs `runQuery()` at once, so opening the sidebar with a selection queries
immediately. `exit()` clears typed anchor, exclusions, anchor ranges, anchors, candidates, dismissed, materialize counts,
total/offset, loading and error, and bumps the query sequence so in-flight responses are ignored. `k`,
`minScore`, `slotMode`, `sources`, `includeRare` persist for the session. Canvas snippets are never touched.

### runQuery

1. Returns early when inactive or no canvas is loaded.
2. Exclusions are pruned to ids still selected (a chip removal lasts while the snippet stays selected);
   ranges are pruned to selected snippets whose current grid still has the range's words.
3. Anchors = `anchorsFromSelection(selectedSnippetIds, snippets, typedAnchor, excluded, ranges)`: selected,
   non-blank, non-excluded snippets (id order; `line_index: null`, or `line_index` + `word_start` +
   `word_end` from the snippet's range) followed by the trimmed typed phrase.
4. With no anchors or no enabled source: candidates/anchors/total cleared, no request.
5. Slot: in `from-selection` mode `slotFromRange(first, range)` when the first anchor snippet has a range,
   else `slotFromSnippet(first, null)`; in `manual` mode the manual slot.
6. Request: `{canvas_id, anchors, context: [], slot, k, min_score, sources, include_rare, exclude_ids: [...dismissed],
   limit: 40, offset: 0, weights: null, dialect: null}`.
7. Response: dismissed ids filtered out; `anchors`, `candidates`, `total` replaced, `offset = 0`.
8. A monotonic sequence number drops responses that resolve after a newer query (or after `exit`).

`loadMore` requests `offset + 40` with the same parameters and appends unseen candidates. It is a no-op while
loading or when `offset + 40 >= total`.

### materialize

`materialize(id)`:

1. `anchors = anchorSnippets(selected, snippets, excluded)`; position from `materializePosition`:
   exactly one anchor snippet → `x = anchor.position_x + anchor.width + 40`, `y = anchor.position_y + count * 60`
   where `count` is `materializeCounts[anchor.id]`; otherwise the world point under the canvas centre
   (`viewportCenter(canvasStore.viewport, viewSize)`).
2. `canvasStore.addSnippetFromText(text, x, y, derived_from)` with `derived_from =
   {provenance, source_unit_id: id, score, match_depth, k, anchor_unit_id: best_anchor_id, anchor_text}`.
3. On success the anchor's count is incremented, the row is removed and the id joins `dismissed`.
   Failures become `RhymeError` with `operation: 'materialize'`; the row stays.

The selection is not changed, so the next search still runs against the same anchor and further
materializations step down from it.

## Sidebar

Mounted by `CanvasPage` to the right of the canvas column while `uiStore.rhymeSidebarOpen`; 380 px wide, the
full height of the canvas area, results scroll. Layout top to bottom:

1. Header "Rhymes" with ×.
2. Search box (`typedAnchor`; Enter runs the query immediately, typing re-queries after 150 ms) and the typed
   anchor's echoed rhyme key.
3. Anchor chips: one per selected non-blank snippet, label = last non-empty line (28 chars) or the picked
   word range (with a "part" marker), echoed `rhyme_key` of the anchored text, × to exclude for this
   query. Excluded chips stay, dimmed, with + to restore. ▸ expands the chip: every line of the snippet
   as a numbered `SyllableStrip` from its `grid_data`; word cells are clickable, rests are not. First
   click = start word (a one-word range, queried at once; hint "Now click the end word"), second click on
   the same line = end word (inclusive, either order), the picked cells are highlighted; clicking the
   start word again clears; a click on another line starts over there; "whole line" resets. The
   awaiting-end flag is component state; the range itself is `rhymeStore.anchorRanges`.
4. k (select 1–4), min score (slider), slot (radio from selection / manual; manual count + stress validated by
   `parseManualSlot`, invalid input sends `slot: null` and shows the reason), notes / dictionary / rare words.
5. Status line: "Searching…", "No candidates", or "Showing a–b of total"; More (pages by 40, disabled while
   loading or when everything is loaded). Typed errors render under it as "Query failed (kind): message" or
   "Materialize failed (kind): message".
6. Rows (`listRows`: loaded candidates minus dismissed, limited to `offset + 40`).

### Row

| Element | Content |
|---------|---------|
| Left border + source label | Provenance colour: note `#0ea5e9`, dictionary `#14b8a6`, generated `#71717a`; label `NOTE` / `DICT` / `TEXT` |
| Text | Bold; click sets it as the typed anchor (chain search) |
| Score / depth | `score.toFixed(2)` and `d{match_depth}` |
| Fits badge | "fits slot" (green) when a slot was sent and `fits_slot`; the row is dimmed to `NON_FIT_ALPHA` when a slot was sent and it does not fit |
| Syllable strip | `grid_line.cells`: syllable text per cell, stressed (1/2) bold white, rests "·" |
| Expanded (click the row) | `AlignmentTable`: candidate phones / anchor phones / per-syllable total, coloured by `alignmentColor(total)` from red (0) to green (1), left-to-right (the API returns right-to-left); "vs anchor text · stress" |
| Actions | Materialize, Dismiss |

## Interactions

| Input | Behaviour |
|-------|-----------|
| Toolbar "Rhymes" / header × | `uiStore.toggleRhymeSidebar()`; mounting calls `enter()` (queries immediately with a selection), unmounting calls `exit()` |
| Select snippets / type / remove or restore a chip / pick or clear a word range / change k, min score, slot, sources, rare | Re-query after 150 ms (`useRhymeQuery`) |
| ▸ on a chip | Expand / collapse the snippet's lines (component state) |
| Click a word cell on an expanded chip | `clickWord` transition -> `setAnchorRange(id, range)`; the canvas selection is untouched |
| "whole line" | `setAnchorRange(id, null)` |
| Enter in the search box | `runQuery()` now |
| Click candidate text | `setTypedAnchor(text)` (chain search) |
| Click a row | Toggle the alignment detail (local component state) |
| Materialize | `materialize(id)`: snippet created on the canvas, row removed |
| Dismiss | `dismiss(id)`: row removed, id excluded for the session |
| More | `loadMore()` |

## Data flow

```
toggle sidebar -> RhymeSidebar mounts -> rhymeStore.enter -> runQuery
   -> anchorsFromSelection (minus excluded, with word ranges) + slotFromRange | slotFromSnippet -> nlpApi.rhymeSuggest
   <- anchors (range anchors echo the slice's phonetics, grid_line and word_end) + candidates -> CandidateList rows
change selection / typed anchor / chips / word ranges / params -> useRhymeQuery (150 ms) -> runQuery
materialize -> materializePosition -> canvasStore.addSnippetFromText
   -> snippetApi.create {raw_text, position, derived_from} -> new Snippet in canvasStore (undo snapshot)
   -> row dismissed, anchor count incremented
close -> RhymeSidebar unmounts -> exit -> store cleared; snippets untouched
```
