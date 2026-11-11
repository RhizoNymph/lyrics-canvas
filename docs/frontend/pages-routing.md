# Pages & Routing

SPA routing with protected routes via JWT token check.

## Route Map

```
/login           -> LoginPage       (public)
/register        -> RegisterPage    (public)
/                -> CanvasListPage  (protected)
/canvas/:id      -> CanvasPage      (protected)
*                -> Redirect to /
```

## App (`src/App.tsx`)

Root component. On mount, calls `authStore.loadUser()` to hydrate user context from localStorage token.

### ProtectedRoute

Wrapper component that checks `authStore.token`:
- If no token: redirects to `/login`
- If `loading` (token validation): shows loading state
- If authenticated: renders children

---

## LoginPage

**File:** `src/components/pages/LoginPage.tsx`

### State

- `email`, `password`, `error` (all strings)

### Flow

```
Submit form -> authStore.login(email, password)
  -> Success: navigate to /
  -> Failure: display error message
```

### Layout

Centered form (360px) on dark background (#12122a). Email input, password input, "Sign In" button, link to RegisterPage.

---

## RegisterPage

**File:** `src/components/pages/RegisterPage.tsx`

### State

- `email`, `password`, `displayName`, `error` (all strings)

### Flow

```
Submit form -> authStore.register(email, password, displayName)
  -> Success: navigate to /
  -> Failure: display error message
```

### Layout

Same as LoginPage with additional display name field. "Create Account" button, link to LoginPage.

---

## CanvasListPage

**File:** `src/components/pages/CanvasListPage.tsx`

Dashboard showing all of the user's canvases.

### State

| State | Type | Purpose |
|-------|------|---------|
| `canvases` | Canvas[] | User's canvases |
| `loading` | boolean | Initial fetch state |
| `newTitle` | string | New canvas title input |
| `datasetOpen` | boolean | The Voice dataset dialog is open |
| `createError` | string \| null | Inline create error (title over 255 characters, server failure) |
| `renamingId` | string \| null | The card whose title is being edited |

### Interactions

| Action | API Call |
|--------|---------|
| Page load | `GET /api/canvases` (`placementApi.listCanvases`) |
| Create canvas | `POST /api/canvases` (`createCanvasFromInput`: blank -> "Untitled Canvas", 1..255) -> navigate to `/canvas/{id}` |
| ✎ on a card | Inline `TitleEditor`: Enter -> `PUT /api/canvases/{id} {title}`, card updated in place; Esc cancels; errors inline |
| Delete canvas | `DELETE /api/canvases/{id}` |
| Sign out | `authStore.logout()` |
| Voice dataset | Opens `VoiceDatasetDialog`: `GET /api/recordings/stats`, download `GET /api/recordings/export` |
| Enter in title input | Create canvas |

### Layout

- Header with app title, user display name, Voice dataset button, Sign Out button
- New canvas title input with create button
- Grid of canvas cards (title, updated date, "Public" badge, delete button)
- Empty state when no canvases

---

## CanvasPage

**File:** `src/components/pages/CanvasPage.tsx`

Main editing view that orchestrates all canvas editing components.

### URL Params

- `id`: Canvas UUID from route `/canvas/:id`

### Lifecycle

- On mount: `canvasStore.loadCanvas(id)` -- navigates home on error
- On mount: `tagStore.load()` (user-wide tags and presets); per canvas id: `tagFilterStore.open(id)` restores that canvas's filter and `canvasSearchStore.open(id)` its session search text
- `/canvas/:id?snippet=<sid>`: after this page's load of the canvas resolves, `useSnippetFocus` selects the snippet and centres the viewport on it (list mode scrolls to it), clearing the in-canvas search if it hides it; a tag filter that still hides it shows the toast "Hidden by the tag filter". The parameter is then removed (replace)
- Registers keyboard listeners, cleans up on unmount
- On unmount (or canvas change): `voiceStore.closeRecorder()`, so a recorder never reopens on the next canvas

### Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| Ctrl+Z / Cmd+Z | `canvasStore.undo()` |
| Ctrl+Shift+Z / Cmd+Shift+Z | `canvasStore.redo()` |
| Ctrl+F / Cmd+F | `canvasSearchStore.requestFocus()`: focus the in-canvas search box (works while typing) |
| Ctrl+Shift+L / Cmd+Shift+L | `uiStore.toggleViewMode()` (canvas / list; ignored while typing or editing) |
| Delete / Backspace | `canvasStore.removeFromCanvas(selection)`: remove the selected snippets from this canvas (deleted for good when on no other canvas; ignored when focus is in an input, textarea, select, or when a snippet is being edited) |
| Alt+Shift+R (Option+Shift+R) | `voiceStore.openRecorder()`: record by voice; works while typing in the editor (matched on `code === 'KeyR'`) |

All page shortcuts are ignored while the voice recorder modal is open; the modal owns Space/Enter
(next line) and Escape (cancel).

### Component Layout

```
+--------------------------------------------------+
| Toolbar                                           |
+--------------------------------------------------+
| TagExpressionBar (search box + tag filter)        |
+--------------------------------------+-----------+
|                                      |           |
| CanvasViewport or ListView (viewMode)| Rhyme     |
|                                      | Sidebar   |
|                                      |           |
+--------------------------------------+           |
| SimilarityPanel                      |           |
+--------------------------------------+-----------+
| ExportDialog (overlay)                            |
| VoiceCaptureModal (overlay, voiceStore.request)   |
+--------------------------------------------------+
```

---

## Entry Point

**File:** `src/main.tsx`

Renders `<App />` into DOM root with `React.StrictMode`.

## Canvas list search

The canvas list header has a search input ("Search all canvases and snippets"). Typing (250 ms debounce,
`hooks/useGlobalSearch.ts` over `lib/search/globalSearch.ts`) calls `GET /api/search` and replaces the list with
`GlobalSearchResultsView`: a Canvases section (open the canvas) and a Snippets section (type chip, excerpt with the
terms highlighted, every canvas title as its own link; the row opens the first). Blank input (or Escape) shows the
normal list. Typed errors (`invalid_search_query`, network) and "No matches". See
[web_rests_and_search.md](../features/web_rests_and_search.md).
