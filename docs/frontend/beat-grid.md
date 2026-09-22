# Beat Grid

Syllable-aligned grid display and editing system for lyrics. Each line of text is broken into syllable cells with stress markers and optional rests.

## Data Structure

```typescript
interface GridCell {
  syllable?: string;       // Syllable text (e.g., "ra", "dio")
  stress?: number;         // 0=unstressed, 1=primary, 2=secondary
  word_index?: number;     // Which word in the line
  rest?: boolean;          // True = silence beat
}

interface GridLine {
  cells: GridCell[];
  syllable_count: number;
  raw_text: string;
}

interface GridData {
  lines: GridLine[];
}
```

## BeatGridDisplay

**File:** `src/components/beatgrid/BeatGridDisplay.tsx`

Read-only HTML table rendering of a beat grid. Purely presentational with no local state.

### Props

```typescript
interface Props {
  gridData: GridData;
  zoom?: number;           // Scale factor (default: 1)
}
```

### Rendering

- HTML `<table>` with `<thead>` (column numbers) and `<tbody>` (syllable cells)
- Column headers: numbered 1, 2, 3... plus "#" for syllable count
- Monospace font, alternating row backgrounds
- Cell min-width: `50 * zoom`, font-size: `13 * zoom`
- Horizontal scroll overflow for wide grids

### Cell Styling

| Condition | Text | Color | Weight |
|-----------|------|-------|--------|
| Stressed (stress > 0) | syllable | #fff | Bold |
| Unstressed (stress = 0) | syllable | #bbb | Normal |
| Rest (rest = true) | "·" | #444 | Normal |

---

## Inline Grid Editing

**File:** `src/components/canvas/InlineGridEditor.tsx`

On-canvas inline editor that replaces the Pixi snippet card during editing. Rather than a separate modal with a textarea, the user types directly into the beat grid — syllable cells expand in real-time as text is entered.

See [Canvas Rendering — InlineGridEditor](./canvas-rendering.md#inlinegrideditor) for full component documentation.

### Syllabification Flow

```
User types text in active row
  -> Local syllable estimate updates grid cells instantly
     (vowel-group heuristic: split words into estimated syllable chunks)
  -> After 300ms debounce:
     -> POST /api/nlp/syllabify { text (all lines joined) }
       -> Backend returns GridData with accurate syllables + stress
       -> Active row switches to API data when raw_text matches
       -> Inactive rows update immediately
```

### Fallback Syllabification

If the API call fails, `localSyllabifyLine()` is used for all rows:

```typescript
// For each word: count vowel groups, split into that many character chunks
estimateSyllableCount("hullabaloo")  // -> 4 (vowel groups: u, a, a, oo)
splitWord("hullabaloo")              // -> ["hul", "lab", "alo", "o"]
// API would return accurate: ["hul", "la", "ba", "loo"]
```

### API Call

```
POST /api/nlp/syllabify
Body: { text: string }
Response: GridData
```

### UI Elements

1. Color-coded header bar with snippet type `<select>` dropdown
2. Per-row beat grid cells (active rows show live syllable estimate, inactive rows show API data)
3. Compact text input below active row for free-form typing
4. Per-row syllable count badge (highlighted on active row)
5. Extra empty cell on active row (+1 for next syllable)

## BeatGridEditor (standalone)

**File:** `src/components/beatgrid/BeatGridEditor.tsx`

Standalone full editor for creating and modifying beat grids outside the canvas context. Includes type selection, grid mode, syllabification, textarea, and live preview.

### Props

```typescript
interface Props {
  initialText: string;
  initialGridData: GridData | null;
  gridMode: GridMode;              // 'auto' | 'fixed'
  beatsPerLine: number | null;
  snippetType: SnippetType;
  onSave: (data: {
    raw_text: string;
    grid_data: GridData;
    snippet_type: SnippetType;
    grid_mode: GridMode;
    beats_per_line: number | null;
  }) => void;
  onCancel: () => void;
}
```

### Grid Modes

- **Auto**: Uses backend response as-is; lines can vary in cell count
- **Fixed**: Pads short lines with rest cells to match `beatsPerLine`, truncates long lines with `.slice(0, beatsPerLine)`
