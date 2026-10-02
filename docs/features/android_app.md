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
TokenStore.tokens -> SessionViewModel (SignedOut | SignedIn) -> AppNavHost (login <-> canvas list)
Login -> AuthViewModel -> AuthRepository (stores token) -> SignedIn -> CanvasListViewModel
Open canvas -> CanvasViewModel(StateFlow<CanvasUiState>) + RhymeViewModel(port = CanvasViewModel)
  pointer events -> reduceGesture (pure) -> CanvasGestureAction
    -> CanvasViewModel: panBy / transform (viewport, debounced saveViewport + flush on ON_STOP),
       onTap (open editor | toggle selection), onLongPress (select, start move),
       moveCardBy (local), endMove (one batch-position, revert on failure)
  selection (RhymeCanvasPort.rhymeView) + sheet controls -> planRhymeQuery
    -> distinctUntilChanged -> debounce(150ms, first at once) -> mapLatest -> rhyme-suggest -> rows
  Materialize -> materializePosition() -> POST snippet (derived_from) -> port.insertMaterialized
  Merge -> MergeRequest.of -> POST merge -> replace originals with merged snippet, select it
  Tap card -> EditorRoute -> GridEditorRoute(snippet) -> onSaved -> CanvasViewModel.replaceSnippet
any 401 -> ApiClient clears TokenStore -> SignedOut -> login
```

Screens, ViewModels, gestures, rendering and the rhyme sheet in detail:
[docs/android/canvas.md](../android/canvas.md).

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

Paths are under `android/`. `:app` packages are under `app/src/main/kotlin/dev/nymph/lyrics/app/`; the B1 files are described in full in [docs/android/canvas.md](../android/canvas.md#files).

| Module | Package | File | Role / key exports |
|---|---|---|---|
| `:core:model` | `dev.nymph.lyrics.model` | `Ids.kt` | `SnippetId`, `CanvasId`, `TagId`, `GroupId`, `UserId` (`@JvmInline` value classes, serialised as bare strings) |
| | | `Grid.kt` | `GridCell` (`isRest`, `GridCell.REST`), `GridLine`, `GridData` (also the syllabify response) |
| | | `LyricsJson.kt` | `LyricsJson` (`ignoreUnknownKeys`, `explicitNulls = false`, `encodeDefaults`) |
| | | `Auth.kt` | `LoginRequest`, `RegisterRequest`, `TokenResponse`, `User` |
| | | `Canvas.kt` | `Canvas`, `CanvasDetail` (+ `toCanvas()`), `ViewportState`, `CanvasCreateRequest`, `CanvasUpdateRequest`, `Tag`, `Group` |
| | | `Snippet.kt` | `Snippet` (phonetics as `JsonElement?` passthrough), `SnippetType`, `GridMode`, `Provenance`, `DerivedFrom`, `SnippetCreateRequest`, `SnippetUpdateRequest`, `PositionUpdate`, `BatchPositionRequest`, `BatchPositionResponse` |
| | | `Merge.kt` | `MergeMode`, `MergeRequest` (only via `MergeRequest.of`, null for < 2 distinct ids), `MergeResponse` |
| | | `Nlp.kt` | `SyllabifyRequest` |
| | | `Rhyme.kt` | `RhymeLimits` (`PAGE_SIZE`, `MAX_LIMIT`, `STRESS_PATTERN`), `RhymeK` (int-serialised enum), `SourceName`, sealed `AnchorRef` = `SnippetAnchorRef` / `TextAnchorRef` (discriminator `kind`), `AnchorRange`, `Slot`, `ScoreWeights`, `DialectRule(Kind)`, `DialectMap`, `RhymeSuggestRequest`, `RhymeSyllable`, `RhymeUnitFields`, `RhymeUnit`, `RhymeCandidate`, `SyllableAlignment`, `RhymeSuggestResponse` |
| | `dev.nymph.lyrics.model.error` | `ApiError.kt` | sealed `ApiError` (`Unauthorized(status, detail)`, `NotFound(detail)`, `Validation(status, detail)`, `Server(status, detail)`, `Network(cause)`, `Decoding(cause)`), `ApiError.fromHttp`, `ApiError.detailFromBody` (FastAPI string or pydantic list) |
| | | `ApiResult.kt` | sealed `ApiResult<T>` (`Success`, `Failure`), `map`, `flatMap`, `fold`, `onSuccess`, `onFailure` |
| `:core:logic` | `dev.nymph.lyrics.logic` | `JsCompat.kt` | internal `jsTrim`, `jsSplitWhitespace`, `jsSlice`: JS string/array semantics the ports rely on |
| | `dev.nymph.lyrics.logic.grid` | `WordIndex.kt`, `RawText.kt`, `MoveChunk.kt`, `ReorderLines.kt`, `Rests.kt`, `DropIndex.kt`, `LocalSyllabify.kt` | `rebaseWordIndexes`, `countSyllables`, `attachWords(All)`, `rebuildRawText(All)`, `withRebuiltText`, `moveChunk`, `reorderLines`, `insertRest`, `removeCell`, `gapFromX`, `moveTargetFromGap`, `estimateSyllableCount`, `splitWord`, `localSyllabifyLine` |
| | `dev.nymph.lyrics.logic.rhyme` | `AnchorRange.kt` | sealed `RangeClickState` (`None`, `AwaitingEnd`, `Complete`), `wordSpan`, `clickWord`, `cellInRange`, `sliceGridLine`, `lineWords`, `lineWordCount`, `gridLineOf`, `rangeText`, `rangeFits`, `anchorRefFor` |
| | | `SlotFit.kt` | `slotFromGridLine`, `lastNonEmptyLineIndex`, `slotFromSnippet`, `slotFromRange`, `fits`, `parseManualSlot` → `ManualSlotResult` (`Ok` / `Invalid(ManualSlotError)`) |
| | | `MaterializePosition.kt` | `MATERIALIZE_GAP_X`, `MATERIALIZE_STEP_Y`, `AnchorRect`, `Snippet.toAnchorRect()`, `materializePosition` |
| | | `CandidateList.kt` | `listRows`, `ShownRange`, `shownRange`, `canLoadMore` |
| | | `QueryFromSelection.kt` | `selectedSnippets`, `anchorSnippets`, `anchorsFromSelection` |
| | | `RhymeQuery.kt` | request-building rules of `rhymeStore.ts`: `SlotMode`, `RhymeSources`, `RhymeQueryParams`, `RhymeSelection`, sealed `RhymeQueryPlan` (`Skip` / `Query`, both carrying pruned exclusions and ranges), `planRhymeQuery`, `pruneExclusions`, `pruneRanges`, `slotFromFirstAnchor`, `derivedFromFor`, `materializeRequest` |
| | | `RhymeError.kt` | `RhymeErrorKind`, `RhymeOperation`, `RhymeError`, `toRhymeError(ApiError, op)` |
| | `dev.nymph.lyrics.logic.editor` | `EditorRowsState.kt` | `EditorRowsState` (internal constructor), `initEditorState`, `gridLineForRow`, `setActiveRow`, `typeLine`, `insertRow`, `deleteRow`, `moveChunkInRow`, `insertRestInRow`, `removeCellsInRow`, `reorderRows`, `mergeSyllabified`, `needsSyllabify`, `rawTextForSave` |
| | `dev.nymph.lyrics.logic.canvas` | `Viewport.kt` | `Point`, `ViewSize`, `MIN_ZOOM`/`MAX_ZOOM`, `clampZoom`, `Viewport` (`worldToScreen`, `screenToWorld`, `zoomAbout`, `scaleAbout`, `panBy`, `toState`, `Viewport.from(ViewportState?)`), `viewportCenter` |
| | | `HitTest.kt` | `Rect`, `MIN/MAX_SNIPPET_SCALE`, `clampSnippetScale`, `cardBounds`, `cardScreenBounds`, `drawOrder`, `hitTest`, `hitTestScreen` |
| | | `MergeError.kt` | sealed `MergeError` (`TooFewSnippets`, `NotFound`, `Invalid`, `Server`, `Network`, `Unknown`), `toMergeError` |
| `:app` | `…app` | `LyricsApp.kt`, `MainActivity.kt` | Application holding the `AppContainer`; activity hosting `LyricsTheme { AppNavHost }` |
| | `…app.di` | `AppContainer.kt`, `DataStoreTokenStore.kt`, `ProcessScope.kt` | Manual dependency graph; DataStore `TokenStore`; process-lifetime scope for saves that outlive a screen |
| | `…app.nav` | `Routes.kt`, `AppNavHost.kt` | Type-safe routes (`AuthRoute`, `CanvasListRoute`, `CanvasRoute`, `EditorRoute`); session-driven navigation; editor destination sharing the canvas ViewModel |
| | `…app.session` | `SessionViewModel.kt` | `SessionState`, `SessionViewModel` (token store -> signed in / out, `signOut`) |
| | `…app.auth` | `AuthViewModel.kt`, `AuthScreen.kt` | Email / password login and register |
| | `…app.canvaslist` | `CanvasListViewModel.kt`, `CanvasListScreen.kt` | List, create, open canvases |
| | `…app.canvas` | `CanvasUiState.kt`, `CanvasViewModel.kt`, `CanvasScreen.kt`, `CanvasTopBars.kt`, `SnippetCanvas.kt` | Canvas state (`Selection`, `CanvasUiState`, `CanvasEvent`), ViewModel (camera, selection, moves, merge, delete, create, `RhymeCanvasPort`), screen with adaptive rhyme pane / sheet, contextual top bar, Compose `Canvas` drawing |
| | `…app.canvas.render` | `CardLayout.kt`, `Culling.kt`, `CardRenderer.kt` | Pure card geometry (web `gridDraw` / `SnippetCard`), culling and background grid; `DrawScope.drawCard` |
| | `…app.canvas.gesture` | `GestureReducer.kt`, `CanvasGestures.kt` | Pure touch state machine (`reduceGesture`); pointer-input modifier feeding it |
| | `…app.rhyme` | `RhymeCanvasPort.kt`, `RhymeUiState.kt`, `AnchorChips.kt`, `RhymeViewModel.kt` | Canvas port for the sheet; sheet state; pure chip model; query pipeline, controls, materialize |
| | `…app.rhyme.ui` | `RhymePanel.kt`, `QueryControls.kt`, `AnchorChipsView.kt`, `CandidateRow.kt`, `SyllableStrip.kt`, `AlignmentTable.kt` | Sheet content (web `components/rhyme/*`) |
| | `…app.ui` | `UserMessages.kt`, `theme/Palette.kt`, `theme/LyricsTheme.kt` | `ApiError.userMessage()`, hex colour parsing; web palette; dark Material 3 theme |
| | `…app.editor` | `GridEditorRoute.kt` | Editor entry point (B2) |
| `:core:data` | `dev.nymph.lyrics.data` | `ApiClient.kt` | `ApiClient(baseUrl: HttpUrl, tokenStore, okHttpClient, ioDispatcher)`, `ApiClient.create(String, ...)`, `HttpMethod`; `get`, `send`, `delete` return `ApiResult` |
| | | `TokenStore.kt` | `TokenStore` (`tokens: Flow<String?>`, suspend `token`/`save`/`clear`), `InMemoryTokenStore` |
| | `dev.nymph.lyrics.data.repository` | `AuthRepository.kt` | `AuthRepository` / `HttpAuthRepository` (`login`, `register` store the token then load `me`; `me`; `logout`) |
| | | `CanvasRepository.kt` | `CanvasRepository` / `HttpCanvasRepository` (`list`, `create`, `get`, `update`, `saveViewport`) |
| | | `SnippetRepository.kt` | `SnippetRepository` / `HttpSnippetRepository` (`create`, `update`, `delete`, `batchPosition`, `merge`) |
| | | `NlpRepository.kt` | `NlpRepository` / `HttpNlpRepository` (`syllabify`, `rhymeSuggest`) |

### Core data / control flow

```
Repository fn -> ApiClient.call(method, segments, body)
  -> TokenStore.token() -> Request (Accept/Content-Type JSON, Bearer when a token exists)
  -> Call.enqueue, suspended in suspendCancellableCoroutine (coroutine cancel => call.cancel)
  -> body read on ioDispatcher
     IOException            -> ApiResult.Failure(Network)
     non-2xx                -> ApiError.fromHttp(status, body); 401 also clears the TokenStore
     2xx, bad shape         -> Failure(Decoding)
     2xx                    -> Success(LyricsJson.decode(body))
ViewModel -> toRhymeError / toMergeError (:core:logic) -> UI state
```

## Ported logic

Every `it(...)` of the ported `*.test.ts` files is one JUnit 5 test with the same inputs and expectations.

| TypeScript | Kotlin | TS tests | Kotlin tests |
|---|---|---|---|
| `frontend/src/lib/grid/moveChunk.ts` | `core/logic/.../grid/MoveChunk.kt` | 9 | 9 (`MoveChunkTest`) |
| `frontend/src/lib/grid/rawText.ts` | `grid/RawText.kt` | 7 | 7 (`RawTextTest`) |
| `frontend/src/lib/grid/reorderLines.ts` | `grid/ReorderLines.kt` | 4 | 4 (`ReorderLinesTest`) |
| `frontend/src/lib/grid/rests.ts` | `grid/Rests.kt` | 7 | 7 (`RestsTest`) |
| `frontend/src/lib/grid/wordIndex.ts` | `grid/WordIndex.kt` | 9 | 9 (`WordIndexTest`) |
| `frontend/src/lib/grid/dropIndex.ts` | `grid/DropIndex.kt` | 6 | 6 (`DropIndexTest`) |
| `frontend/src/lib/grid/localSyllabify.ts` | `grid/LocalSyllabify.kt` | 4 | 4 (`LocalSyllabifyTest`) |
| `frontend/src/lib/rhyme/anchorRange.ts` | `rhyme/AnchorRange.kt` | 13 | 13 (`AnchorRangeTest`) |
| `frontend/src/lib/rhyme/slotFit.ts` | `rhyme/SlotFit.kt` | 13 | 13 (`SlotFitTest`) |
| `frontend/src/lib/rhyme/materializePosition.ts` | `rhyme/MaterializePosition.kt` | 6 | 6 (`MaterializePositionTest`) |
| `frontend/src/lib/rhyme/candidateList.ts` | `rhyme/CandidateList.kt` | 7 | 7 (`CandidateListTest`) |
| `frontend/src/lib/rhyme/queryFromSelection.ts` | `rhyme/QueryFromSelection.kt` | 10 | 10 (`QueryFromSelectionTest`) |
| `frontend/src/lib/rhyme/geometry.ts` | `canvas/Viewport.kt` (`viewportCenter`) | 1 | 1 (first case of `ViewportTest`) |
| `frontend/src/lib/rhyme/errors.ts` | `rhyme/RhymeError.kt` | 6 | 6 + 1 (`RhymeErrorTest`, adds 401 mapping) |
| `frontend/src/components/canvas/editorState.ts` | `editor/EditorRowsState.kt` | 21 | 21 (`EditorRowsStateTest`) |
| `frontend/src/stores/rhymeStore.ts` (request building only) | `rhyme/RhymeQuery.kt` | n/a | 12 (`RhymeQueryTest`, from the store's request / exclusion / range cases) |
| (new) canvas geometry | `canvas/Viewport.kt`, `canvas/HitTest.kt`, `canvas/MergeError.kt` | n/a | 7 + 9 + 2 |

Not ported: `lib/rhyme/debounce.ts` (the app uses Flow `debounce`), `lib/merge` (server merge endpoint).

## Invariants (core modules)

- `:core:model` and `:core:logic` are pure Kotlin/JVM; only `:core:data` touches the network.
- No `ApiClient` call throws (coroutine cancellation excepted); every outcome is an `ApiResult`.
- A 401 from any route clears the `TokenStore`; 403 does not (it means no credentials were sent).
- Grid reducers return the identical instance for a no-op (callers can compare by reference, as the
  web's `next === current` checks do).
- `EditorRowsState` keeps one grid line per non-blank row; only the reducers can construct it.
- A `MergeRequest` always holds at least two distinct ids.

## Decisions

Deviations from the contract above, and the reasons.

1. **`ApiError` / `ApiResult` live in `:core:model`** (`dev.nymph.lyrics.model.error`), not
   `:core:data`. `:core:logic` maps them to `RhymeError` / `MergeError` and must not depend on
   OkHttp. `:core:data` is still the only producer.
2. **`ApiError` carries more than the contract lists:** `Unauthorized(status, detail)` covers 401
   and 403 (FastAPI's `HTTPBearer` answers 403 when no header is sent); `NotFound` and `Server` keep
   the server's `detail`; `Validation(status, detail)` covers 400 (register failure) and 422, with
   pydantic messages joined by `"; "` exactly as `lib/rhyme/errors.ts` does. Only 401 clears the token.
3. **`TokenStore` is suspend and observable** (`tokens: Flow<String?>`, suspend `token`/`save`/
   `clear`) instead of a blocking `token(): String?`: the DataStore implementation is asynchronous,
   and the app needs to observe the 401 sign-out to navigate to login.
4. **`ApiClient` takes the `TokenStore`, not a bare token provider,** because it must clear the
   token on 401. It takes a parsed `HttpUrl` (an invalid base URL is unrepresentable);
   `ApiClient.create(String, ...)` returns null for a bad one. It accepts an `ioDispatcher`
   (default `Dispatchers.IO`) so tests and the app can inject one. Calls are enqueued and awaited
   with cancellation so a cancelled coroutine cancels the HTTP call.
5. **Repositories are interfaces with `Http*` implementations** so ViewModel tests in `:app` can
   use fakes.
6. **Nulls are omitted on the wire** (`explicitNulls = false`). The web sends `weights: null`,
   `dialect: null`, `line_index: null`; the backend treats an absent field and `null` identically on
   every route the app calls. Side effect: `SnippetUpdateRequest` cannot clear `group_id`, which the
   backend's `exclude_none` already prevents for the web too.
7. **Sealed / constrained types replace loose TS shapes:** `RangeClickState` is `None` /
   `AwaitingEnd` / `Complete` (no "awaiting end without a range"), keeping `range` and `awaitingEnd`
   accessors; `parseManualSlot` returns `ManualSlotResult.Ok` / `Invalid(ManualSlotError)`;
   `RhymeK` is an enum serialised as the integer 1..4; `MergeRequest` is built only by `of()`;
   `EditorRowsState` has an internal constructor; `Viewport` rejects a non-positive zoom
   (`Viewport.from` clamps saved state first).
8. **`RhymeCandidate` does not extend `RhymeUnit`** (Kotlin data classes cannot); both implement
   `RhymeUnitFields`.
9. **`errors.test.ts` inputs are `ApiError` values** instead of Axios errors (`ApiError.fromHttp`
   for HTTP cases, `ApiError.Decoding` for "plain errors"); the RhymeError pass-through is an
   overload. 401 maps to `server`, as any non-404/400/422 HTTP failure does in the TS.
10. **`rhymeStore.buildRequest` became the pure `planRhymeQuery`,** which returns the pruned
    exclusions and ranges for the caller to store (the web store writes them as a side effect).
11. **JS semantics are reproduced explicitly** (`JsCompat.kt`): `trim()` and `\s` include Unicode
    spaces, `slice` clamps and accepts negatives, `Math.round` rounds halves up, `Number()` parses
    blank as 0 (used by `parseManualSlot`).
12. **`geometry.ts` lives in `logic.canvas`** with the other camera math; `Point`, `ViewSize` and
    `Viewport` are shared by the rhyme and canvas packages.
13. **Lenient decoding of server-optional shapes:** `ViewportState` keys default to the identity
    viewport, `GridData.lines` defaults to empty (a stored `{}`), `GridLine.syllable_count` /
    `raw_text` default like the pydantic model. An unknown enum value (e.g. a new `snippet_type`)
    still fails as `ApiError.Decoding`.
14. **The OpenAPI snapshot test is not in this branch:** `docs/api/openapi.json` arrives with
    `feat/typed-api`. Until then the model tests pin field names against the pydantic models and
    route code directly (`RequestEncodingTest`, `ResponseDecodingTest`).
15. **Canvas screen coordinates are dp.** The core `Viewport`'s "screen" space is dp on Android (pointer
    pixels are divided by the density before they reach the ViewModel; drawing multiplies back), so a
    saved `viewport_state` means the same on any density and close to the web's CSS pixels.
16. **The viewport is persisted** (`saveViewport` 1 s after the last pan / zoom and on `ON_STOP`, in a
    process-lifetime scope). The web client loads `viewport_state` but never saves it.
17. **A card move commits only that card**, once on release and only if it moved (the web's
    `commitPositions` sends every snippet). A long-press-drag moves the pressed card only, even when
    other cards are selected. A failed commit puts the card back.
18. **No per-card resize on Android** (the web's corner handle); cards are drawn at scale 1.
19. **Rhyme re-query keys:** the plan depends on the selected snippets' content, not only their ids, so
    editing a selected snippet re-queries; a debounced plan that repeats the last completed query is not
    re-sent (the IME search action always sends). Camera-only canvas changes are not mirrored into the
    sheet state; materialize reads the camera when it runs.
20. **New snippet:** the FAB creates a freestyle snippet whose top-left is the viewport centre (the web
    creates at the double-click point).
21. **Delete** asks for confirmation, deletes the selected snippets in parallel, and keeps (still
    selected) any the server failed to delete.
22. **Editor result:** `onSaved` replaces the snippet and closes the editor; `onClose` closes it. Popping
    only happens while the editor is on top, so `onSaved` followed by `onClose` closes once.
23. **On compact width the rhyme sheet is modal**, so the selection cannot change while it is open; the
    ≥ 840 dp side pane leaves the canvas interactive.
24. **Level of detail:** card text is not drawn below a 4 dp cell font (zoom < ~0.31) and background grid
    lines closer than 8 dp are skipped.
