# Web Rhyme Order: "Rhyme, then meaning"

Web client of the rhyme order toggle (round-3 contract section B). Every rhyme list on web (the rhyme
sidebar's single-line suggest, the pattern mode's slot columns, and the word bank's "Rhymes with" view)
can be shown in **Rhyme** order (score order, as before) or **Rhyme, then meaning**: candidates grouped into
fixed rhyme-score bands (Perfect / Strong / Near / Loose), bands in rhyme order, and each band sorted by how
close the candidate is in meaning (embedding cosine similarity) to the anchor. The server does the sorting
and sends each row's `band` and `similarity`; the client sends the order, draws band headers and a quiet
similarity percent, and remembers the choice per viewer.

Backend counterpart: the rhyme-order backend branch (`order` field on the three endpoints, `band`,
`similarity`, `order_applied`); see its feature doc once merged.

## Scope

- One shared, per-viewer order choice (`rhymeOrderStore`, localStorage `lyrics-canvas.rhymeOrder`), default
  `rhyme`. The same toggle appears in three places and drives all three lists:
  - rhyme sidebar, normal mode: "Order" row in `QueryControls`, under k / min score;
  - pattern mode: above the slot columns (shown while at least one slot is on);
  - word bank, Rhymes tab, "Rhymes with": under the min score slider.
- Requests: `order: "meaning"` on `POST /api/nlp/rhyme-suggest`, `POST /api/nlp/rhyme-pattern/suggest` and
  `POST /api/word-bank/rhymes` when meaning is chosen; nothing is sent for rhyme order (the server default).
- Changing the order refetches from the first page (sidebar: the 150 ms debounced re-query; pattern: the
  150 ms debounced re-suggest; word bank: the 250 ms debounced reload).
- Response handling: each list keeps the order it **asked for** next to the `order_applied` the server
  answered with, and presents itself as one of:
  - `plain`: rhyme asked, rhyme given; no headers.
  - `bands`: meaning applied; a band header before the first row of each band run. Appended pages ("More"
    in the sidebar, per-column "More" in pattern mode) continue the current band with no repeated header.
  - `unavailable`: meaning asked, rhyme given (embedding model unavailable): a quiet italic
    "Meaning order unavailable" and no headers.
- Each row shows `≈NN%` (similarity, rounded) in muted monospace next to its score when the server sent a
  similarity; nothing in rhyme order (similarity is null there).
- Pattern mode: the order applies per slot column; whole-line matches are unaffected (the server keeps their
  order; the client never bands them).

## Non-scope

- Client-side sorting or banding of rows by score: the server orders; the client only inserts headers
  where `band` changes in the order received.
- Per-list order choices (one choice for all three lists, by design: "remembered per device").
- Word bank Families view (not a ranked rhyme list), related words (already a meaning search).
- Android (its own branch).

## Data and control flow

```
RhymeOrderToggle ──setOrder──> rhymeOrderStore.order ──saveRhymeOrder──> localStorage
                                    │
         ┌──────────────────────────┼──────────────────────────────┐
         ▼                          ▼                              ▼
useRhymeQuery (deps: order)  useRhymePatternQuery (deps)   RhymesTab debounce key (…|order)
   runQuery()                    runSuggest()                 loadRhymes(anchor)
   order = currentRhymeOrder()   currentParams().order        order = currentRhymeOrder()
   buildRequest(0, order)        buildSuggestRequest(...)     rhymesBody(..., order)
   ...orderField(order)          ...orderField(params.order)  ...orderField(order)
         │                          │                              │
         ▼                          ▼                              ▼
 rhymeStore.ordering =        results.ordering =             rhymes = ready(response)
 resultOrdering(order,        resultOrdering(order,           rhymesOrder = order
   res.order_applied)           res.order_applied)
         │                          │                              │
         ▼                          ▼                              ▼
 CandidateListView            PatternPanel → SlotColumns     RhymesWithView
 presentationOf(ordering)     presentation=presentationOf    orderPresentation(requestedOrder,
 withBandHeaders(rows, p)     withBandHeaders per column       value.order_applied)
 BandHeader / OrderNote       BandHeader compact / OrderNote withBandHeaders / OrderNote
```

Paging keeps the order of the loaded results, not the live toggle:

- Sidebar `loadMore` builds its request with `ordering.requested`, so a "More" pressed in the 150 ms
  between a toggle and the re-query still appends a page in the same order (then the re-query replaces
  everything from offset 0).
- Pattern `loadMoreSlot` / `loadMoreLines` override the live order with `results.ordering.requested`;
  `appendSlotPage`, `appendLineMatches` and `dismissFromResults` keep `ordering` untouched.

`orderField(order)` is `{}` for `rhyme` and `{ order: 'meaning' }` for meaning, so rhyme-order requests are
byte-identical to the ones sent before this feature (the existing request-shape tests are unchanged).

## Files

| File | Role / key exports |
|------|--------------------|
| `frontend/src/lib/rhyme/order.ts` | `RhymeOrder`, `RhymeBand`, `RHYME_ORDERS`, `RHYME_ORDER_LABELS`, `RHYME_BANDS`, `BAND_MIN_SCORE` (0.95 / 0.85 / 0.75, the one client copy of the band edges), `BAND_LABELS`, `bandOf(score)`, `rowBand(row)` (server band, else from score), `orderField(order)`, `OrderPresentation`, `orderPresentation(requested, applied)`, `ResultOrdering`, `RHYME_ORDERING`, `resultOrdering`, `presentationOf`, `BandedItem<T>`, `withBandHeaders(rows, presentation)`, `similarityPercent`, `MEANING_UNAVAILABLE_NOTE`; persistence `RHYME_ORDER_STORAGE_KEY`, `parseRhymeOrder`, `loadRhymeOrder`, `saveRhymeOrder` (typed `SaveRhymeOrderResult`, never throws) |
| `frontend/src/stores/rhymeOrderStore.ts` | `useRhymeOrderStore` (`order`, `setOrder`, initialised from storage, writes on change only), `currentRhymeOrder()` for stores |
| `frontend/src/types/rhyme.ts` | `RhymeCandidate.band?`, `.similarity?`; `RhymeSuggestRequest.order?: 'meaning'`; `RhymeSuggestResponse.order_applied?` |
| `frontend/src/types/rhymePattern.ts` | `PatternSuggestRequest.order?`, `PatternSuggestResponse.order_applied?` (pattern candidates extend `RhymeCandidate`, so they carry `band` / `similarity`) |
| `frontend/src/types/wordBank.ts` | `BankRhymesBody.order?`, `BankRhymeMatch.band?`, `.similarity?`, `BankRhymesResponse.order_applied?` |
| `frontend/src/stores/rhymeStore.ts` | `ordering: ResultOrdering` (reset by `exit` and by empty / blocked queries); `buildRequest(offset, order)`; `runQuery` reads `currentRhymeOrder()`, `loadMore` reuses `ordering.requested` |
| `frontend/src/hooks/useRhymeQuery.ts`, `hooks/useRhymePatternQuery.ts` | `order` in the re-query dependencies |
| `frontend/src/lib/rhyme/pattern/request.ts` | `PatternParams.order?` → `orderField` in `buildSuggestRequest` |
| `frontend/src/lib/rhyme/pattern/results.ts` | `PatternResults.ordering`; `firstPageResults(res, dismissed, requested = 'rhyme')` |
| `frontend/src/stores/rhymePatternStore.ts` | `currentParams` adds the order; `fetchPage` uses the column's order for later pages |
| `frontend/src/lib/wordBank/queries.ts` | `rhymesBody(anchor, filter, minScore, order = 'rhyme')` |
| `frontend/src/stores/wordBankQueryStore.ts` | `rhymesOrder` (order of the latest request); `loadRhymes` sends `currentRhymeOrder()` |
| `frontend/src/components/rhyme/order/RhymeOrderToggle.tsx` | `RhymeOrderToggleView` (radio group "Rhyme order"), default export bound to the store |
| `frontend/src/components/rhyme/order/orderParts.tsx` | `BandHeader` (`data-band`, `compact` for columns), `SimilarityBadge` (`≈NN%`), `OrderNote` |
| `frontend/src/components/rhyme/CandidateList.tsx` | Split into `CandidateListView` (props = store slice, testable) and the connected default; bands rows, shows the note |
| `frontend/src/components/rhyme/CandidateRow.tsx`, `pattern/PatternCandidateRow.tsx` | `SimilarityBadge` before the score |
| `frontend/src/components/rhyme/QueryControls.tsx` | "Order" toggle |
| `frontend/src/components/rhyme/pattern/PatternPanel.tsx`, `pattern/SlotColumns.tsx` | Toggle + note above the columns; `SlotColumns.presentation?` bands each column |
| `frontend/src/components/wordBank/RhymesWithView.tsx`, `RhymesTab.tsx` | Toggle, `requestedOrder?` prop, banded matches with similarity; `order` in the reload key |

Tests: `lib/rhyme/order.test.ts`, `stores/rhymeOrderStore.test.ts`, `stores/rhymeStore.order.test.ts`,
`lib/rhyme/pattern/order.test.ts`, `stores/rhymePatternStore.order.test.ts`, `lib/wordBank/queries.order.test.ts`,
`stores/wordBankQueryStore.order.test.ts`, `components/rhyme/order/order.test.tsx` (static markup of the
toggle, parts, `CandidateListView`, `SlotColumns`, `RhymesWithView`).

## Invariants

- The server is the only sorter. Headers are inserted strictly where `rowBand(row)` changes in the order
  received; header keys are unique even if a band reappears.
- Headers appear only when `order_applied === "meaning"`; a missing `order_applied` reads as `rhyme`.
- The "Meaning order unavailable" note appears only when meaning was asked and rhyme was applied.
- A rhyme-order request carries no `order` field.
- Pages appended to a result set are always fetched in that set's requested order; a toggle change always
  restarts from offset 0 (pattern: `offsets: {}`, `line_match_offset: 0`).
- Storage failures never break the toggle: reads fall back to `rhyme`, writes return a typed failure and log
  `rhyme order: write failed key=… order=…` at warn.

## Decisions

- **`band`, `similarity`, `order_applied` and `order` are optional in the TS wire types**, although the
  contract says the server always sends the first three. Existing tests (which this branch must not change)
  build candidates, bank matches and responses without them; optional fields keep them compiling. The
  client derives a missing band from the score with the same edges (`rowBand`), and a missing
  `order_applied` reads as `rhyme`.
- **`order` is omitted for rhyme** rather than sent as `"rhyme"`: same server behaviour, and existing tests
  assert exact request bodies.
- **One shared choice** in its own store rather than a field on `rhymeStore`: the word bank must not depend
  on the rhyme sidebar's session store, and the three lists are documented as one per-device setting.
- **Similarity as a quiet percent** (`≈72%`) rather than a second bar, to keep rows uncluttered.
- **Pattern toggle outside "Search options"**: the order changes what the columns look like, so it sits
  visibly above them; the other shared controls stay folded.
