# Web Word Bank

The web client of the word bank: a per-user bank of words and short phrases, tagged with the existing
user-wide tags, filtered with the same tag expressions, grouped into rhyme families, ranked against one
word, searched by meaning, and turned into a new snippet. The data model, endpoints and algorithms are
specified in [word_bank.md](./word_bank.md) (canonical, written with the backend). The client side of
tags and expressions is in [web_tag_expressions.md](./web_tag_expressions.md).

## Scope

- Typed wire shapes (`types/wordBank.ts`), `Tag.word_count`, and a typed API (`api/wordBank.ts`) that
  rejects only with `WordBankApiError`.
- `WordBankApiError` for every new server code (`invalid_word` with the batch `index`, `word_exists`,
  `word_not_found`, `too_many_words`, `related_words_warming` with `Retry-After`,
  `related_words_unavailable`) plus the reused tag codes and fallbacks.
- A client mirror of the word text rules and the add box splitter (commas and newlines).
- `wordBankStore`: load the whole bank, filter locally with the shared evaluator (typeless subject),
  add batches, rename, delete, link/unlink through `POST /api/word-bank/tags`, the selection, tag sync
  with `tagStore`, and "Make snippet".
- `wordBankQueryStore`: families, rhymes with, related search with the warming retry.
- The `Words` toolbar toggle and Ctrl/Cmd+Shift+W (plus Alt+Shift+W) for a right sidebar that is
  mutually exclusive with the rhyme sidebar.
- The panel: shared filter bar with presets, add box with tag chips, list with multi-select, inline
  rename, delete (confirmed above 5), tag picker on the selection, Rhymes tab (Families, Rhymes with),
  Related tab, draft modal.
- "+ bank" on every rhyme sidebar candidate; "Add to word bank" in the inline editor's context menu on a
  text selection and Ctrl/Cmd+Shift+B; a result toast.
- The shared filter bar (`TagFilterBar`) and tag checklist (`TagChecklist`), generalized from the canvas
  filter bar and the snippet tag picker.
- `spec/tag_expression_vectors.json` v2: `evaluate` accepts `type: null`.

## Non-scope

- The backend (migration 006, endpoints, scoring, clustering, the embedding index) and Android.
- Server-side filtering of the bank (the contract has none; the client loads everything).
- Undo of word bank changes. Bank writes are server state outside the canvas undo history. Only the
  snippet created by "Make snippet" goes through the canvas undo history.
- Following a tag rename into the word bank filter of other browsers (each keeps its own text).
- Word bank state across canvases beyond what the server stores. The filter is user-wide per browser.

## Data and control flow

### Opening and loading

```
Toolbar "Words" / Ctrl+Cmd+Shift+W / Alt+Shift+W (CanvasPage keydown, works while typing)
  -> uiStore.toggleWordBank(): rightPanel 'none' | 'rhymes' | 'words'   (one right panel at a time)
CanvasPage renders <WordBankPanel/> while rightPanel === 'words'
WordBankPanel mount -> wordBankStore.open()
  first call only: loadFilterAt(localStorage, 'lyrics-canvas.wordBankFilter') -> filterReducer restore
  load(): GET /api/word-bank -> words (newest first), selection pruned, revision + 1
```

### Filter

```
TagFilterBar (compact) value = wordBankStore.filter
  dispatchFilter(action) -> filterReducer (same state machine as the canvas filter)
    -> selection pruned to the words still shown
    -> saveFilterAt(localStorage, 'lyrics-canvas.wordBankFilter', {text, appliedText})  (try/catch inside)
visible = visibleWords(words, filter.applied)
  -> evaluate(applied, { type: null, tagNames: word.tags.map(name) })    every type: term is false
count line: "N words (M shown)"
```

The rhyme views send `tag_filter = format(filter.applied)` (the last valid filter, normalized), omitted
when there is no filter.

### Adding

```
Add box (textarea; Enter submits, Shift+Enter is a new line)
  live: addInputProblem(text) -> the first entry that would be rejected, shown inline, Add disabled
  submit -> wordBankStore.addFromText(text, 'box', addTagIds)
    splitAddInput: split on , \r \n, trim, drop empty
    addWords: checkBatch (1..200, every entry normalized and valid; first bad index wins, no request)
      -> POST /api/word-bank {words, tag_ids}
      -> mergeWords(created + existing), tagStore.adjustWordCount per tag (tagLinkDelta)
    ok -> notice "Added N, M already in bank" (toast)   error -> inline in the box
Add box chips: TagChecklist over one pseudo-target whose tags are addTagIds; toggles edit addTagIds.
Rhyme candidate "+ bank" (AddToBankButton): checkWordText(candidate.text) fails -> disabled + tooltip;
  else addFromText(text, 'rhyme') -> toast (success or failure).
Inline editor, active row input with a non-empty selection:
  contextmenu -> EditorBankMenu "Add to word bank" (data-keep-editor-open) -> addFromText(sel, 'editor')
  Ctrl/Cmd+Shift+B -> addFromText(sel, 'editor')
  no selection -> the browser's own context menu; the shortcut does nothing
Related "+" -> wordBankQueryStore.addRelatedWord(text): addFromText(text, 'related', addTagIds),
  then the result is shown as "in bank" (bank_word_id from the created or existing word).
```

### List, selection, rename, delete, tags

```
WordList rows (role=option, checkbox, text, tag pills, ✎)
  click -> select({type:'click'})   Ctrl/Cmd-click or checkbox -> 'toggle'
  Shift-click -> {type:'range', order: visible ids}  (from the anchor, in list order)
  select() always prunes to visible words: the selection is a subset of what is shown
Rename (double-click or ✎): checkWordText live; Enter -> renameWord(id, text)
  unchanged normalized text -> no request; PUT /api/word-bank/{id} {text}
  409 word_exists shown inline; 404 word_not_found drops the word locally
Delete: >5 selected -> SelectionBar confirm step ("Delete N words? This cannot be undone.")
  POST /api/word-bank/delete {ids} -> remove locally, word_count - n per tag
Tags (popover TagChecklist over the selected words, counts = word_count):
  row click -> planTagToggle(tagId, selectedWords): all -> unlink all; else link where missing
  -> setTag(ids, tagId, op): POST /api/word-bank/tags -> mergeWords(updated), word_count delta
     404 word_not_found: nothing changed (all or nothing); tag_not_found -> tagStore.forgetTag
  create / rename / recolor / delete tags: tagStore, as for snippets
```

### Tag sync (tagStore -> wordBankStore)

```
updateTag ok      -> wordBankStore.patchTag(embedTag(tag))  (word tags re-sorted by lower(name))
  name changed    -> wordBankStore.dispatchFilter({type:'renameTag', from, to})
forgetTag(id)     (deleteTag, update/link tag_not_found)
                  -> snippets: removeEmbeddedTag; words: removeTag (also drops it from addTagIds)
adjustWordCount(id, delta)  after word bank adds, links, unlinks, deletes
```

### Rhymes tab

```
Families (default) - FamiliesPane
  debounced (250 ms) on revision | filter.appliedText | familyMinScore (slider, default 0.8)
  -> loadFamilies: POST /api/word-bank/families {min_score, tag_filter?}  (stale responses dropped)
  families in server order, each with "Select all" (select add), unmatched last
  422 too_many_words -> message (filter the bank first) + Retry
Rhymes with - RhymesWithPane
  anchor = resolveRhymeAnchor(typedText, selection, pinned):
    typed text -> {text}; exactly one selected word -> {word_id} (pinned);
    the pinned word while it stays selected (so selecting matches keeps the anchor); else none
  debounced on anchor | revision | appliedText | rhymeMinScore (default 0.5)
  -> loadRhymes: POST /api/word-bank/rhymes {word_id | text, min_score, tag_filter?}
  matches with score bars and depth; click toggles selection; "Select all"
```

### Related tab

```
searchRelated(q): checkRelatedQuery (1..64 code points after a strip) else 'invalid' / idle
  searchRelated loop (lib/wordBank/queries.ts):
    GET /api/word-bank/related?q&limit=30
    503 related_words_warming -> state 'warming' {attempt, retryAfterSeconds}
       "Building related-word index… Retrying in S s (n of 10)"; sleep Retry-After; at most 10 requests
    503 related_words_unavailable -> "Related words are unavailable on this server" (no retry)
  a new search or leaving the tab cancels the pending sleep (sequence number + wake function)
```

### Make snippet

```
SelectionBar "Make snippet" (>=1 selected)
  -> draftFromWords(selectedInOrder(words, selection))  one word per line, list order, type freestyle
DraftModal (focus trap, Esc / backdrop / Cancel discard)
  editable textarea, type select
  Create -> wordBankStore.makeSnippet(draft)
    newSnippetPosition(viewport, canvasViewSize)    viewport centre (origin if never measured), as voice capture
    canvasStore.addSnippetFromText(text, x, y, undefined, type) -> POST /api/canvases/{id}/snippets
    select the new snippet; close. The words stay in the bank.
```

## Wire shapes the client assumes

- `BankWord {id, text, tags: SnippetTag[] (sorted by lower(name)), created_at}`; `Tag.word_count`.
- `POST /api/word-bank {words, tag_ids}` -> `{created, existing}`; `PUT /api/word-bank/{id} {text}`;
  `POST /api/word-bank/delete {ids}` -> 204; `POST /api/word-bank/tags {word_ids, tag_id, op}` ->
  `{words}`.
- `POST /api/word-bank/rhymes` -> `{anchor {text, rhyme_key, syllable_count}, matches [{word, score,
  match_depth}]}`; `POST /api/word-bank/families` -> `{families [{label, rhyme_key, words, cohesion}],
  unmatched}`; `GET /api/word-bank/related?q&limit` -> `{query, results [{text, similarity,
  bank_word_id}]}`.
- Errors `{detail: {error, message, index?, expression_error?}}`; `Retry-After` header (whole seconds,
  clamped 1..60, default 10).

## Files

| File | Role | Key exports |
|------|------|-------------|
| `spec/tag_expression_vectors.json` | Shared vectors v2 (byte-identical in all three branches) | |
| `frontend/src/lib/tagExpr/evaluate.ts` | `EvalSubject.type: SnippetType \| null` | `evaluate`, `EvalSubject` |
| `frontend/src/lib/tagExpr/evaluate.test.ts` | Typeless subject cases | |
| `frontend/src/lib/tagExpr/vectors.test.ts` | Version 2, null-type eval cases run | |
| `frontend/src/types/wordBank.ts` | Wire shapes | `BankWord`, `BankWordCreateBody/Response`, `WordTagOp`, `WordTagsBody/Response`, `RhymeAnchorInput`, `BankRhymesBody/Response`, `BankRhymeMatch`, `BankFamiliesBody/Response`, `RhymeFamily`, `RelatedWord`, `RelatedWordsResponse` |
| `frontend/src/types/index.ts` | `Tag.word_count` | |
| `frontend/src/api/wordBank.ts` | Endpoints over an injected axios instance | `createWordBankApi`, `WordBankApi`, `RELATED_DEFAULT_LIMIT` |
| `frontend/src/api/client.ts` | Binds it | `wordBankApi` |
| `frontend/src/lib/wordBank/errors.ts` | Typed errors, `detail` union per code | `WordBankApiError`, `WORD_BANK_SERVER_ERROR_CODES`, `WordBankErrorKind`, `WordBankErrorDetail`, `WordBankOperation`, `WordBankResult`, `toWordBankApiError`, `describeWordBankError`, `parseRetryAfter` |
| `frontend/src/lib/wordBank/text.ts` | Word rules, splitter, batch check | `normalizeWordText`, `checkWordText`, `isValidWordText`, `describeWordProblem`, `splitAddInput`, `checkBatch`, `addInputProblem`, `needsDeleteConfirm`, `MAX_WORD_LENGTH`, `MAX_WORDS_PER_ENTRY`, `MAX_BATCH_WORDS` |
| `frontend/src/lib/wordBank/words.ts` | Immutable bank updates, visibility | `byNewest`, `mergeWords`, `removeWords`, `patchWordTag`, `removeWordTag`, `isWordVisible`, `visibleWords`, `selectedInOrder`, `tagLinkDelta` |
| `frontend/src/lib/wordBank/selection.ts` | Selection reducer | `selectionReducer`, `WordSelection`, `SelectionAction`, `EMPTY_SELECTION`, `clickKind` |
| `frontend/src/lib/wordBank/queries.ts` | Request bodies, related retry, rhyme anchor | `tagFilterParam`, `familiesBody`, `rhymesBody`, `checkRelatedQuery`, `searchRelated`, `resolveRhymeAnchor`, `DEFAULT_FAMILY_MIN_SCORE`, `DEFAULT_RHYME_MIN_SCORE`, `MAX_RELATED_TRIES` |
| `frontend/src/lib/wordBank/draft.ts` | Draft, add summary, field selection | `draftFromWords`, `SnippetDraft`, `DEFAULT_DRAFT_TYPE`, `isDraftCreatable`, `addSummary`, `selectedFieldText` |
| `frontend/src/lib/wordBank/keys.ts` | Shortcuts | `isWordBankShortcut`, `isAddSelectionShortcut`, labels |
| `frontend/src/lib/wordBank/fixtures.ts` | Test fixtures | `bw`, `wtag` |
| `frontend/src/lib/wordBank/index.ts` | Barrel | |
| `frontend/src/lib/tagFilter/persistence.ts` | Filter storage by key | `WORD_BANK_FILTER_KEY`, `loadFilterAt`, `saveFilterAt` (canvas `loadFilter`/`saveFilter` delegate) |
| `frontend/src/lib/tags/picker.ts` | Toggle plan for any taggable | `Taggable`, `TagToggle`, `planTagToggle` (`planToggle` wraps it) |
| `frontend/src/lib/voice/target.ts` | Shared new-snippet position | `newSnippetPosition` |
| `frontend/src/stores/wordBankStore.ts` | Bank, filter, selection, writes, notices, tag sync | `useWordBankStore`, `selectVisibleWords`, `WordBankNotice`, `AddSource` |
| `frontend/src/stores/wordBankQueryStore.ts` | Families, rhymes with, related | `useWordBankQueryStore`, `Remote`, `RelatedState`, `markRelatedInBank` |
| `frontend/src/stores/tagStore.ts` | `forgetTag`, `adjustWordCount`, word bank sync on update/delete | |
| `frontend/src/stores/uiStore.ts` | `rightPanel`, `toggleWordBank` | `RightPanel` |
| `frontend/src/stores/canvasStore.ts` | `addSnippetFromText(..., snippet_type)` | |
| `frontend/src/components/tags/TagFilterBar.tsx` | Shared expression filter bar (canvas + word bank) | default |
| `frontend/src/components/tags/TagChecklist.tsx` | Shared tag checklist over any targets | default, `ToggleOutcome`, `fromTagResult` |
| `frontend/src/components/tags/TagPicker.tsx` | Snippet wrapper of TagChecklist | default |
| `frontend/src/components/toolbar/TagExpressionBar.tsx` | Canvas wrapper of TagFilterBar | default |
| `frontend/src/components/toolbar/Toolbar.tsx` | `Words` toggle | |
| `frontend/src/components/pages/CanvasPage.tsx` | Mounts panel and toast, Ctrl/Cmd+Shift+W | |
| `frontend/src/components/wordBank/WordBankPanel.tsx` | Panel container | default, `countLabel`, `WordBankTab` |
| `frontend/src/components/wordBank/AddWordsBox.tsx` | Add box view | default |
| `frontend/src/components/wordBank/WordList.tsx`, `WordRow.tsx` | List and rows, inline rename | default |
| `frontend/src/components/wordBank/SelectionBar.tsx` | Tags / Make snippet / Delete / Clear | default, `DeleteStep` |
| `frontend/src/components/wordBank/Popover.tsx` | Button + popover | default |
| `frontend/src/components/wordBank/RhymesTab.tsx` | Families / Rhymes with containers | default, `RhymeView` |
| `frontend/src/components/wordBank/FamiliesView.tsx`, `RhymesWithView.tsx` | Rhyme views | default, `WordChips` |
| `frontend/src/components/wordBank/RelatedTab.tsx`, `RelatedView.tsx` | Related search | default, `WARMING_MESSAGE`, `UNAVAILABLE_MESSAGE` |
| `frontend/src/components/wordBank/DraftModal.tsx`, `DraftView.tsx` | Draft editor | default |
| `frontend/src/components/wordBank/WordBankToast.tsx` | Notice toast | default, `WordBankToastView`, `NOTICE_MS` |
| `frontend/src/components/wordBank/AddToBankButton.tsx` | "+ bank" on rhyme candidates | default |
| `frontend/src/components/wordBank/EditorBankMenu.tsx` | Editor context menu | default |
| `frontend/src/components/wordBank/ScoreBar.tsx`, `MinScoreSlider.tsx`, `styles.ts` | Widgets, styles | |
| `frontend/src/components/rhyme/CandidateRow.tsx` | Hosts "+ bank" | |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | Context menu and Ctrl/Cmd+Shift+B on the active row | |

Tests: `lib/wordBank/*.test.ts`, `api/wordBank.test.ts`, `stores/wordBankStore.test.ts`,
`stores/wordBankQueryStore.test.ts`, `stores/uiStore.rightPanel.test.ts`, `lib/tags/picker.taggable.test.ts`,
`components/wordBank/views.test.tsx`, `components/tags/shared.test.tsx` (static rendering; the repo has
no DOM test environment).

## Invariants and constraints

- `selection.ids` is always a subset of the words the filter shows (pruned after every load, write,
  filter change and select).
- `filter.applied` only holds a parse of the last valid text; the rhyme views always send that one,
  normalized, or nothing.
- No add request is sent with an entry the local rules reject; the batch error carries the first bad
  index, as the server's does. The server stays authoritative (its 422 is shown the same way).
- Word tags stay sorted by lower(name) after a rename; `words` stays newest first (`created_at desc, id`).
- Word pills follow every successful link, unlink, rename, recolor and delete of a tag; `word_count`
  follows bank writes (never below 0).
- Store actions return `WordBankResult` and never throw. Every server code maps to its own kind; there
  is no untyped catch-all.
- The related search sends at most 10 requests per query, waits `Retry-After` between them, and stops
  as soon as it is superseded or the tab closes.
- At most one right panel (rhymes or words) is open; `rightPanel` cannot represent both.
- The filter text lives only at `lyrics-canvas.wordBankFilter` (user-wide, per browser); storage
  failures are logged and never break filtering.

## Deviations from the contract

- **Shortcut.** Chrome and Firefox reserve Ctrl/Cmd+Shift+W (close window) and do not deliver it to
  pages in a normal tab. The handler accepts it (it works where the browser passes it through, e.g. an
  installed app window), and Alt+Shift+W is bound as well and shown in the tooltip.
- **Rhymes-with anchor.** "Exactly one word selected" is kept while the user selects matches into the
  selection: the anchor stays pinned to that word as long as it is still selected.
- **Hyphens.** "Hyphens" is read as ASCII `-` only (U+2010 and other dashes are rejected), reading the
  contract literally.
