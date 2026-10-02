# API Client & Types

Centralized HTTP client and shared type definitions.

## API Client

**File:** `src/api/client.ts`

Axios instance with JWT injection and 401 handling.

### Configuration

- Base URL: `VITE_API_URL` env var (defaults to empty string for same-origin)
- Content-Type: `application/json`

### Interceptors

**Request**: Injects `Authorization: Bearer <token>` from localStorage on every request.

**Response**: On 401 Unauthorized, removes token from localStorage and redirects to `/login`.

### Endpoint Modules

#### authApi

| Method | Path | Body/Params |
|--------|------|-------------|
| `register(email, password, display_name)` | POST `/api/auth/register` | JSON body |
| `login(email, password)` | POST `/api/auth/login` | JSON body |
| `me()` | GET `/api/auth/me` | - |

#### canvasApi

| Method | Path | Body/Params |
|--------|------|-------------|
| `list()` | GET `/api/canvases` | - |
| `create(title)` | POST `/api/canvases` | `{ title }` |
| `get(id)` | GET `/api/canvases/{id}` | - |
| `update(id, data)` | PUT `/api/canvases/{id}` | JSON body |
| `delete(id)` | DELETE `/api/canvases/{id}` | - |
| `share(id)` | POST `/api/canvases/{id}/share` | - |
| `fork(id)` | POST `/api/canvases/{id}/fork` | - |
| `getShared(token)` | GET `/api/canvases/shared/{token}` | - |
| `merge(canvasId, snippetIds, mode)` | POST `/api/canvases/{id}/merge` | `{ snippet_ids, mode }` -> `MergeResponse { snippet, deleted_ids }` |

#### snippetApi

| Method | Path | Body/Params |
|--------|------|-------------|
| `create(canvasId, data: SnippetCreateBody)` | POST `/api/canvases/{canvasId}/snippets` | `{ position_x, position_y, snippet_type?, raw_text?, derived_from? }`; typed `AxiosResponse<Snippet>` |
| `update(id, data)` | PUT `/api/snippets/{id}` | JSON body |
| `delete(id)` | DELETE `/api/snippets/{id}` | - |
| `batchPosition(updates)` | PATCH `/api/snippets/batch-position` | `[{id, position_x, position_y}]` |

#### groupApi

| Method | Path | Body/Params |
|--------|------|-------------|
| `create(canvasId, name)` | POST `/api/canvases/{canvasId}/groups` | `{ name }` |
| `update(id, data)` | PUT `/api/groups/{id}` | JSON body |
| `delete(id)` | DELETE `/api/groups/{id}` | - |

#### tagApi

| Method | Path | Body/Params |
|--------|------|-------------|
| `create(canvasId, name, color)` | POST `/api/canvases/{canvasId}/tags` | `{ name, color }` |
| `update(id, data)` | PUT `/api/tags/{id}` | `{ name?, color? }` |
| `delete(id)` | DELETE `/api/tags/{id}` | - |
| `addToSnippet(snippetId, tagId)` | POST `/api/snippets/{snippetId}/tags?tag_id={tagId}` | Query param |
| `removeFromSnippet(snippetId, tagId)` | DELETE `/api/snippets/{snippetId}/tags/{tagId}` | - |

#### nlpApi

| Method | Path | Body/Params |
|--------|------|-------------|
| `syllabify(text)` | POST `/api/nlp/syllabify` | `{ text }` |
| `search(canvasId, q)` | GET `/api/canvases/{canvasId}/search` | `?q=` |
| `similar(snippetId, topK=10)` | GET `/api/snippets/{snippetId}/similar` | `?top_k=` |
| `tsne(canvasId, nComponents=2)` | POST `/api/canvases/{canvasId}/tsne` | `{ n_components }` |
| `rhymes(word)` | GET `/api/nlp/rhymes` | `?word=` |
| `rhymeSuggest(req: RhymeSuggestRequest)` | POST `/api/nlp/rhyme-suggest` | JSON body; typed `AxiosResponse<RhymeSuggestResponse>` |

#### exportApi

| Method | Path | Body/Params |
|--------|------|-------------|
| `export(canvasId, format)` | POST `/api/canvases/{canvasId}/export` | `{ format }`, responseType: 'blob' |

---

## Type System

**Files:** `src/types/index.ts` (core), `src/types/rhyme.ts` (rhyme-suggest contract)

### Enums

```typescript
type SnippetType = 'verse' | 'chorus' | 'bridge' | 'hook' | 'freestyle';
type GridMode = 'auto' | 'fixed';
```

### Beat Grid Types

```typescript
interface GridCell {
  syllable?: string;
  stress?: number;       // 0=unstressed, 1=primary, 2=secondary
  word_index?: number;
  rest?: boolean;
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

### Domain Models

```typescript
interface Snippet {
  id: string;
  canvas_id: string;
  snippet_type: SnippetType;
  grid_mode: GridMode;
  beats_per_line: number | null;
  grid_data: GridData | null;
  raw_text: string;
  position_x: number;
  position_y: number;
  width: number;
  height: number;
  z_index: number;
  group_id: string | null;
  tags: Tag[];
  created_at: string;
  updated_at: string;
}

interface Group {
  id: string;
  canvas_id: string;
  name: string;
  position_x: number;
  position_y: number;
  snippet_ids: string[];
  created_at: string;
}

interface Tag {
  id: string;
  canvas_id: string;
  name: string;
  color: string;
}

interface Canvas {
  id: string;
  owner_id: string;
  title: string;
  is_public: boolean;
  share_token: string | null;
  viewport_state: { x: number; y: number; zoom: number } | null;
  created_at: string;
  updated_at: string;
}

interface CanvasDetail extends Canvas {
  snippets: Snippet[];
  groups: Group[];
  tags: Tag[];
}

interface User {
  id: string;
  email: string;
  display_name: string;
  oauth_provider: string | null;
  created_at: string;
}
```

### NLP Result Types

```typescript
interface SimilarityResult {
  snippet_id: string;
  raw_text: string;
  score: number;
  snippet_type: string;
}

interface RhymeResult {
  word: string;
  rhyme_type: string;
  source: string;
  snippet_id?: string;
  snippet_preview?: string;
}

interface TSNEPosition {
  snippet_id: string;
  x: number;
  y: number;
  z?: number;
}
```

### Rhyme Types (`src/types/rhyme.ts`)

Mirrors of `backend/app/schemas/rhyme.py` as specified in
[`docs/features/rhyme_view.md`](../features/rhyme_view.md#api-contract): `RhymeSyllable`, `RhymeUnit`
(with `grid_line: GridLine`), `SyllableAlignment`, `RhymeCandidate`, `AnchorRef` (discriminated on `kind`),
`Slot`, `ScoreWeights`, `DialectRule`/`DialectMap`, `DerivedFrom`, `RhymeSuggestRequest`,
`RhymeSuggestResponse`. Also `RhymeK = 1 | 2 | 3 | 4`, `STRESS_PATTERN = /^[012]+$/`, `RHYME_PAGE_SIZE = 40`,
`RHYME_MAX_LIMIT = 100`.

```typescript
class RhymeError extends Error {
  kind: 'network' | 'not_found' | 'invalid_request' | 'server' | 'unknown';
  operation: 'query' | 'materialize';
  status: number | null;
}
```

`lib/rhyme/errors.ts` `toRhymeError(err, operation)` converts axios errors: no response -> `network`,
404 -> `not_found`, 400/422 -> `invalid_request` (pydantic `detail` messages joined), otherwise `server`.

### Constants

```typescript
const SNIPPET_TYPE_COLORS: Record<SnippetType, string> = {
  verse: '#3b82f6',      // Blue
  chorus: '#f59e0b',     // Amber
  bridge: '#8b5cf6',     // Purple
  hook: '#ef4444',       // Red
  freestyle: '#6b7280',  // Gray
};
```
