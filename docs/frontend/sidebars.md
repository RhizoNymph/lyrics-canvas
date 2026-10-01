# Sidebars

Right-side and bottom panel components for NLP-powered analysis.

## RhymeSidebar

**File:** `src/components/sidebar/RhymeSidebar.tsx` (pieces in `src/components/rhyme/`)

Right-side panel for the engine-backed rhyme search (`POST /api/nlp/rhyme-suggest`). The selected snippet(s)
and/or a typed phrase are the anchors; ranked, slot-checked candidates are listed in the panel and reach the
canvas only when a row is materialized. Full description in [rhyme-view.md](./rhyme-view.md).

### State

All state lives in `rhymeStore` (session, params, results, dismissed, exclusions, materialize counts,
loading/error). The sidebar owns only the expanded row. `uiStore.rhymeSidebarOpen` controls mounting;
`CanvasPage` renders the sidebar while it is true and the sidebar calls `rhymeStore.enter()` on mount and
`exit()` on unmount.

### Interactions

- Type a word or phrase (Enter queries now, typing re-queries after 150 ms); select snippets on the canvas
- Remove a selected snippet from the anchors with its chip's ×, restore with +
- Click a candidate's text to search for that phrase instead (chain search)
- Click a row to see the aligned tail against the anchor
- Materialize creates a snippet on the canvas; Dismiss hides the row for the session
- More loads the next 40

### Layout

Width: 380px fixed, full height of the canvas area. Header, query controls (search, chips, k, min score,
slot, sources, rare words), status line with More, scrollable result rows.

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
