# Android Rhyme Patterns

Android client of "rhyme patterns across lines" (canonical backend doc:
[rhyme_patterns.md](./rhyme_patterns.md); shared contract in the coordinator's
`rhyme-pattern-contract.md`, section 2). Pick 2..16 consecutive lines of one snippet in the rhyme
sheet; the server finds the **slots** where the lines rhyme with each other (multis, internal rhymes,
end rhymes); the user edits them; the sheet shows one independent list per slot of words and phrases
that rhyme with **every** member of that slot, plus the user's own lines that already hit 2+ slots.
Picks for each slot build a tuple that becomes a snippet.

## Scope

- Entering pattern mode from the rhyme sheet: the chip expansion's **Lines** toggle (tap the first
  and the last line number), and **Pattern (last N lines)** with a 2..16 stepper when exactly one
  snippet is selected (default `min(4, line count)`).
- Pattern view (replaces the single-anchor panel inside the same sheet / side pane): header with the
  range and "Back to single", the note about ignored other selected snippets, the pattern lines with
  every word tappable and slot members in slot colours, the slot legend (`A  EH D IY  3/3`; tap =
  on / off, × = drop), the word picker (Add to A/B/..., New slot, Remove from X), Reset, the
  collapsible controls (min score, notes / dictionary / rare words, "Only my lines matching",
  detection threshold), the tuple tray, and the **Slots** / **Whole lines (N)** tabs.
- Slot lists: one column per **on** slot in slot order, paged horizontally (`LazyRow`, a column is
  45% of the pane, at least 140 dp, so the next one peeks); each column scrolls on its own and has
  "More" for that slot's next page. Rows: text, score, "fits", source badge ("my line" / "dict"),
  "+ bank", ×. Tap = pick for the tuple (tap again to unpick).
- Tuple tray: picks in slot order with gaps (`steady · ___ · strong`); Make snippet, Add picks to
  word bank, Clear.
- Whole-line matches tab: note lines with their hits coloured per slot and "N of M slots"; tap to
  materialize next to the anchor; × dismisses; "More" pages.
- Empty states: no slots detected (message, detection threshold, "tap words to make a slot"), a slot
  with no candidates, no line matches, every slot off, both sources off, invalid tag filter.
- Offline: both routes are server-only (`GatedRhymePatternRepository`, `NeedsConnection` while the
  health monitor says down); the failure line says "needs connection".

## Non-scope

- Any backend change (feat/rhyme-pattern-backend owns the engine and routes).
- Single-line patterns (one line keeps the single-anchor behaviour), several snippets at once,
  Cartesian tuple lists, per-slot `k` overrides (the client always sends `k: null`), multi-word spans
  made by tapping (a tap adds one word; detected two-word members are kept as they are).
- Persisting a pattern session: closing the sheet or "Back to single" drops it.

## Data / control flow

```
Single mode (RhymePanel -> QueryControls -> AnchorChipsView, pattern = PatternEntry(state, vm, seed))
  chip ▸ -> "Lines" -> PatternViewModel.toggleLinePick(snippet)            LinePick.AwaitingFirst
  line number tap -> onLineTap(snippet, line, seed) -> pickLine (pure)
      Next(AwaitingLast(first)) | Next(AwaitingFirst) (first tapped again)
      Rejected(keep first, RangeProblem) -> hint under the chip
      Picked(range) -> enter
  one chip: "Pattern (last N lines)" [-][+] -> enterLastLines -> lastLinesRange (pure) -> enter
enter(snippet, range, seed = RhymeUiState.patternSeed(): min score, sources, rare, tag filter)
  -> PatternSession(target = canvas + snippet + PatternRange, label, ignored = other selected snippets)
  -> startDetect(0): Detection.Running -> POST /api/nlp/rhyme-pattern/detect (detectRequest)
       Success -> Detection.Ready(lines, SlotBoard.fromDetect), slotInfo = server slots
       Failure -> Detection.Failed + PatternFailure(DETECT, toPatternError)
Suggest pipeline (one collector for the VM's life):
  state -> PatternUiState.plan() = planPatternSuggest(target, board, params, tagFilter, dismissed)
    -> distinctUntilChanged on the plan without exclude_ids -> debounce(150 ms) -> mapLatest
       Skip / Blocked -> clear columns and line matches (no request)
       Query -> POST /api/nlp/rhyme-pattern/suggest (exclude_ids = dismissed now)
         drop the answer if the plan changed meanwhile; else firstPage -> columns + line matches,
         slotInfo += recomputed slots (keys for the legend)
Edits: word tap -> picker (wordActions: every entry pre-applied, refused ones disabled with a reason)
  -> edit(SlotEdit) -> applySlotEdit (pure)
       Applied -> new board, tray pruned to the on slots -> plan changes -> re-query after 150 ms
       Refused -> notice ("That would put B out of order with A on line 3.")
  legend tap = Toggle, × = Drop; Reset / detection threshold -> startDetect (threshold debounced)
Paging: column "More" -> PatternPage.Slot(id, nextOffset): same slots, offsets {id: offset}, no line
  matches -> appendSlotPage (that column only); lines "More" -> PatternPage.LineMatches(offset), limit 1
  -> appendLineMatchPage. A page answer is dropped when the plan changed meanwhile.
Tray: row tap -> togglePick; Make snippet -> makeSnippetRequest (picks joined by spaces, no
  derived_from) at materializePosition(anchor, made) -> SnippetRepository.create -> port.insertMaterialized
  Add picks to word bank -> bankText (valid picks, comma-joined) -> WordBankQuickAdd.add
Line match tap -> lineMatchRequest (derived_from: the note line, mean hit score, min k of the hit
  slots, anchor "pattern:<sid>:<start>-<end>", anchor text = the pattern lines joined " / ")
  -> create -> insert; the match is dismissed
Back to single -> exit(): session dropped, jobs cancelled. Closing the sheet (×, back, sheet dismiss)
  -> CanvasScreen.closeRhymes = patternVm.exit() + rhymeVm.close().
```

The single-mode `RhymeViewModel` keeps its own session while pattern mode is shown, so "Back to
single" returns to it unchanged.

## Files

Paths under `android/`.

| Module | File | Role / key exports |
|---|---|---|
| `:core:model` | `model/RhymePattern.kt` | Wire mirrors of `app/schemas/rhyme_pattern.py`: `PatternLimits` (2..16 lines, 8 slots, page 30, detect 0.75 / floor 0.3), `SpanRef`, `PatternSpan` (+ `ref`), `PatternSlot` (`k: RhymeK`), `PatternWord`, `PatternLine`, `PatternDetectRequest` / `Response`, `SlotSpec`, `PatternSuggestRequest`, `PatternCandidate` (all `RhymeCandidate` fields + `member_scores`; implements `RhymeUnitFields`), `PatternSlotResult`, `LineMatchHit`, `LineMatch`, `PatternSuggestResponse`, `PatternErrorCode` (`pattern_range_invalid`, `pattern_span_invalid`, `pattern_slot_order`, `invalid_tag_expression`) |
| `:core:logic` | `logic/rhyme/pattern/PatternRange.kt` | `PatternRange` (internal constructor), `RangeProblem`, `RangeCheck`, `patternRange`, `lastLinesRange`, `defaultPatternLineCount`, `snippetLineCount` |
| | `logic/rhyme/pattern/LinePick.kt` | sealed `LinePick` (`Off`, `AwaitingFirst`, `AwaitingLast`), sealed `LinePickStep` (`Next`, `Picked`, `Rejected`), `toggleLinePick`, `pickLine`, `isFirst`, `activeFor` |
| | `logic/rhyme/pattern/SlotBoard.kt` | `SlotId`, `WordSpan` (never empty), `BoardLine`, `BoardSlot` (≥ 1 member, ≤ 1 per line, `refs`), `SlotBoard` (internal constructor; `fromDetect`, `empty`, `onSlots`, `specs`, `slotAt`, `isFull`, `linesWithWords`), internal `orderViolation`, `slotLetter` |
| | `logic/rhyme/pattern/SlotEdit.kt` | sealed `SlotEdit` (`AddWord`, `NewSlot`, `RemoveWord`, `Drop`, `Toggle`), sealed `SlotEditRefusal` (`CrossesOrder`, `NoSuchSlot`, `NoSuchWord`, `NotInSlot`, `TooManySlots`), sealed `SlotEditResult` (`Applied`, `Unchanged`, `Refused`), `applySlotEdit`, sealed `WordAction` (`AddTo`, `NewSlot`, `Remove`), `wordActions` |
| | `logic/rhyme/pattern/PatternQuery.kt` | `PatternTarget`, `PatternParams`, sealed `PatternPage` (`First`, `Slot`, `LineMatches`), `PatternSkip`, sealed `PatternPlan` (`Skip`, `Blocked`, `Query`), `detectRequest`, `planPatternSuggest` |
| | `logic/rhyme/pattern/TupleTray.kt` | `TuplePick`, sealed `TrayCell` (`Picked`, `Gap`), `TRAY_GAP`, `TupleTray` (`toggle`, `prune`, `cells`, `display`, `pickedTexts`, `snippetText`, `bankWords`), `makeSnippetRequest` |
| | `logic/rhyme/pattern/PatternResults.kt` | `SlotColumn` (`canLoadMore`, `nextOffset`, `without`), `LineMatchList`, `firstPage`, `appendSlotPage`, `appendLineMatchPage`, `patternAnchorId`, `lineMatchRequest` |
| | `logic/rhyme/pattern/PatternError.kt` | `PatternOperation`, sealed `PatternError` (`Rejected(code, message, expressionError)`, `NotFound`, `Invalid`, `NeedsConnection`, `Network`, `Server`, `Unexpected`), `PatternFailure`, `toPatternError` |
| `:core:data` | `data/repository/RhymePatternRepository.kt` | `RhymePatternRepository` (`detect`, `suggest`), `HttpRhymePatternRepository` (`POST /api/nlp/rhyme-pattern/detect`, `/suggest`) |
| `:core:sync` | `sync/repo/GatedRhymePatternRepository.kt` | Server-only wrapper: `NeedsConnection` while the gate is closed |
| `:app` | `di/AppContainer.kt` | `rhymePatterns` (gated HTTP repository) |
| | `canvas/CanvasScreen.kt` | `PatternViewModel` next to `RhymeViewModel`; `closeRhymes` ends both; hosts `RhymeSidebar` in the pane and the sheet |
| | `rhyme/ui/RhymeSidebar.kt` | `RhymeSidebar` (pattern panel while a session is active, else `RhymePanel` with a `PatternEntry`), `RhymeUiState.patternSeed()` |
| | `rhyme/ui/RhymePanel.kt`, `QueryControls.kt`, `AnchorChipsView.kt` | Optional `pattern: PatternEntry?` threaded to the chips: Lines toggle, tappable line numbers, last-N shortcut |
| | `rhyme/pattern/PatternUiState.kt` | sealed `Detection` (`Running`, `Failed`, `Ready(lines, board)`), `WordRef`, `PatternTab`, `PatternSession` (`board`, `order`, `pickerActions`, `shownColumns`), `PatternUiState` (`plan()`, `skip`, `tagFilterBlocked`) |
| | `rhyme/pattern/PatternViewModel.kt` | `PatternSeed`, `PatternViewModel(port, patterns, snippets, debounceMs)`: line picking, enter / exit, detect, suggest pipeline, paging, edits, controls, tray, Make snippet, line-match materialize |
| | `rhyme/pattern/PatternLegend.kt` | Pure view models: `SlotChip`, `LineWord`, `serverViewMatches`, `slotChips`, `lineWords`, `columnTitle`, `bankText`, `matchWords`, `slotsHitText` |
| | `rhyme/pattern/PatternMessages.kt` | `refusalText`, `rangeProblemText`, `skipText`, `failureText` |
| | `rhyme/pattern/ui/PatternEntryControls.kt` | `PatternEntry`, `LinesToggle`, `LineNumber`, `LastLinesShortcut` |
| | `rhyme/pattern/ui/PatternPanel.kt` | `PatternPanel` (layout above) |
| | `rhyme/pattern/ui/PatternLinesBlock.kt` | `slotColor`, `PatternLinesBlock` (words + inline picker), `SlotLegend` |
| | `rhyme/pattern/ui/SlotColumnsView.kt` | `SlotColumnsView` (horizontal columns, rows, More) |
| | `rhyme/pattern/ui/LineMatchesView.kt` | `LineMatchesView` (hits coloured, materialize, ×, More) |
| | `rhyme/pattern/ui/TupleTrayBar.kt` | `TupleTrayBar` |
| | `rhyme/pattern/ui/PatternControls.kt` | `PatternControls`, `DetectThreshold` |
| | `ui/theme/Palette.kt` | `Palette.slot(index)` (8 slot colours), `DIMMED_SLOT_ALPHA` |

Tests: `RhymePatternDtoTest` (model), `SlotEditTest`, `WordActionsTest`, `LinePickTest`,
`PatternQueryTest`, `TupleTrayTest`, `PatternResultsTest`, `PatternErrorTest` (logic, fixtures in
`PatternFixtures.kt`), `RhymePatternRepositoryTest` (MockWebServer: both routes, every pattern code,
located tag-expression error, 404), `GatedRhymePatternRepositoryTest`, and in `:app`
`PatternViewModelTest` (fake repository in `PatternFakes.kt`) and `PatternLegendTest`.

## Invariants

- A `SlotBoard` always satisfies the server's span and order rules: members inside their line, at
  most one member per line per slot, no overlap between slots on a line, members in slot order on
  every line, unique ids, at most 8 slots, no slot without a member. Only `fromDetect` (which drops a
  slot that would break them) and `applySlotEdit` build one; a refused edit leaves the board as it was.
- New slot ids are `u<n>`, counting up per board history and skipping ids in use; detected ids are
  kept as the server sent them and echoed in every suggest.
- A new slot goes after every slot whose member on its line precedes the word and before every one
  whose member follows it; among slots with no member on that line, by mean relative position
  (member start / line word count), as the server orders slots.
- Only on slots are sent, in board order, members in line order, `k` null. Line matches are asked for
  only while the notes source is on. A slot page sends the same slots with only that slot's offset.
- `exclude_ids` (dismissed and materialized ids) never re-query on their own; they ride on the next
  query. An answer (first page or page) whose plan no longer matches the controls is dropped.
- The tuple holds at most one pick per slot and only picks of on slots; "Make snippet" text is the
  picks in slot order joined by spaces, sent without `derived_from`.
- Nothing reaches the canvas until Make snippet or a line-match materialize returned the created
  snippet.
- `PatternRange` always has 2..16 lines with at least 2 lines with words, inside the snippet.

## Assumptions about the wire (to confirm against the backend PR)

- Shapes and field names exactly as in the contract (§1.2): `PatternSlotModel.k` is an int 1..4,
  `offsets` is a JSON object keyed by slot id, `PatternCandidateModel` = `RhymeCandidateModel` +
  `member_scores`, `LineMatchModel.unit` = `RhymeUnitModel`, `grid_line` = `RhymeGridLineModel`.
- Errors are `{"detail": {"error": code, "message": ...}}` with status 422 for the pattern codes and
  `invalid_tag_expression` (with `expression_error`), plain-string 404 for canvas / snippet, plain 422
  for dialect / pydantic failures.
- Word indexes in `LineMatchHit` index the whitespace-split words of `unit.text` (display text).
- `derived_from` on a materialized line match is accepted with free-form `anchor_unit_id` /
  `anchor_text` (the backend model has no format rule).
