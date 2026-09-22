# Sidebars

Right-side and bottom panel components for NLP-powered analysis.

## RhymeSidebar

**File:** `src/components/sidebar/RhymeSidebar.tsx`

Right-side panel for rhyme lookup. Shows dictionary rhymes (perfect and near) plus rhyme matches found within the user's canvas snippets.

### State

| State | Type | Source | Purpose |
|-------|------|--------|---------|
| `input` | string | Local | Current search term |
| `dictRhymes` | `{ perfect: string[], near: string[] }` | API | Dictionary results |
| `snippetRhymes` | RhymeResult[] | API | In-snippet matches |
| `loading` | boolean | Local | API call state |
| `rhymeSidebarOpen` | boolean | uiStore | Visibility toggle |
| `rhymeWord` | string | uiStore | Synced search word |

### Interactions

- Type a word and press Enter or click "Find" to search
- Click any rhyme word to search for that word instead
- Setting `rhymeWord` in uiStore auto-opens the sidebar

### API Call

```
GET /api/nlp/rhymes?word=<word>
Response: {
  dictionary: { perfect: string[], near: string[] },
  snippets: RhymeResult[]
}
```

### Layout

Width: 280px fixed. Three sections rendered conditionally:

1. **Perfect Rhymes**: Up to 30 clickable tags
2. **Near Rhymes**: Up to 20 clickable tags (slightly transparent at 0.8 opacity)
3. **In Your Snippets**: Rhyme matches from user's lyrics with snippet preview text

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
