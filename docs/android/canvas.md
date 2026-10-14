# Android: Shell, Canvas Screen and Rhyme Sheet

Milestone B1 of the [Android app](../features/android_app.md): navigation and auth, the canvas list, the
canvas screen (Compose drawing, touch gestures, selection mode, merge, delete, new snippet) and the rhyme
sheet with Materialize. The grid editor (B2) is entered through `GridEditorRoute` and is documented with
its own milestone.

## Scope

- Navigation: login / register -> canvas list -> canvas -> editor; sign-out on any 401.
- Canvas list: list, create (title dialog), open, refresh, sign out.
- Canvas screen: web-identical card drawing in a Compose `Canvas`, culling, pan / pinch zoom, tap to
  edit, long-press selection mode, long-press-drag move, contextual bar (Merge ⇣ / ⇢, Delete, Rhymes, ✕),
  FAB new snippet, viewport persistence.
- List mode: a top-bar toggle that swaps the canvas for a `LazyColumn` of the same cards, last edited
  first, sharing tap-to-edit and the selection actions (see [list_mode.md](../features/list_mode.md)).
- Rhyme sheet: every feature of the web rhyme sidebar (see [rhyme-view.md](../frontend/rhyme-view.md)).

## Non-scope

- The grid editor screen (B2). Per-card resize (the web's corner handle), groups, tag editing, undo,
  t-SNE, search, export.
- Pull to refresh (a Refresh action is in the list's top bar instead).

## Screens

| Screen | Composable | ViewModel | Notes |
|---|---|---|---|
| (session) | `nav/AppNavHost` | `SessionViewModel` | Start destination from the token store; `SignedOut` at any time pops everything and shows login |
| Login / register | `auth/AuthScreen` | `AuthViewModel` | Mode toggle; display name only when registering |
| Canvas list | `canvaslist/CanvasListScreen` | `CanvasListViewModel` | FAB "New canvas" opens a title dialog; the created canvas opens at once |
| Canvas | `canvas/CanvasScreen` | `CanvasViewModel`, `RhymeViewModel` | Both ViewModels live in the canvas back-stack entry |
| Editor | `editor/GridEditorRoute` (B2) | (B2) | Reached via `EditorRoute(canvasId, snippetId)`; reads the snippet from, and writes the saved one back to, the canvas entry's `CanvasViewModel` |

ViewModels are built with `viewModel { ... }` initializers from `AppContainer` repositories (no DI
library). Everything they expose is a `StateFlow`; one-shot effects (open editor, snackbar text, open a
new canvas) go through a buffered `Channel` exposed as a `Flow`.

## Session and 401

```
DataStoreTokenStore.tokens -> SessionViewModel.state (Unknown | SignedOut | SignedIn)
AppNavHost: Unknown -> blank frame; SignedOut -> navigate(AuthRoute) popping the graph;
            SignedIn while on AuthRoute -> navigate(CanvasListRoute) popping the graph
login/register -> AuthRepository stores the token -> tokens emits -> SignedIn
any 401 -> ApiClient clears the token store -> SignedOut -> login
```

The ViewModel that got the 401 shows "Your session has expired. Sign in again." in its own state (the
screen is already being replaced). `AuthViewModel` treats a 401 as wrong credentials and shows the
server's detail.

## Canvas ViewModel

`CanvasViewModel(canvasId, canvases, snippets, persistScope, viewportSaveDelayMs = 1000, savedState)`

State: `CanvasUiState` = `Loading` | `Failed(message)` | `Ready(canvasId, title, snippets, viewport,
viewSize, selection, busy, mergeError, mode)`. `Selection` = `Idle` | `Selecting(ids)`; `Selecting` cannot be
empty (deselecting the last card leaves selection mode). `CanvasMode` = `Canvas` | `SnippetList(order)`
(the list order captured by `ListOrder.of`); `Ready.listRows` is `order.arrange(snippets)`. The mode's
kind is saved in the `SavedStateHandle` under `CANVAS_MODE_KEY`. All screen coordinates are dp.

| Call | Effect |
|---|---|
| `reload()` | `GET /api/canvases/{id}`; viewport from `viewport_state` via `Viewport.from` (zoom clamped); mode from the saved kind (a list is sorted afresh) |
| `setMode(kind)` | Saves the kind (also before load); `LIST` captures a new `ListOrder`, `CANVAS` drops it. Selection is kept |
| `setViewSize(size)` | Canvas element size (dp); used for the viewport centre |
| `panBy(delta)` | `Viewport.panBy`; schedules a viewport save |
| `transform(centroid, pan, zoom)` | `viewport.panBy(pan).scaleAbout(centroid, zoom)`: the content follows the fingers, then zooms about the current centroid (clamped 0.1–5×); schedules a save |
| `flushViewport()` | Save a pending viewport now (called on the screen's `ON_STOP`) |
| `onTap(hit)` | Idle: a card opens the editor (`CanvasEvent.OpenEditor`). Selecting: a card toggles; empty canvas does nothing |
| `onLongPress(id)` | Adds the card to the selection (entering selection mode) and records its position as a move origin |
| `onListLongPress(id)` | List row long-press: adds the row to the selection; records no move origin |
| `moveCardBy(id, delta)` | Only after `onLongPress(id)`: moves the card by `delta / zoom` world units, locally |
| `endMove(id)` | If the card moved: one `PATCH /api/snippets/batch-position` with that card; on failure the card returns to its origin and a snackbar shows the error |
| `exitSelection()` | Back to `Idle` (also clears `mergeError`); the system back gesture does the same |
| `merge(mode)` | `MergeRequest.of(selected, mode)`; null (fewer than two) => `MergeError.TooFewSnippets`, no request. Success: drop `deleted_ids`, append the merged snippet, select only it. Failure: nothing changes, `mergeError = toMergeError(e)` and a snackbar |
| `deleteSelected()` | `DELETE` each selected snippet in parallel; removes the ones the server deleted; the rest stay selected; first error to the snackbar (after a confirm dialog in the UI) |
| `createSnippetAtCenter()` | `POST` a freestyle snippet at `viewportCenter(viewport, viewSize)`, insert it, then `OpenEditor` |
| `replaceSnippet(s)` | The editor's `onSaved`: replace in place; in list mode, capture a new list order |
| `hitTest(screen)` | Topmost card under a dp point (`hitTestScreen`) |

Viewport persistence: every camera change cancels and restarts a 1 s timer; when it fires, or on
`flushViewport()`, the viewport is saved with `CanvasRepository.saveViewport` unless it equals the last
saved one. Saves run in `ProcessScope` (process lifetime), so a save started when the screen stops is not
cancelled with the ViewModel. A failed save forgets the "last saved" value so the next change retries.

`CanvasViewModel` implements `RhymeCanvasPort` for the rhyme sheet: `rhymeView` (selection, snippets,
camera), `currentRhymeView()`, `insertMaterialized(snippet)`.

## Gestures

`canvas/gesture/CanvasGestures.kt` (`Modifier.canvasGestures`) reads raw pointer events in
`awaitEachGesture`, converts positions to dp and feeds the pure reducer `reduceGesture` in
`GestureReducer.kt`; the reducer's actions go to the ViewModel.

| State | Input | Next | Action |
|---|---|---|---|
| Idle | Down(pos, hit) | Pressed | — |
| Pressed | Move within touch slop | Pressed | — |
| Pressed | Move past slop | Panning | Pan(pos − start) |
| Pressed | Long-press timeout, on a card | Dragging(card) | LongPress(card) (+ haptic) |
| Pressed | Long-press timeout, empty canvas | Pressed | — (a later drag still pans) |
| Pressed | Up | Idle | Tap(hit) |
| Pressed / Panning | Multi-finger frame | Transforming | Transform(centroid, pan, zoom) |
| Panning | Move | Panning | Pan(delta) |
| Dragging | Move | Dragging | MoveCard(card, delta) |
| Dragging | Up / Cancel | Idle | EndMove(card) |
| Dragging | Multi-finger frame | Dragging | — (ignored) |
| Transforming | Any frame (1 or more fingers) | Transforming | Transform |
| Panning / Transforming | Up / Cancel | Idle | — |

The long-press timeout is `withTimeoutOrNull` around `awaitPointerEvent` while `Pressed`
(`ViewConfiguration.longPressTimeoutMillis`); the slop is `ViewConfiguration.touchSlop`. A frame in which
a finger lands or lifts sends zero pan and zoom 1, because the centroid jumps. If the gesture coroutine is
cancelled mid-gesture, `Cancel` is fed so a card move still ends.

## Rendering

`SnippetCanvas` draws in one Compose `Canvas`:

1. Background `#12122a` and the web's 50-unit grid lines (`backgroundGridLines`, skipped when closer
   than 8 dp).
2. `visibleCards(snippets, viewport, viewSize)`: draw order (z-index, then list order), culled to cards
   whose screen rectangle grown by the 4 dp shadow overlaps the view.
3. For each card, `drawCard` (`render/CardRenderer.kt`) under `translate(origin) + scale(zoom × density)`,
   so everything is drawn in card-local world units from `cardLayout(snippet)`
   (`render/CardLayout.kt`): shadow, `#1c1c38` body, header in the snippet-type colour (85 % alpha,
   rounded top corners), white selection border (2.5 dp), tag pills right to left from the right padding
   (the web's 6-unit character estimate), grid stripes and lines (clamped to the inner right edge),
   header label, tag labels, cell text (stressed bold white, normal `#d0d0e8`, rests "·" `#555577`),
   per-line syllable counts, or the raw-text fallback (200 characters, wrapped) when there is no grid.

Text is measured with `rememberTextMeasurer(cacheSize = 512)` at fixed card-unit sizes
(`CardTextStyles`), so zooming does not miss the measurement cache; below a 4 dp cell font
(`textVisible`, zoom < ~0.31) only shapes are drawn. Card layouts are cached per snippet id and reused
while the grid instance, size, type, tags and text are unchanged (a moved card keeps its grid instance).
Strokes are expressed in dp (`1 / zoom` card units).

## Canvas screen layout

`CanvasScreen` = `BoxWithConstraints` around a `Scaffold`:

- Top bar: title, back, "List" / "Canvas" mode toggle (once loaded), "Rhymes" toggle; while selecting, `SelectionTopBar` (count, Merge ⇣, Merge ⇢
  (enabled with ≥ 2 selected and nothing in flight), Delete (confirm dialog), Rhymes, ✕).
- FAB "New snippet" while not selecting.
- Body: the canvas or, in list mode, `SnippetList` (rows are the canvas card resized for the row by
  `listCardWidth` / `listCardHeight`, tap = `onTap`, long-press = `onListLongPress`); a `Failed`
  message with Retry; or a progress indicator.
- Rhyme sheet: with `maxWidth >= 840.dp` a permanent 380 dp side pane right of the canvas (system back
  closes it); otherwise a `ModalBottomSheet` (dismissing it closes the session).
- Snackbars for `CanvasEvent.Message`; `CanvasEvent.OpenEditor` navigates.

## Rhyme sheet

`RhymeViewModel(port: RhymeCanvasPort, nlp, snippets, debounceMs = 150)`; state `RhymeUiState`
(the fields of the web store, plus `chips`, `rows`, `shown`, `canLoadMore`, `typedKey`, `manualError`
derived from it).

### Query pipeline

```
open() -> launch session:
  session state -> PlanInput(controls, canvasId, selectedIds, selected snippets) -> distinctUntilChanged
    -> planRhymeQuery(..., offset 0)                       (RhymeQueryPlan: Skip | Query)
    -> distinctUntilChanged(kind, request minus exclude_ids, pruned exclusions, pruned ranges)
    -> withIndex().debounce { first ? 0 : 150 ms }
  merge(that, manual ops: Refresh (IME search), LoadMore)
    -> mapLatest { execute }                               (a newer op cancels the running one)
execute(Run(plan)):
  Skip  -> store pruning; clear anchors/candidates/total/offset; no request
  Query -> same request as the last completed one ? store pruning only
           : store pruning, loading, slot; POST rhyme-suggest with exclude_ids = dismissed
             -> anchors, candidates minus dismissed, total, offset 0 | RhymeError(QUERY)
LoadMore -> offset + 40 with the current plan; append unseen, non-dismissed candidates
close()  -> cancel the session; keep k, min score, slot mode, manual slot, sources, rare words
```

Controls are typed text, `RhymeQueryParams`, exclusions and ranges; dismissals are not, so a dismissal
does not re-query (as on the web). Camera-only canvas changes are not mirrored into the sheet state (no
recomposition per pan frame); a materialize reads the camera from the port when it runs. A materialized
snippet is not selected, so it does not change the plan and does not re-query.

### Controls and chips

| Control | Call |
|---|---|
| Search box (IME action Search runs at once) | `setTypedText`, `searchNow` |
| Chip ▸ / ▾ | `toggleChipExpanded` |
| Chip × / + | `excludeAnchor` / `restoreAnchor` |
| Word tap on an expanded chip line | `onWordTap(id, line, word)` -> `clickWord` (start / end / clear) |
| "whole line" | `resetRange` |
| k 1–4 (filter chips) | `setK` |
| Min score slider (0–1, 0.05 steps) | `setMinScore` (clamped) |
| Slot radio, manual count + stress | `setSlotMode`, `setManualSlot` (validated by `parseManualSlot`; only a valid slot is sent) |
| notes / dictionary / rare words | `toggleSource`, `setIncludeRare` |
| Row tap / text tap | `toggleRow` / `setTypedText(candidate.text)` (chain search) |
| Materialize / Dismiss button / swipe | `materialize`, `dismiss` |
| More | `loadMore` |

`anchorChips(...)` (pure) builds one chip per selected non-blank snippet: label (picked range text, else
last non-empty line, 28 characters), "part" marker, echoed rhyme key, excluded / expanded / awaiting-end
flags, the snippet's lines for the syllable strips, and the hint text.

### Materialize

`materializePosition(anchorSnippets(selection, excluded), counts, camera)`: right of the single anchor
(+40, +60 per earlier materialization from it), else the viewport centre. `POST` the freestyle snippet
with `derived_from` (`materializeRequest`); on success `port.insertMaterialized` puts it on the canvas,
the anchor's count goes up, the row is removed and its id joins `dismissed`. Failure: the row stays and
the error line shows `Materialize failed (kind): message`. A row being materialized has its button
disabled.

## Files

Paths under `android/app/src/main/kotlin/dev/nymph/lyrics/app/`.

| File | Role / key exports |
|---|---|
| `MainActivity.kt` | `setContent { LyricsTheme { AppNavHost(container) } }` |
| `nav/Routes.kt` | `AuthRoute`, `CanvasListRoute`, `CanvasRoute(canvasId)`, `EditorRoute(canvasId, snippetId)` (type-safe, `@Serializable`) |
| `nav/AppNavHost.kt` | `AppNavHost(container)`: session-driven start / sign-out navigation; editor destination sharing the canvas entry's ViewModel |
| `di/ProcessScope.kt` | `ProcessScope.scope`: process-lifetime scope for saves that must outlive a screen |
| `session/SessionViewModel.kt` | `SessionState` (`Unknown`, `SignedOut`, `SignedIn`), `SessionViewModel` (`state`, `signOut`) |
| `auth/AuthViewModel.kt`, `auth/AuthScreen.kt` | `AuthMode`, `AuthFieldError`, `AuthUiState`, `AuthViewModel` (`setMode`, `setEmail`, `setPassword`, `setDisplayName`, `submit`); the form |
| `canvaslist/CanvasListViewModel.kt`, `canvaslist/CanvasListScreen.kt` | `CanvasListUiState`, `CanvasListEvent` (`Open`, `Message`), `CanvasListViewModel` (`refresh`, `create`); the list |
| `canvas/CanvasUiState.kt` | `Selection` (`Idle`, `Selecting`, `of`), `Selection.ids`, `CanvasModeKind`, `CanvasMode` (`Canvas`, `SnippetList`), `CanvasUiState` (`Loading`, `Failed`, `Ready` with `listRows`), `CanvasEvent` (`OpenEditor`, `Message`) |
| `canvas/CanvasViewModel.kt` | `CanvasViewModel`, `VIEWPORT_SAVE_DELAY_MS`, `CANVAS_MODE_KEY` |
| `canvas/CanvasScreen.kt` | `CanvasScreen`, `canvasViewModel(container, canvasId, owner)` |
| `canvas/CanvasTopBars.kt` | `CanvasTopBar`, `SelectionTopBar` |
| `canvas/SnippetCanvas.kt` | `SnippetCanvas` (draw loop, layout cache, size reporting, gestures) |
| `canvas/list/ListCardSize.kt` | `ListCardMetrics`, `ListCardWidth`, `listCardNeededWidth`, `listCardWidth`, `listCardHeight`, `listCard` (pure) |
| `canvas/list/SnippetList.kt` | `SnippetList` (list mode `LazyColumn`; rows drawn with `cardLayout` + `drawCard`) |
| `canvas/render/CardLayout.kt` | `CardMetrics`, `CellTextKind`, `CellText`, `CountText`, `TagPill`, `GridFrame`, `CardLayout`, `placeCellText`, `cardLayout`, `textVisible` (pure) |
| `canvas/render/Culling.kt` | `intersects`, `visibleCards`, `BackgroundGrid`, `backgroundGridLines` (pure) |
| `canvas/render/CardRenderer.kt` | `CardTextStyles`, `DrawScope.drawCard` |
| `canvas/gesture/GestureReducer.kt` | `GestureState`, `GestureInput`, `CanvasGestureAction`, `GestureStep`, `reduceGesture` (pure) |
| `canvas/gesture/CanvasGestures.kt` | `Modifier.canvasGestures(hitTest, onAction)` |
| `rhyme/RhymeCanvasPort.kt` | `RhymeCanvasView` (`selectedSnippets`, `withoutCamera`), `RhymeCanvasPort` |
| `rhyme/RhymeUiState.kt` | `RhymeControls`, `RhymeUiState` |
| `rhyme/AnchorChips.kt` | `AnchorChip`, `CHIP_TEXT_MAX`, `truncateChip`, `chipLabel`, `chipHint`, `anchorChips` (pure) |
| `rhyme/RhymeViewModel.kt` | `RhymeViewModel`, `RHYME_QUERY_DEBOUNCE_MS` |
| `rhyme/ui/RhymePanel.kt` | `RhymePanel`, `statusText`, `errorText` |
| `rhyme/ui/QueryControls.kt` | `QueryControls` |
| `rhyme/ui/AnchorChipsView.kt` | `AnchorChipsView` |
| `rhyme/ui/CandidateRow.kt` | `CandidateRow` |
| `rhyme/ui/SyllableStrip.kt` | `SyllableStrip(line, selected, onWordTap)` |
| `rhyme/ui/AlignmentTable.kt` | `AlignmentTable`, `syllableText` |
| `ui/UserMessages.kt` | `ApiError.userMessage()`, `SESSION_EXPIRED`, `parseHexColor` |
| `ui/theme/Palette.kt`, `ui/theme/LyricsTheme.kt` | Web palette, snippet-type and provenance colours, alignment colour; dark Material 3 scheme |

Tests (`android/app/src/test/...`, JUnit 5 + kotlinx-coroutines-test + Turbine, fake repositories in
`fakes/`): `CanvasViewModelTest`, `CanvasViewModelListModeTest`, `ListCardSizeTest`, `RhymeViewModelTest`, `SessionViewModelTest`, `AuthViewModelTest`,
`CanvasListViewModelTest`, `GestureReducerTest`, `CardLayoutTest`, `CullingTest`, `UserMessagesTest`.
`MainDispatcherExtension` installs a `StandardTestDispatcher` as `Dispatchers.Main`.

## Invariants

- Nothing from the rhyme sheet is drawn until the server confirms the materialized snippet.
- A card move sends at most one batch-position request, on release, and only if the card moved.
- `Selection.Selecting` is never empty; selection-mode UI shows exactly while it is `Selecting`.
- List mode and the canvas share one `Selection`; list rows only reorder when a new `ListOrder` is
  captured (list shown, reload, editor save).
- A merge that fails leaves snippets and selection unchanged.
- At most one rhyme query result is applied per plan; an older in-flight query never overwrites a newer one
  (`mapLatest` cancels it, and with it the HTTP call).
- ViewModels never throw; every `ApiError` becomes state or a snackbar message.
