# Android: Word Bank

The Android client of the word bank ([word_bank.md](./word_bank.md), the canonical spec written with
the backend: data model, text rules, HTTP API, families / rhymes / related algorithms). A per-user
bank of words and short phrases, tagged with the same user-wide tags as snippets, filtered with the
same tag expressions and presets, ranked by rhyme, searched by meaning, and turned into a new
snippet on the canvas it was opened from.

> Offline copy: Adding, renaming, deleting and tagging words work offline on the local copy; rhymes, families and related words say "Needs connection" offline; see [android_offline_sync.md](./android_offline_sync.md).

## Scope

- **Typeless tag evaluation** (`:core:logic`): `TagFilter.matches(type: SnippetType?, names)` treats a
  null type as a subject without a snippet type: every `type:` term is false (so `!type:verse` is
  true). `spec/tag_expression_vectors.json` is version 2 (8 extra eval cases with `"type": null`);
  `TagExpressionVectorsTest` runs them and asserts version 2.
- **Word text rules** (`:core:logic`, `logic/wordbank`): the contract's normalization (NFC, strip,
  whitespace runs → one space, Python `str.isspace`) and validation (1–64 code points, ≤ 4 words,
  only Unicode letters / `'` `’` / `-` / spaces, ≥ 1 letter); the add splitter (commas and newlines
  split, spaces do not) with case-insensitive de-duplication (first spelling wins) and the 200-word
  batch limit.
- **Model / API** (`:core:model`, `:core:data`): `BankWord`, `BankWordId`, the request / response
  DTOs of every `/api/word-bank` route, `UserTag.wordCount` (`word_count`, default 0),
  `WordBankErrorCode` (the six new codes), `ApiError.Validation.index` (`detail.index`) and
  `ApiError.Server.retryAfterSeconds` (`Retry-After`), `WordBankRepository`.
- **Typed errors**: `WordBankError` with one case per new code plus the reused tag codes
  (`TagRejected`) and transport cases; `userMessage()` for each.
- **Word bank screen** (full screen, from the canvas top bar's "Words", back navigation):
  filter bar (same component and behaviour as the canvas one, presets shared, its own remembered
  text under DataStore key `word_bank_filter`), tabs Words / Rhymes / Related, selection bar (Tags,
  Make snippet, Delete), tag picker sheet on the selection, snippet draft sheet.
  - Words: add box (commas / newlines split; tag chips for the new words; "Added N, M already in
    bank" snackbar; local and server errors inline), "N words (M shown)", rows with tag pills,
    checkbox / tap / long-press selection, inline rename (case-only renames allowed), delete with a
    confirm above 5 words.
  - Rhymes: Families (min score slider, default 0.8, query on release; per family "Select all";
    `unmatched` last) and Rhymes with (typed text, else exactly one selected word; min score default
    0.5; score bars). Both send the applied filter as `tag_filter`; matches toggle the selection.
  - Related: search field, similarity, "+" (adds with the add box's tags) or "in bank";
    `related_words_warming` shows "Building related-word index…" and retries after `Retry-After`
    (at most 10 retries); `related_words_unavailable` shows "Related words are unavailable on this
    server".
  - Make snippet: the selected words in list order, one per line, editable, type chips (default
    freestyle); Create posts through the canvas ViewModel at the viewport centre (the FAB's rule),
    selects the new snippet there and closes the sheet; the words stay in the bank.
- **"+ bank"** on every rhyme sheet candidate (disabled, with a long-press tooltip, when the text
  fails the word rules) and **"Add to word bank"** in the editor line field's text selection
  toolbar; both trim, split and post through `WordBankQuickAddViewModel` and show the result as a
  toast.
- **Shared tag controls**: the canvas tag controls were generalized (`TagControlsViewModel<K>`,
  `TagTarget<K>`, `FilterTextStore`, `TagChangeListener`) so the filter bar, presets and picker
  serve both snippets and words with one implementation.

## Non-scope

- The routes, migration and algorithms (backend; [word_bank.md](./word_bank.md)); the web client
  ([web_word_bank.md](./web_word_bank.md)).
- Offline caching of the bank; a failed first load shows Retry.
- Tag pills inside the Rhymes / Related result rows (only the Words list shows pills).
- Server-side filtering: the whole bank is loaded and filtered locally (per the contract).
- Live sync with a canvas open elsewhere; only the canvas below the word bank on the back stack is
  told about tag edits made in the word bank.

## Data and control flow

### Shared tag controls

```
TagControlsViewModel<K : TaggedItemId>(tags, presets, FilterTextStore, TagTarget<K>, TagChangeListener)
  filter bar  -> FilterBarState -> target.setTagFilter(applied); appliedText -> store.save (conflated)
  openPicker(ids: Set<K>) -> PickerSession(ids, links = target.currentLinks(ids))
  row tap -> planToggle(tag, links): TagToggle(targets: List<TaggedItemId>)
          -> target.applyToggle(tag, toggle): String?   (target sends + applies what the server confirmed)
  tag create / edit / delete -> own list, target.onTagEdited / onTagDeleted, filter rename, presets reload
                              -> listener.tagCreated / tagEdited(before, after) / tagDeleted
  tagCreatedElsewhere / tagEditedElsewhere / tagDeletedElsewhere: the same, without notifying anyone

TagsViewModel(canvasId, ...)        = TagControlsViewModel<SnippetId>(..., store.forCanvas(canvasId), SnippetTagTarget(tags, port))
SnippetTagTarget                    per-snippet link / unlink in parallel -> port.onTagLinked / onTagUnlinked
word bank TagControlsViewModel      = TagControlsViewModel<BankWordId>(..., wordBankFilter, WordBankViewModel, ForwardTagChanges(canvasTags))
```

`TaggedItemId` (sealed; `SnippetId`, `BankWordId`) lets the logic-level picker helpers stay
non-generic, so `TagToggle.targetsOf<K>()` narrows the targets back to one id kind.

### Word bank screen

```
Canvas top bar "Words" -> WordBankRoute(canvasId)
WordBankDestination: canvasEntry's CanvasViewModel + TagsViewModel (shared),
                     own WordBankViewModel(repo, canvas) + word bank TagControlsViewModel
WordBankViewModel.init -> GET /api/word-bank -> Remote.Loaded(words, newest first)
visible = words.filter(filter.matches(null, tag names)); selection always ⊆ visible (pruned on every change)

Add box: planAdd(text)  Nothing -> no-op | Invalid(i) / TooMany -> inline error, no request
                        Ready(words) -> POST {words, tag_ids} -> created + existing merged
                        (new first, existing replaced) -> "Added N, M already in bank"
                        422 invalid_word index i -> "The server rejected word i+1 ("…")."
Rename: validateWordText -> unchanged: close | PUT {text} -> replace
        409 word_exists inline | 404 word_not_found: dropped + snackbar
Delete: (UI confirm when > 5) -> POST /delete in chunks of 500 -> remove confirmed chunks
Picker toggle: POST /api/word-bank/tags {word_ids (≤ 500 per call), tag_id, op} -> replace words
               404 word_not_found -> message + reload
Rhymes tab: setTab(RHYMES) / setRhymeView / filter change (on that tab) -> refreshRhymeView
  Families: POST /families {tag_filter = normalized applied filter or omitted, min_score}
  Rhymes with: rhymesWithAnchor(typed, selection) -> Text | Word | null (needsAnchor)
               -> POST /rhymes BankRhymesRequest.of(anchor, tag_filter, min_score)
Related: checkRelatedQuery(q) -> GET /related?q=&limit=30
         503 related_words_warming(Retry-After s) -> Warming(retries); delay(clamp 1–60 s); retry (≤ 10)
         503 related_words_unavailable -> Failed(RelatedUnavailable)
         "+" -> POST {words: [text], tag_ids: add box tags} -> result marked in bank
Make snippet: draftText(words, selection) -> DraftState(text, FREESTYLE)
         Create -> WordBankCanvasPort.createSnippetFromText(text, type)
                -> CanvasViewModel: POST /api/canvases/{id}/snippets at viewportCenter, insert, select,
                   reveal in list mode -> DraftCreateResult (Created | Failed | CanvasNotLoaded)
```

### Quick add (rhyme sheet, editor)

```
RhymePanel -> CandidateRow(onAddToBank, bankProblem = validateWordText(text) problem)
Editor ActiveLineField: Modifier.appendTextContextMenuComponents { selectedText(value) -> "Add to word bank" }
   (LocalAddToWordBank provided by GridEditorRoute.onAddToWordBank)
both -> WordBankQuickAddViewModel(canvas entry).add(raw) -> planAdd -> POST -> messages -> Toast
```

## Files

| File | Role / key exports |
|---|---|
| `spec/tag_expression_vectors.json` | Shared conformance vectors, version 2 (byte-identical across branches) |
| `android/core/logic/.../logic/tags/TagExprEval.kt` | `TagFilter.matches(type: SnippetType?, names)` (null type = typeless) |
| `.../logic/tags/TagPicker.kt` | `pickerRows`, `TagToggle` (`targets: List<TaggedItemId>`), `targetsOf<K>()`, `planToggle` over any `TaggedItemId` |
| `.../logic/wordbank/WordText.kt` | `BankWordText` (validator-only constructor), `WordTextProblem`, `WordTextCheck`, `normalizeWordText`, `validateWordText`, `isValidWordText`, `MAX_WORD_LENGTH`, `MAX_WORDS_PER_ENTRY` |
| `.../logic/wordbank/WordInput.kt` | `splitWordInput`, `AddPlan` (`Nothing`, `Invalid`, `TooMany`, `Ready`), `planAdd`, `MAX_ADD_BATCH` |
| `.../logic/wordbank/WordBankError.kt` | `WordBankError` (one case per code), `toWordBankError`, `isTagNotFound`, `DEFAULT_RELATED_RETRY_AFTER_SECONDS` |
| `.../logic/wordbank/WordBankList.kt` | `TagFilter.matches(BankWord)`, `visibleWords`, `countLabel`, `selectedInOrder`, `draftText`, `pruneSelection`, `needsDeleteConfirm`, `tagFilterParam`, `rhymesWithAnchor`, `withTagEdited` / `withTagDeleted` / `mergedWith` / `withoutIds`, `wordTagLinks`, `addSummary` |
| `.../logic/wordbank/RelatedRetry.kt` | `relatedRetryDelayMs`, `MAX_RELATED_RETRIES`, `RelatedQueryCheck`, `checkRelatedQuery` |
| `android/core/model/.../model/Ids.kt` | `TaggedItemId` (sealed), `SnippetId`, `BankWordId` |
| `.../model/WordBank.kt` | `BankWord`, `WordBankAddRequest` / `Response`, `WordBankRenameRequest`, `WordBankDeleteRequest`, `TagLinkOp`, `WordBankTagRequest` / `Response`, `BankRhymeAnchor`, `BankRhymesRequest` (`of`; exactly one anchor), `BankRhymesResponse`, `BankFamiliesRequest` / `Response`, `BankFamily`, `RelatedWord`, `RelatedWordsResponse`, `WordBankErrorCode` |
| `.../model/Tags.kt` | `UserTag.wordCount` |
| `.../model/error/ApiError.kt` | `Validation.index`, `Server.retryAfterSeconds`, `fromHttp(status, body, retryAfter)`, `retryAfterSeconds`, `indexFromBody` |
| `android/core/data/.../data/ApiClient.kt` | `sendNoContent` (JSON body, ignored response); passes `Retry-After` to `ApiError.fromHttp` |
| `.../data/repository/WordBankRepository.kt` | `WordBankRepository` (`list`, `add`, `rename`, `delete`, `setTag`, `rhymes`, `families`, `related`), `HttpWordBankRepository` |
| `android/app/.../app/tags/TagControlsViewModel.kt` | `TagControlsViewModel<K>` (filter bar, picker, tag edits, presets; `*Elsewhere` hooks) |
| `.../app/tags/TagTarget.kt` | `TagTarget<K>`, `FilterTextStore`, `TagChangeListener` (`None`), `ForwardTagChanges` |
| `.../app/tags/TagsViewModel.kt` | `TagsViewModel` (canvas), `TagFilterStore.forCanvas`, `SnippetTagTarget` |
| `.../app/tags/TagsUiState.kt` | `TagsUiState<K>`, `PickerSession<K>` (`ids`) |
| `.../app/tags/ui/FilterBar.kt`, `ui/TagPickerSheet.kt` | Take any `TagControlsViewModel<*>`; `everythingNote`, `pluralNoun` |
| `.../app/wordbank/WordBankUiState.kt` | `WordBankUiState`, `Remote`, `WordBankTab`, `RhymeView`, `AddBoxState`, `RenameSession`, `FamiliesState`, `RhymesWithState`, `RelatedStatus`, `RelatedState`, `DraftState` |
| `.../app/wordbank/WordBankViewModel.kt` | `WordBankViewModel` (`TagTarget<BankWordId>`), `MAX_IDS_PER_REQUEST` |
| `.../app/wordbank/WordBankCanvasPort.kt` | `WordBankCanvasPort`, `DraftCreateResult` |
| `.../app/wordbank/WordBankQuickAdd.kt` | `WordBankQuickAddViewModel` |
| `.../app/wordbank/WordBankFilterStore.kt` | `DataStoreWordBankFilterStore`, `WORD_BANK_FILTER_KEY` |
| `.../app/wordbank/WordBankMessages.kt` | `WordBankError.userMessage`, `WordTextProblem.userMessage`, `AddPlan.problemMessage`, `RelatedQueryCheck.problemMessage`, `RELATED_WARMING_MESSAGE`, `RELATED_UNAVAILABLE_MESSAGE` |
| `.../app/wordbank/WordBankViewModels.kt` | `wordBankQuickAdd`, `QuickAddToasts`, `wordBankViewModel`, `wordBankTagsViewModel` |
| `.../app/wordbank/ui/WordBankScreen.kt` | `WordBankScreen` (top / selection bars, filter bar, tabs, picker, delete confirm, draft sheet) |
| `.../app/wordbank/ui/WordsTab.kt` | Add box with tag chips, count, word rows, inline rename |
| `.../app/wordbank/ui/RhymesTab.kt` | Families / Rhymes with views, min score sliders, `FailedLine` |
| `.../app/wordbank/ui/RelatedTab.kt` | Related search, warming / unavailable lines, "+" / "in bank" |
| `.../app/canvas/CanvasViewModel.kt` | `WordBankCanvasPort.createSnippetFromText` (viewport centre, select, reveal) |
| `.../app/canvas/CanvasScreen.kt`, `CanvasTopBars.kt` | "Words" action (`onOpenWordBank`), quick add for the rhyme sheet, toasts |
| `.../app/rhyme/ui/RhymePanel.kt`, `CandidateRow.kt` | `onAddToBank`, "+ bank" with disabled tooltip |
| `.../app/editor/GridEditorRoute.kt`, `editor/ui/ActiveLineField.kt`, `editor/ui/SelectionActions.kt` | `onAddToWordBank`, `LocalAddToWordBank`, `selectedText`, the selection toolbar item |
| `.../app/nav/Routes.kt`, `nav/AppNavHost.kt` | `WordBankRoute(canvasId)`, `WordBankDestination`; editor quick add |
| `.../app/di/AppContainer.kt` | `wordBank`, `wordBankFilter` |

Tests: `core/logic/.../wordbank/WordTextTest`, `WordInputTest`, `WordBankErrorTest`,
`WordBankListTest`, `RelatedRetryTest`, `tags/TagExpressionVectorsTest` (v2, null types);
`core/model/.../WordBankDtoTest`; `core/data/.../repository/WordBankRepositoryTest` (MockWebServer,
including `Retry-After`); `app/.../wordbank/WordBankViewModelTest`, `WordBankQuickAddTest`,
`WordBankMessagesTest` (fakes in `WordBankFakes.kt`); `app/.../canvas/CanvasViewModelWordBankTest`;
`app/.../editor/ui/SelectionActionsTest`.

## Invariants

- A `BankWordText` is always normalized and valid: only `validateWordText` builds one, and every
  add sends only `BankWordText` values. The server stays authoritative (its `invalid_word` is shown).
- A word matches a filter exactly like a snippet without a type: tag terms by name
  (case-insensitive), every `type:` term false. Shared vectors v2 pass.
- The selection is always a subset of the visible (filtered) words, and the draft lists it in list
  order.
- A rhymes request always carries exactly one of `word_id` / `text` (private constructor + `of`).
- `tag_filter` is the normalized applied filter or absent; the applied filter is always valid.
- Related retries only follow `related_words_warming`, at most `MAX_RELATED_RETRIES`, each after
  `Retry-After` clamped to 1–60 s; a new search cancels a waiting one.
- Delete and tag requests carry at most 500 ids.
- The word bank filter text lives only under `word_bank_filter`; canvas filters keep `filter:<id>`.
- Tag edits from either tag controls reach the other through `TagChangeListener` (word bank →
  canvas below); the word list shows only server-confirmed tag changes.
- Nothing throws: failures are `WordBankError` / `TagError` state, inline text, snackbars or toasts.

## Contract notes

- "Hyphens" is read as ASCII `-` only (U+2010 and dashes are refused); apostrophes are `'` and `’`.
- "Retry … at most 10 tries" is read as at most 10 retries after the first request (11 requests).
- `Retry-After` is honoured as delta seconds, clamped to 1–60 s; an HTTP-date or missing header
  waits 10 s.
