# Web Rhyme Patterns

Web client of rhyme patterns across lines (rhyme-pattern contract §2). The user selects 2 or more
consecutive lines of one snippet. The server finds the places where those lines rhyme with each other
(**slots**). The sidebar then shows, for each slot, a list of words and phrases that rhyme with **every**
line's member at that place, and the user builds the next line from one pick per slot. The detection
algorithm, the API and its errors are canonical in [rhyme_patterns.md](./rhyme_patterns.md) (backend,
sibling PR). The normal single-anchor sidebar is [rhyme_view.md](./rhyme_view.md); the panel layout is in
[sidebars.md](../frontend/sidebars.md#pattern-mode).

## Scope

- Entering Pattern mode: two clicks on line numbers in an anchor chip's expansion (in either order; the
  first again cancels), or "Pattern (last N lines)" in the normal mode with exactly one snippet selected
  (N = min(4, lines with words), stepper 2..min(16, lines with words)). A range needs at least 2 lines with
  words and at most 16 lines. A single line or a word range keeps the normal behaviour.
- Pattern mode for one snippet; other selected snippets are ignored, and the header says so. "Back to
  single" returns to the normal mode. Closing the sidebar clears both modes.
- Typed wire models (`types/rhymePattern.ts`) and `api/rhymePattern.ts` for `POST
  /api/nlp/rhyme-pattern/detect` and `/suggest`, rejecting with `PatternError` only.
- Slot editing with a pure reducer: add a word (replaces the slot's member on that line), extend a member
  to a word, move a member to another slot, remove a member, start a new slot (from a free word, or from
  the member holding the word), drop a slot, toggle a slot on or off. Reset re-detects. A detection
  threshold slider (default 0.75, 0.3..1) also re-detects.
- Suggest with only the on slots, 150 ms after any edit or change of the shared controls. One
  independently scrollable column per on slot, with per-slot paging through `offsets`.
- Tuple tray: one pick per slot. Make snippet creates one line (the picks in slot order joined by spaces)
  next to the anchor without `derived_from`. Add picks to word bank and Clear.
- Whole-line matches: the user's note lines hitting 2+ slots, hits in slot colours, "N of M slots",
  Materialize (with `derived_from`), dismiss and paging.
- Controls shared with the normal mode: min score, sources, rare words, "Only my lines matching" tag
  filter. Dismissals (×) are per pattern session and sent as `exclude_ids`.

## Non-scope

- The backend: detection, scoring, indexes, endpoints and errors (`feat/rhyme-pattern-backend`).
- Android ([android_rhyme_patterns.md](./android_rhyme_patterns.md)).
- A per-slot `k` override in the UI: `SlotSpec.k` is always sent as null, so the server derives it.
- Weights and dialect overrides: sent as null, as in the normal mode.
- Following edits of the anchor snippet's text during a session. The lines are the ones detected. A slot
  that no longer fits comes back as `pattern_span_invalid` / `pattern_slot_order`, and the message suggests
  Reset.
- Pattern mode across several snippets, or a one-line pattern.

## Data and control flow

```
chip line numbers / "Pattern (last N lines)"
  -> clickLineNumber / lastLinesRange -> checkPatternRange (2+ lines with words, <= 16 lines)
  -> rhymePatternStore.enter(snippetId, range)
       view = detecting{session}
       POST /api/nlp/rhyme-pattern/detect {canvas_id, snippet_id, line_start, line_end, weights: null,
                                           dialect: null, min_score: detectMinScore}
       -> view = ready{session, lines, edit: initialEditState(lines, slots), detected, results: null, picks: {}}
       -> runSuggest()                                   (or detect_failed{error: PatternError})
RhymeSidebar: view.kind !== 'off' -> PatternPanel (540px); useRhymeQuery pauses, re-queries on leaving

runSuggest: params = rhymeStore {minScore, sources, includeRare, tagFilterText -> parse/format} + dismissed
  invalid tag filter -> suggest = failed(invalid_tag_filter), nothing sent
  buildSuggestRequest(session, edit, params, {kind: 'first'})  null (no on slot / no source) -> idle
  POST /api/nlp/rhyme-pattern/suggest {slots: on slots in order (ids kept, members in line order, k: null),
       limit 30, offsets {}, line_matches true, line_match_limit 30, line_match_offset 0, ...}
  -> results = firstPageResults(res, dismissed)        (stale answers dropped by a sequence number)

tap a word -> wordActions(edit, line, word) (each entry pre-run through applySlotEdit; refusals disabled)
  -> store.edit(e) -> applySlotEdit -> ok: edit := next, picks pruned to live slot ids
                                    -> refused: notice = describeSlotEditError(...)
  -> useRhymePatternQuery sees the new edit -> 150 ms -> runSuggest
legend chip -> edit toggle / drop          Reset -> detect again (edits discarded)
detection threshold -> 150 ms -> reset

column More -> loadMoreSlot(slotId): same slots, offsets {slotId: offset + 30}, line_matches false
  -> appendSlotPage (only that column; duplicates and dismissed dropped)
line matches More -> loadMoreLines: offsets {}, line_match_offset + 30 -> appendLineMatches
row tap -> togglePick(slotId, candidate)      × -> dismiss(id): removed from every column, the line
                                                    matches and the picks; sent in exclude_ids next time
Make snippet -> tupleText(trayOf(view)) -> canvasStore.addSnippetFromText(text, x, y) at
  materializePosition([anchor], createdCount) (right of the anchor, one row down per snippet made)
Add picks to word bank -> wordBankStore.addFromText(picks.join(', '), 'rhyme') (toast reports)
line match Materialize -> addSnippetFromText(unit.text, x, y, lineMatchDerivedFrom(...)) -> row removed
```

## Files

| File | Role | Key exports |
|---|---|---|
| `frontend/src/types/rhymePattern.ts` | Wire models mirroring `backend/app/schemas/rhyme_pattern.py`, bounds and page sizes | `PatternLines`, `SpanRef`, `PatternSpan`, `PatternSlot`, `PatternLine`, `PatternDetectRequest/Response`, `SlotSpec`, `PatternSuggestRequest/Response`, `PatternCandidate`, `PatternSlotResult`, `LineMatch`, `LineMatchHit`, `PATTERN_MAX_SLOTS`, `PATTERN_PAGE_SIZE` |
| `frontend/src/api/rhymePattern.ts` | Detect and suggest over an injected axios instance; bound in `api/client.ts` as `rhymePatternApi` | `createRhymePatternApi`, `RhymePatternApi` |
| `frontend/src/lib/rhyme/pattern/types.ts` | Client state types | `PatternSession`, `LineRange`, `Member`, `EditSlot`, `SlotEditState`, `SlotEdit`, `SlotEditError`, `SlotEditResult`, `TuplePick(s)`, `TrayEntry` |
| `frontend/src/lib/rhyme/pattern/slotEdit.ts` | The slot-edit reducer and the order check | `initialEditState`, `applySlotEdit`, `memberAt`, `slotOrderError` |
| `frontend/src/lib/rhyme/pattern/slotView.ts` | Letters, legend data, the word picker's actions, refusal messages | `slotLetter`, `slotViews`, `SlotView`, `wordActions`, `WordAction`, `letterLookup`, `describeSlotEditError` |
| `frontend/src/lib/rhyme/pattern/lineRange.ts` | Line-number clicks, range checks, the last-N shortcut | `clickLineNumber`, `NO_LINE_PICK`, `checkPatternRange`, `describeRangeProblem`, `defaultLastN`, `lastNMax`, `lastLinesRange` |
| `frontend/src/lib/rhyme/pattern/request.ts` | Detect / suggest request building | `buildDetectRequest`, `buildSuggestRequest`, `slotSpecs`, `PatternParams`, `SuggestPage` |
| `frontend/src/lib/rhyme/pattern/results.ts` | Columns and line matches: first page, per-slot and line-match pages, dismiss, paging predicates, hit highlighting | `firstPageResults`, `appendSlotPage`, `appendLineMatches`, `dismissFromResults`, `canLoadMoreSlot`, `canLoadMoreLines`, `serverSlotsOf`, `lineMatchWords`, `slotsHitLabel` |
| `frontend/src/lib/rhyme/pattern/tray.ts` | Tuple tray reducer and text | `togglePick`, `prunePicks`, `trayEntries`, `trayLabel`, `tupleText`, `pickTexts`, `EMPTY_PICKS` |
| `frontend/src/lib/rhyme/pattern/derived.ts` | `derived_from` for a materialized line match | `lineMatchDerivedFrom`, `patternSpanId` |
| `frontend/src/lib/rhyme/pattern/errors.ts` | Typed failures and their messages | `PatternError`, `PatternErrorKind`, `PatternOperation`, `toPatternError`, `describePatternError` |
| `frontend/src/lib/rhyme/pattern/fixtures.ts` | Test fixtures (wire-shaped lines, slots, candidates, matches) | `pline`, `pslot`, `pspan`, `pcand`, `pmatch`, `phit`, `gridOf` |
| `frontend/src/stores/rhymePatternStore.ts` | The session: view union, suggest status, paging, notice, action errors, actions | `useRhymePatternStore`, `PatternView`, `ReadyView`, `SuggestStatus`, `knownSlots`, `trayOf`, `LINES_PAGER` |
| `frontend/src/hooks/useRhymePatternQuery.ts` | 150 ms re-search after edits / shared-control changes; re-detect after threshold changes | `useRhymePatternQuery` |
| `frontend/src/components/rhyme/pattern/PatternPanel.tsx` | Connected panel and its pure view | `PatternPanel` (default), `PatternPanelView`, `PanelActions`, `patternEmptyMessage` |
| `frontend/src/components/rhyme/pattern/PatternHeader.tsx` | Range label, ignored note, Reset, Back to single | default |
| `frontend/src/components/rhyme/pattern/PatternLinesView.tsx` | The lines as word buttons in slot colours, picker under the tapped line | default, `PickedWord` |
| `frontend/src/components/rhyme/pattern/WordPicker.tsx` | The tapped word's actions | default |
| `frontend/src/components/rhyme/pattern/SlotLegend.tsx` | Slot chips: toggle, drop | default |
| `frontend/src/components/rhyme/pattern/SlotColumns.tsx` | One scrollable column per on slot | default, `SLOT_COLUMN_MIN_WIDTH` |
| `frontend/src/components/rhyme/pattern/PatternCandidateRow.tsx` | A slot candidate row | default, `sourceLabel` |
| `frontend/src/components/rhyme/pattern/TupleTray.tsx` | Picks with gaps and the tuple actions | default |
| `frontend/src/components/rhyme/pattern/LineMatchList.tsx` | Whole-line matches | default |
| `frontend/src/components/rhyme/pattern/PatternControls.tsx` | Search options (shared controls) and the detection threshold | default, `DetectThreshold` |
| `frontend/src/components/rhyme/pattern/PatternShortcut.tsx` | "Pattern (last N lines)" in the normal mode | default, `PatternShortcutView` |
| `frontend/src/components/rhyme/SourceToggles.tsx` | Notes / dictionary / rare words checkboxes, shared by both modes | default |
| `frontend/src/components/rhyme/AnchorChips.tsx` | Line numbers are buttons that pick a pattern range | default |
| `frontend/src/components/rhyme/rhymeColors.ts` | Slot palette and highlight alpha | `SLOT_COLORS`, `slotColor`, `slotAlpha` |
| `frontend/src/components/rhyme/sidebarStyles.ts` | Pattern-mode width | `PATTERN_SIDEBAR_WIDTH` |
| `frontend/src/components/sidebar/RhymeSidebar.tsx` | Mode switch, width, title; exits both stores on unmount | default |
| `frontend/src/hooks/useRhymeQuery.ts` | Paused while Pattern mode is on | `useRhymeQuery` |

Tests sit next to each module (`*.test.ts(x)`): the reducers and helpers in `lib/rhyme/pattern/`, the API
with a mock adapter, the store with a mocked client, and the views with `renderToStaticMarkup`.

## Invariants

- Edit state: every slot has at least one member; members are sorted by line with at most one per line; on
  every pattern line the members appear in slot order and do not overlap. `applySlotEdit` returns either a
  state with these properties or a typed refusal with the state unchanged. A slot left without members is
  removed. At most 8 slots.
- Slot ids: detected ones keep their `s` ids; slots the user starts are `u<n>` with `n` from `nextUserId`,
  which only grows, so an id is never reused within a session. Letters (A, B, ...) and colours follow the
  current slot order.
- A new slot goes after every slot whose member on its line ends before it, before every slot whose member
  there starts after it, and among slots with no member on that line by mean relative position (start word
  / line word count).
- Only on slots are sent, in slot order. Off slots keep their members and their pick but leave the columns,
  the tray and the snippet line.
- One pick per slot; picks of dropped slots are pruned; a dismissed candidate's pick goes too.
- A response is applied only if no newer detect or first-page suggest started since. Paging is refused while
  a first page is loading or that pager is busy, and its answer is dropped if a new first page started.
- An invalid tag filter is never sent (local `invalid_tag_filter`), as in the normal mode.
- Nothing touches the canvas except Make snippet and Materialize, which go through
  `canvasStore.addSnippetFromText` (undoable like any new snippet).
- The normal-mode query does not run while Pattern mode is on.

## Wire-shape assumptions (to check against the backend PR)

- Shapes and field names are those of contract §1.2. `PatternSlot.k` is 1..4. `SlotSpec.k` is sent as `null`
  (not omitted). `weights` and `dialect` are sent as `null`.
- `PatternLine.words` lists only the words of the line (rests excluded), with `word_index` 0.. in order. A
  line without words may appear with `words: []`.
- Line-match `hits[].word_start/word_end` index the words of `unit.text` (the display text, split on
  whitespace).
- Errors use `{"detail": {"error": code, "message": ...}}` with the codes `pattern_range_invalid`,
  `pattern_span_invalid`, `pattern_slot_order` and `invalid_tag_expression` (with `expression_error`).
  Anything else is classed by status.
- With `line_matches: false` the response still carries `line_matches: []` and `line_match_total`. The
  client ignores them on a slot page, and ignores the slot results on a line-match page.
- A line match's `derived_from` uses the first hit slot. Its last member's span id
  (`pattern:<snippet_id>:<line>:<start>-<end>`) is the anchor, and its `k` is both `k` and `match_depth`.
