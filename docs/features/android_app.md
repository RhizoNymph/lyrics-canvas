# Android App

Native Android client for Lyrics Canvas: Kotlin, Jetpack Compose, the existing FastAPI backend as the
engine. Same features as the web client (canvas of snippets with beat grids, rhyme sidebar with
materialize, word-range anchors, block merge, grid chunk drag / rests / line reorder), redesigned for
touch. Lives in `android/` of this repo.

## Scope

- Pure-Kotlin modules for the model, the client-side logic, and the HTTP client, each JVM-testable.
- An app module with Compose UI: login/register, canvas list, canvas screen, rhyme sheet, grid editor.
- Backend hardening the client needs: typed response models on every route the app calls, a committed
  OpenAPI snapshot, and a server-side merge endpoint shared by both clients.

## Non-scope

- Offline engine (CMU dict / scorer / g2p on device). The backend is required.
- Hosting the backend. Dev builds point at the emulator host (`10.0.2.2:8000`) or a LAN IP via
  `-PapiBaseUrl=`.
- Google sign-in (email/password only in v1), export, t-SNE, similarity, search, groups UI, tags editing
  (tags are displayed and preserved, not edited).
- Server-side undo. The app keeps the web client's local-only undo semantics: none in v1.

## Modules

| Module | Kind | Role | Depends on |
|---|---|---|---|
| `:core:model` | Kotlin JVM + serialization | `@Serializable` DTOs mirroring the API (snake_case field names via `@SerialName`), grid types, rhyme types, typed ids | — |
| `:core:logic` | Kotlin JVM | Ports of `frontend/src/lib/grid/*`, `lib/rhyme/{anchorRange,slotFit,materializePosition,candidateList,queryFromSelection,geometry}`, plus canvas geometry (world/screen transforms, hit-testing) | model |
| `:core:data` | Kotlin JVM, OkHttp + kotlinx.serialization | `ApiClient` (bearer auth, typed `ApiError`), repositories, `TokenStore` interface | model |
| `:app` | Android application, Compose | Screens, ViewModels (`StateFlow`), navigation, DataStore-backed `TokenStore`, Compose canvas rendering | all |

Logic ported from TypeScript keeps its test cases: every `*.test.ts` in the ported modules becomes a
JUnit 5 test with the same inputs and expectations. Merge logic is NOT ported; both clients call the
server merge endpoint.

## API surface used by the app

| Use | Route |
|---|---|
| Auth | `POST /api/auth/login`, `POST /api/auth/register`, `GET /api/auth/me` |
| Canvases | `GET /api/canvases`, `POST /api/canvases`, `GET /api/canvases/{id}`, `PUT /api/canvases/{id}` (viewport) |
| Snippets | `POST /api/canvases/{id}/snippets`, `PUT /api/snippets/{id}`, `DELETE /api/snippets/{id}`, `PATCH /api/snippets/batch-position` |
| Merge | `POST /api/canvases/{id}/merge` (new, see Backend) |
| NLP | `POST /api/nlp/syllabify`, `POST /api/nlp/rhyme-suggest` |

`docs/api/openapi.json` is the committed snapshot; `:core:model` has a test that every DTO field the app
reads exists in that snapshot with a compatible type.

### Backend: `POST /api/canvases/{canvas_id}/merge`

```jsonc
// request
{ "snippet_ids": ["uuid", "uuid"], "mode": "vertical" | "horizontal" }
// 200 response
{ "snippet": SnippetResponse, "deleted_ids": ["uuid", ...] }
```

Server orders the snippets by canvas position (vertical: y then x; horizontal: x then y; id tie-break),
builds the merged grid with the same rules as `frontend/src/lib/merge` (fixed grids pad with rests and
raise `beats_per_line`, never truncate; horizontal re-bases `word_index`), creates the merged snippet at
the first block's position with the union of tags and the first block's group, then deletes the
originals, all in one transaction. 404 if any id is not owned by the caller or not in the canvas; 422 if
fewer than two distinct ids. The web `canvasStore.mergeSelected` switches to this endpoint.

## Touch interaction mapping

| Web | Android |
|---|---|
| Drag snippet | Long-press-drag a card (short drag pans the canvas) |
| Shift-click multi-select | Long-press a card enters selection mode; taps toggle; back or ✕ exits |
| Right-click menu, merge shortcuts | Contextual top app bar while selecting: Merge ⇣, Merge ⇢, Delete, Rhymes |
| Ctrl+wheel zoom, middle-drag pan | Pinch zoom (0.1–5×, anchored at the centroid), one/two-finger pan |
| Double-click to edit | Tap a card while not selecting opens the grid editor |
| Double-click empty canvas | FAB "New snippet" creates one at the viewport centre and opens the editor |
| Rhyme sidebar (380 px) | `ModalBottomSheet` on compact width; permanent side pane at ≥ 840 dp (expanded width class) |
| Anchor chip ▸ word range | Same: chip expands to syllable strips; tap start word, tap end word |
| Row expand / Materialize / Dismiss | Same, as list rows; swipe-to-dismiss as a shortcut |
| Grid editor overlay | Full-screen editor screen |
| Click / shift-click chunk, drag | Tap a cell selects; drag the selection handles to extend; long-press-drag the chunk; drop indicator between cells |
| Alt+↑/↓ line move | Drag handle at the row start (reorderable column) plus ▲/▼ in the row menu |
| Insert / Delete rest | Row toolbar `+·` and `−` |

Cells are 48 dp wide minimum; rows scroll horizontally inside the editor; the text field for the active
row sits above the IME (`imePadding`), and the grid scrolls the active row into view.

## Data / control flow

```
Login -> ApiClient(token) -> CanvasListViewModel -> CanvasViewModel(StateFlow<CanvasUiState>)
  canvas gestures -> CanvasViewModel (viewport, selection, moves) -> batch-position on drag end
  selection -> RhymeViewModel: anchors(selection, ranges, excluded) + typed text + controls
             -> debounce(150ms) + mapLatest -> rhyme-suggest -> rows
  Materialize -> materializePosition() -> POST snippet (derived_from) -> CanvasViewModel inserts
  Merge -> POST merge -> replace originals with merged snippet, select it
  Tap card -> GridEditorViewModel(EditorRowsState port) -> PUT snippet (raw_text + grid_data)
```

## Invariants

- The canvas only ever shows server-confirmed snippets; nothing from the rhyme sheet is drawn until
  Materialize succeeds.
- Logic in `:core:logic` is pure and has the same results as the TypeScript it ports for the same inputs.
- `word_index` stays increasing within a line after any edit; `raw_text` is always derivable from the grid.
- Errors are typed (`ApiError` sealed hierarchy; `RhymeError`, `MergeError` mapped from it). No
  exceptions escape a ViewModel; failures become UI state.
- All dependency versions are pinned in `android/gradle/libs.versions.toml` and at least a week old.

## Building (this machine, sandboxed)

`~/.gradle` is read-only inside the agent sandbox. Source the env file in the session scratchpad
(`android-env.sh`): it points `GRADLE_USER_HOME` at a writable copy, uses `~/.gradle/caches` as a
read-only dependency cache, JDK 21, and a scratch `ANDROID_USER_HOME` with the debug keystore. Then:

```
cd android && ./gradlew --offline assembleDebug test
```

Outside the sandbox a plain `./gradlew assembleDebug test` works with the normal Gradle home.

## Milestones

| # | Milestone | Branch |
|---|---|---|
| A1 | Typed API responses, OpenAPI snapshot, merge endpoint, web switched to it | `feat/typed-api` |
| A2 | `:core:model`, `:core:logic`, `:core:data` with ported tests | `feat/android-app` |
| B1 | App shell, auth, canvas list, canvas screen, selection + merge, rhyme sheet + materialize | `feat/android-canvas` |
| B2 | Grid editor screen | `feat/android-editor` |

## Files

Filled in as modules land; see each module's package for exports.
