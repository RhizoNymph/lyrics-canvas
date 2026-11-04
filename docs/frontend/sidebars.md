# Sidebars

Right-side and bottom panel components for NLP-powered analysis.

## RhymeSidebar

**File:** `src/components/sidebar/RhymeSidebar.tsx` (pieces in `src/components/rhyme/`)

Right-side panel for the engine-backed rhyme search (`POST /api/nlp/rhyme-suggest`). The selected snippet(s)
and/or a typed phrase are the anchors; ranked, slot-checked candidates are listed in the panel and reach the
canvas only when a row is materialized. Full description in [rhyme-view.md](./rhyme-view.md).

### State

All state lives in `rhymeStore` (session, params, results, dismissed, exclusions, materialize counts,
loading/error). The sidebar owns only the expanded row. `uiStore.rightPanel === 'rhymes'` controls
mounting (the word bank shares the slot; only one is open); `CanvasPage` renders the sidebar while it holds and the sidebar calls `rhymeStore.enter()` on mount and
`exit()` on unmount.

### Interactions

- Type a word or phrase (Enter queries now, typing re-queries after 150 ms); select snippets on the canvas
- Remove a selected snippet from the anchors with its chip's ×, restore with +
- Click a candidate's text to search for that phrase instead (chain search)
- Click a row to see the aligned tail against the anchor
- Materialize creates a snippet on the canvas; Dismiss hides the row for the session
- "+ bank" adds the candidate's text to the word bank (toast with the result); disabled with the reason as a
  tooltip when the text is not a valid bank entry (digits, more than 4 words, over 64 characters)
- More loads the next 40
- "Only my lines matching" (`NotesTagFilter`): a tag expression (same input, autocomplete and presets as the
  canvas filter bar, plus "Use canvas filter") restricting the notes source; sent as `tag_filter`, never
  sent while invalid (the list shows "Not searched: ..." instead). See
  [web_tag_expressions.md](../features/web_tag_expressions.md)

### Layout

Width: 380px fixed, full height of the canvas area. Header, query controls (search, chips, k, min score,
slot, sources, rare words, notes tag filter), status line with More, scrollable result rows.

---

## WordBankPanel

**File:** `src/components/wordBank/WordBankPanel.tsx` (pieces in `src/components/wordBank/`)

Right-side panel (380px) for the user's word bank, mounted while `uiStore.rightPanel === 'words'`. State
lives in `wordBankStore` (words, filter, selection, add tags, notices) and `wordBankQueryStore` (rhyme
views, related search); the panel owns the add box text, the renaming row, the delete confirmation and
the open draft. Full description in [web_word_bank.md](../features/web_word_bank.md).

### Layout

Header (× closes), compact `TagFilterBar` ("N words (M shown)", presets), selection bar (Tags popover
with the shared `TagChecklist`, Make snippet, Delete with a confirm step above 5, Clear), tabs:

- **Words**: add box (commas and newlines split; tag chips with a `+ tag` checklist), the filtered list
  (click, Ctrl/Cmd-click, Shift-click, checkboxes; double-click or ✎ renames inline).
- **Rhymes**: Families (min score slider, default 0.8; Select all per family; unmatched last) or Rhymes
  with (the one selected word or typed text; min score default 0.5; score bars; Select all).
- **Related**: search by meaning; "+" adds with the add box's tags, "in bank" otherwise; warming retries
  (at most 10, after Retry-After) and the unavailable message.

Make snippet opens `DraftModal` (selected words in list order, one per line, editable, type select
defaulting to freestyle); Create places the snippet at the viewport centre. `WordBankToast` (mounted by
`CanvasPage`) shows add results from every source, including the editor's "Add to word bank".

---

## SimilarityPanel

**File:** `src/components/sidebar/SimilarityPanel.tsx`

Bottom panel showing snippets semantically similar to a target snippet using TF-IDF cosine similarity.

### State

| State | Type | Source | Purpose |
|-------|------|--------|---------|
| `results` | SimilarityResult[] | API | Similar snippets |
| `loading` | boolean | Local | API call state |
| `similarityPanelOpen` | boolean | uiStore | Visibility toggle |
| `similarityTargetId` | string \| null | uiStore | Target snippet ID |

### Interactions

- Setting `similarityTargetId` in uiStore auto-opens the panel and triggers the API call
- Click a result to navigate: pans viewport to snippet and selects it

### Navigation Logic

```typescript
const s = snippets.get(snippetId);
setViewport({ x: -s.position_x + 400, y: -s.position_y + 300 });
selectSnippet(snippetId);
```

### API Call

```
GET /api/snippets/{snippetId}/similar?top_k=10
Response: SimilarityResult[]
```

### Result Display

Each result shows:
- Snippet type label (uppercase, small font)
- Similarity score as percentage (e.g., "94% match")
- Raw text preview (truncated to 80 chars)
- Left border colored by snippet type via `SNIPPET_TYPE_COLORS`

### Layout

Height: 200px fixed. Scrollable vertical list.

---

## Related Types

```typescript
interface RhymeResult {
  word: string;
  rhyme_type: string;          // 'perfect' or 'near'
  source: string;              // 'dictionary' or 'snippet'
  snippet_id?: string;
  snippet_preview?: string;
}

interface SimilarityResult {
  snippet_id: string;
  raw_text: string;
  score: number;               // 0-1 cosine similarity
  snippet_type: string;
}
```
