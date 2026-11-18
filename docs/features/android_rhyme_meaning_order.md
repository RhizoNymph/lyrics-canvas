# Android Rhyme Order: "Rhyme, then meaning"

A toggle on Android's rhyme lists picks between two orders. **Rhyme** is the score order the lists
have always used. **Rhyme, then meaning** keeps fixed score bands in rhyme order and sorts each band
by meaning similarity to the anchor. The server does the ordering, sends each row's `band` and
`similarity`, and says which order it applied (`order_applied`). The app sends the choice, shows a
header where the band changes, and shows a quiet note when the server could not apply meaning order.
The server side is `feat/rhyme-meaning-order-backend`; the web side is `feat/web-rhyme-meaning-order`.

## Scope

- **One choice per device** applies to three lists. The choice is stored in Preferences DataStore
  `rhyme_order`, key `rhyme_order`, as `"rhyme"` or `"meaning"`; the default is rhyme. The lists:
  - the rhyme sheet (single-line suggest),
  - the pattern slot columns,
  - the word bank's "Rhymes with" view.

  Changing it in one list changes it in all three. Each list that is showing is fetched again from
  its first page.
- **The toggle** is a pair of chips, "Order: [Rhyme] [Rhyme, then meaning]". It sits in the rhyme
  sheet's query controls (under Slot), above the pattern slot columns, and under the min-score
  slider of the word bank's Rhymes with view.
- **Requests.** `order: "meaning"` is sent on `POST /api/nlp/rhyme-suggest`,
  `POST /api/nlp/rhyme-pattern/suggest` and `POST /api/word-bank/rhymes`. Rhyme order sends no
  `order` field, because `"rhyme"` is the server's default. Later pages ("More" on the sheet, a
  column's "More", line-match pages) send the same order.
- **Band headers.** When `order_applied` is `"meaning"`, a header (Perfect / Strong / Near / Loose)
  is shown before the first row and wherever the band changes. Appended pages continue the current
  band without a new header. Each pattern column is banded on its own. Line matches are never banded.
- **Meaning label.** Each row shows its similarity as a muted "≈NN%" next to the score. Rows have
  no similarity in rhyme order.
- **Meaning order unavailable.** When meaning was asked but the server answered
  `order_applied: "rhyme"` (the embedding model is unavailable), there are no headers and a muted
  "Meaning order unavailable" line appears under the list's status / controls.

## Non-scope

- The ordering itself: bands, similarity, the anchor embedding and paging are the server's
  ([rhyme_view.md](./rhyme_view.md) on the backend branch).
- A client-side re-sort. Rows are shown in the order the server sent them.
- Word bank families, related words, and the pattern Whole lines tab.
- Offline. These are server-only lists that already say "Needs connection"
  ([android_offline_sync.md](./android_offline_sync.md)).

## Data and control flow

```
DataStore rhyme_order ── RhymeOrderStore.order (Flow) ──> RhymeViewModel.applyOrder   -> params.order
                                                     ├──> PatternViewModel.applyOrder -> params.order
                                                     └──> WordBankViewModel.applyRhymeOrder -> rhymesWith.order
                                                                (refetches a shown Rhymes-with list)
Toggle chip -> vm.setOrder(order) / setRhymesOrder(order): apply at once, then RhymeOrderStore.save(order)

Rhyme sheet
  params.order -> planRhymeQuery: RhymeSuggestRequest(order = order.requestValue)   (null for rhyme)
  the order is part of the query identity -> a new query from offset 0 (debounced like any control)
  answer -> RhymeUiState.ordering = ResultOrdering(asked = request.order ?: RHYME, applied = order_applied)
  RhymeUiState.items = withBandHeaders(rows, ordering.presentation) { rowBand(band, score) }
  RhymePanel: Header -> BandHeader, Row -> CandidateRow (SimilarityLabel); OrderNote under the status line
  loadMore -> same plan with the next offset (same order); rows appended, headers recomputed

Pattern columns
  params.order -> planPatternSuggest(... order = order.requestValue) for First, Slot and LineMatches pages
  plan key changes -> runSuggests re-queries every column from its first page
  answer -> PatternSession.ordering; SlotColumnsView: toggle + OrderNote above, per column withBandHeaders

Word bank Rhymes with
  loadRhymesWith -> BankRhymesRequest.of(anchor, ..., order) ; rhymesWith.askedOrder = order
  RhymesWithState.presentation = orderPresentation(askedOrder, response.order_applied)
  RhymesWithState.items = withBandHeaders(matches, presentation) { rowBand(band, score) }
```

`OrderPresentation` follows from the order asked for and the order applied:

| asked | applied | presentation |
|---|---|---|
| rhyme | rhyme | `PLAIN` |
| meaning | meaning | `BANDED` (headers) |
| meaning | rhyme | `MEANING_UNAVAILABLE` (note, no headers) |

On the rhyme sheet, "Fit rhythm" ([android_score_tools.md](./android_score_tools.md)) adds a third
input: when the server answers `rhythm_applied: true`, `ResultOrdering.presentation` is `BANDED`
whatever the order. The sheet then shows "Meaning order unavailable" from
`ResultOrdering.meaningUnavailable` (asked meaning, applied rhyme), so the note still appears when
the rows are banded by rhythm. The pattern columns and the word bank keep using `OrderNote(presentation)`.

## Files and key exports

| File | Role / key exports |
|---|---|
| `android/core/model/.../model/Rhyme.kt` | `RhymeOrder` (`RHYME`, `MEANING`; `requestValue`), `RhymeBand` (`PERFECT`, `STRONG`, `NEAR`, `LOOSE`). New fields: `RhymeSuggestRequest.order`, `RhymeCandidate.band` / `similarity`, `RhymeSuggestResponse.orderApplied`. |
| `android/core/model/.../model/RhymePattern.kt` | `PatternSuggestRequest.order`, `PatternCandidate.band` / `similarity`, `PatternSuggestResponse.orderApplied` |
| `android/core/model/.../model/WordBank.kt` | `BankRhymesRequest.order` (`of(..., order)`), `BankRhymeMatch.band` / `similarity`, `BankRhymesResponse.orderApplied` |
| `android/core/logic/.../logic/rhyme/RhymeOrder.kt` | `PERFECT_MIN_SCORE` / `STRONG_MIN_SCORE` / `NEAR_MIN_SCORE` (0.95 / 0.85 / 0.75, the one Android copy), `bandOf`, `rowBand`, `RhymeBand.label`, `RhymeOrder.label`, `MEANING_UNAVAILABLE_NOTE`, `OrderPresentation`, `orderPresentation`, `ResultOrdering`, `BandedItem` (`Header`, `Row`), `withBandHeaders`, `similarityPercent` |
| `android/core/logic/.../logic/rhyme/RhymeQuery.kt` | `RhymeQueryParams.order`; `planRhymeQuery` sends it |
| `android/core/logic/.../logic/rhyme/pattern/PatternQuery.kt` | `PatternParams.order`; `planPatternSuggest` sends it on every page |
| `android/app/.../app/rhyme/order/RhymeOrderStore.kt` | `RhymeOrderStore` (`order: Flow`, `save`), `DataStoreRhymeOrderStore`, `InMemoryRhymeOrderStore` |
| `android/app/.../app/rhyme/order/RhymeOrderUi.kt` | `RhymeOrderToggle`, `BandHeader`, `SimilarityLabel`, `OrderNote`, `bandedKey` (lazy-list keys for headers and rows) |
| `android/app/.../app/rhyme/RhymeViewModel.kt`, `RhymeUiState.kt` | `orders` parameter, `setOrder`; `RhymeUiState.ordering`, `presentation`, `items` |
| `android/app/.../app/rhyme/ui/RhymePanel.kt`, `QueryControls.kt`, `CandidateRow.kt` | Banded rows, toggle, note, similarity |
| `android/app/.../app/rhyme/pattern/PatternViewModel.kt`, `PatternUiState.kt` | `orders` parameter, `setOrder`; `PatternSession.ordering` |
| `android/app/.../app/rhyme/pattern/ui/SlotColumnsView.kt`, `PatternPanel.kt` | Toggle and note above the columns, per-column band headers, similarity |
| `android/app/.../app/wordbank/WordBankViewModel.kt`, `WordBankUiState.kt`, `WordBankViewModels.kt` | `orders` parameter, `setRhymesOrder`; `RhymesWithState.order`, `askedOrder`, `presentation`, `items` |
| `android/app/.../app/wordbank/ui/RhymesTab.kt` | Toggle, note, banded matches, similarity |
| `android/app/.../app/di/AppContainer.kt`, `canvas/CanvasScreen.kt` | `rhymeOrders` (DataStore `rhyme_order`) passed to the three ViewModels |

Tests:

- `core/model`: `RhymeOrderDtoTest` (request fields, band / similarity / `order_applied` decoding).
- `core/logic`: `RhymeOrderTest` (band edges, labels, presentation, headers, appended pages,
  percent), `RhymeOrderQueryTest` and `pattern/PatternOrderQueryTest` (the field on every page).
- `app`: `RhymeViewModelOrderTest`, `PatternViewModelOrderTest` and `WordBankRhymeOrderTest`
  (refetch from the first page, headers, the unavailable note, the device choice, paging keeps
  the order).

## Invariants and constraints

- **Rows keep the server's order.** The app never re-sorts candidates, so headers follow the
  server's banding.
- **Headers only when meaning was applied.** A header appears only when `order_applied` is
  `"meaning"`, and only where the band changes between consecutive rows. A band never gets two
  headers in a row, and lazy-list keys stay unique (a header is keyed by the row it heads).
- **One order per query.** Every page of a list's query uses the order its first page used.
  Changing the order starts a new query from offset 0.
- **Rhyme order requests are unchanged.** They have no `order` field, so they match what was
  sent before this feature.
- **The band edges exist once in the Android code** (`RhymeOrder.kt`). They are used only for
  labels and for a payload without `band`.
- **The choice is per device**, shared by the three lists, and never sent anywhere except as the
  request field.

## Decisions

1. **`band` and `order_applied` decode with fallbacks.** `band` is nullable, and a row without one
   is banded from its score with the shared edges. `order_applied` defaults to `"rhyme"`. The
   server always sends both. This keeps payloads and test fixtures from before the feature
   decodable, as with `version = 1` in the offline sync DTOs.
2. **Rhyme order is sent by omission**, not as `order: "rhyme"`. The server's default is rhyme, and
   this keeps the existing request encoding.
3. **One shared choice for the three lists**, as on web (`lyrics-canvas.rhymeOrder`). The contract
   says "remembered per device" without splitting it per list.
4. **The similarity shows as "≈NN%"** (rounded, clamped to 0..100), muted. The contract leaves
   bar or percent to the client; this matches the web.
5. **The Rhymes with toggle refetches only a list that was already asked for.** Before the first
   "Find" there is nothing to refetch; the next query uses the new order.
6. **Rhythm fit (rhyme sheet only) bands rows too** (round-4 contract section 6). See
   [android_score_tools.md](./android_score_tools.md).
