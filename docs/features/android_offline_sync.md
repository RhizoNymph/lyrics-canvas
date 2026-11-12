# Android Offline Copy and Sync

The Android app keeps a local copy of the signed-in user's data and works from it, with the server
reachable or not. Every offline-capable change is saved on the phone first and queued. The queue
syncs as soon as the server is reachable. A change that conflicts with the server's copy is parked:
the user is alerted, the entity is marked "Not synced", and a review screen shows mine next to the
server's.

The server side (versions, `base_version`, client-chosen ids, `GET /api/sync/snapshot`) is on
`feat/sync-versions-backend`; see [sync_versions.md](./sync_versions.md).

## Scope

- **Local-first, always.** Screens open from the copy and refresh from the server in the background.
  Offline and online behave the same.
- **Offline-capable changes:**
  - Canvases: create, rename, delete. Delete is new on Android: it is in the canvas list's ⋮ menu,
    with a confirmation.
  - Snippets: create, edit (text, type, grid, grid mode, beats), move, remove from this canvas,
    delete everywhere.
  - Tags: link and unlink; create, rename, recolour and delete tags.
  - Word bank: add, rename, delete and tag words.
  - Merge, plus Move to / Also show on another canvas.
  - Viewport saves.
- **Search runs on the copy**, always: search across canvases (same matching rule, orders, limits
  and excerpts as `GET /api/search`) and the in-canvas filter.
- **Conflicts** are content conflicts only, parked and reviewed: Keep server's, Overwrite with mine,
  Save mine as new (snippets only), and Retry merge (merges only).
- **UI:**
  - a sync status indicator;
  - "Not synced" badges;
  - a conflict snackbar with Review;
  - the Not-synced list and the review screen;
  - "Needs connection" on server-only actions.

## Non-scope

- **Server-only features.** These keep their direct calls and answer `ApiError.NeedsConnection`
  while the server is down:
  - rhyme suggestions and syllabify;
  - word bank rhymes, families and related words;
  - voice takes, recordings, check transcript and the voice dataset;
  - saving tag presets. Presets are read from the copy.
  - Similarity, share/fork and groups have no Android UI.
- Background sync with no app process (no WorkManager): the engine runs while the process lives.
- A delta feed: the snapshot is the whole account (fine at this data size).
- `is_public` changes (the app never sends them; they are ignored by the local canvas repository).
- Server-side preset rewriting on a tag rename shows after the next snapshot.

## Storage choice: file-backed JSON (kotlinx.serialization), not Room

Room 2.8.4 with KSP 2.3.12 was tried first in a scratch copy of the project, and rejected.

- **The processor's graph did not resolve `--offline`.** Room's compiler asks for
  `symbol-processing-api:2.0.10-1.0.24`, `kotlin-stdlib:2.2.0`, `guava:33.2.1-jre` and
  `kotlin-metadata-jvm:2.2.0`. None of these are in the cache.
- **Forcing versions did build, but with versions Room is not tested against.** Forcing the cached
  versions (KSP API 2.3.12, stdlib 2.4.20, Guava 33.4.0-jre, metadata 2.3.21) on the KSP classpath
  made `:app:assembleDebug` pass. That is not a clean build.
- **Room's runtime is Android-only in the cache.** There is no `room-runtime-jvm` and no bundled
  SQLite JVM driver, so the store could not be unit-tested on the JVM, and the tests here are
  JVM-only.
- **The data fits a file.** It is one account's snapshot plus an outbox, read whole and written
  whole. It needs no queries.

The store is one JSON file, `filesDir/offline/store.json`, encoded with `StoreJson`. That is
`LyricsJson` plus a `kind` class discriminator. Each write goes to `store.json.tmp`, which is then
renamed over the file (`ATOMIC_MOVE`). No new dependency was added: kotlinx.serialization 1.9.0 is
already pinned.

## Modules and files

`:core:sync` is a new Kotlin/JVM module (package `dev.nymph.lyrics.sync`). It depends on `:core:data`
(for `ApiClient`) and `:core:logic` (search rule, rest marks, local syllabify, canvas ref order).

| File | Role / key exports |
|---|---|
| `outbox/EntityRef.kt` | `EntityRef` = `Snippet` \| `Canvas` \| `Tag` \| `Word`: the unit of ordering, conflicts and badges. A snippet's placements belong to it. |
| `outbox/Patches.kt` | `ContentPatch`, `LayoutPatch`. Each is never empty (built by `of`) and merges with `then`. Text sent without a grid drops an earlier grid. |
| `outbox/SyncOp.kt` | Sealed `SyncOp`, one class per change kind. Each says what it `touches`, `creates`, `references` and `deletes`, and its `bases` (version per entity). It also has `rebased` and `remapped`. `IdRemap` and `SnippetVersion` live here too. |
| `outbox/OutboxEntry.kt` | `OpId`, `OpState` (`Pending`, `InFlight`, `Failed(attempts, reason)`, `Parked(conflict)`), `OutboxEntry` |
| `outbox/Outbox.kt` | The immutable `Outbox`: `enqueue` with coalescing, `nextBatch` (ordering plus word batching), `markInFlight`, `complete`, `fail`, `requeue`, `park`, `rebase`, `remap`, `insertAfter`, `recovered`. Also `waitsFor`, `Batch` and `MAX_WORD_BATCH`. |
| `store/LocalData.kt` | The persisted copy: `LocalData`, `LocalSnippet` (shared content, tags by id), `LocalPlacement`, `LocalTag` (no counts), `LocalWord`. Also the upsert and cascade helpers (`withoutCanvas`, `withoutTag`, ...). |
| `store/Views.kt` | `Views`: API-shaped reads (`canvasList`, `canvasDetail`, `snippet`, `tagList` with local counts, `presets`, `words`) in the server's orders |
| `store/LocalSearch.kt` | `LocalSearch.search` / `excerpt`: `GET /api/search` on the copy (`EXCERPT_MAX` 160, `EXCERPT_LEAD` 40, in code points) |
| `store/StoreFile.kt` | `StoreFile`, `JsonStoreFile` (atomic write; an unreadable file is set aside as `store.json.corrupt-<ms>`), `InMemoryStoreFile`, `StoreRead`, `StoreWriteError`, `StoreJson` |
| `store/LocalStore.kt` | `LocalStore`: the single owner. It exposes `data: StateFlow<LocalData>`. `update` runs under a mutex and writes before publishing. Also `ensureLoaded` (in-flight goes back to pending), `claim(account)` and `wipe`. |
| `apply/LocalEdits.kt` | `LocalEdits(now, newId)`: every offline change applied the way the server would apply it, with its operation enqueued. It answers `Edited(data, ApiResult)`, with the server's failure codes (`tag_name_taken`, `word_exists`, `canvas_not_found`, `same_canvas`, ...). |
| `apply/LocalMerge.kt` | `LocalMerge`: a port of `backend/app/services/merge_service.py`, so a merge shows offline |
| `snapshot/SnapshotMerge.kt` | `SnapshotMerge.merge(local, snapshot)` |
| `engine/SendOutcome.kt` | `OpResult` (successes by kind, including `TagMatched`) and `SendOutcome` (`Ok`, `VersionConflict(current)`, `NotFound(code)`, `Refused`, `Transient`, `Unreadable`) |
| `engine/OpSender.kt` | `OpSender` and `HttpOpSender` (each op as its API call), plus `classify` |
| `engine/Outcomes.kt` | `Outcomes.apply(data, batch, outcome) -> Applied(data, alerts, refresh, transient)`, and the `absorb*` and `remapTag` helpers |
| `engine/SyncEngine.kt` | `SyncEngine` (`run`, `wake`, `alerts`, `activity`, `snapshotAttempts`), `Wake`, `SyncPolicy`, `EngineActivity`, `SnapshotAttempt`, `SyncTrigger` |
| `conflict/Conflict.kt` | `ServerCopy`, `Conflict` (`Changed`, `DeletedOnServer`, `MergeChanged`, `Rejected`), `Resolution`, `resolutionsFor` |
| `conflict/Resolver.kt` | `Resolver(newId, now).resolve(data, opId, resolution) -> Resolved` (`Done(data, refresh)` or `Refused(ResolveError)`), `SAVE_AS_NEW_GAP` |
| `conflict/ConflictAlert.kt` | `ConflictAlert` (with `message`), `reason`, `labelOf` |
| `conflict/ReviewItem.kt` | `ReviewItem`, `ReviewSide`, `EntityKind`, `reviewItems` / `reviewItem`, `lineDiff` |
| `status/SyncStatus.kt` | `SyncStatus` (`Synced`, `Syncing`, `Offline(pending)`, `NotSynced(count, pending)`, with `label`), `syncStatus`, `notSyncedEntities` |
| `repo/LocalFirst.kt` | `LocalFirst` (`edit`, `read`, which waits up to 30 s for a first snapshot, and `awaitSettled`), `OnlineGate` |
| `repo/LocalCanvasRepository.kt`, `LocalSnippetRepository.kt`, `LocalTagRepositories.kt` (`LocalTagRepository`, `CachedTagPresetRepository`), `LocalWordBankRepository.kt` | The existing repository interfaces, now local-first |
| `repo/ServerOnlyRepositories.kt` | `LocalSearchRepository`; `GatedNlpRepository`; `GatedVoiceRepository` (waits for the snippet's queue before a take and absorbs the returned snippet; `NOT_SYNCED_YET`); `SyncAuthRepository` (sign-in claims the copy, sign-out wipes it) |
| `SyncCenter.kt` | What the screens read: `status`, `notSynced`, `reviewItems`, `reviewItem(opId)`, `unconfirmed`, `alerts`, `online`, plus `resolve` and `syncNow` |
| `SyncLog.kt` | `SyncLog` (structured `event` plus key-value fields), `LogLevel`, `debug` / `info` / `warn` / `error` |

Changes outside the new module:

- **`:core:model`**
  - `version: Int = 1` on `Canvas`, `CanvasDetail`, `Snippet`, `UserTag` and `BankWord`.
  - Optional `base_version` / `base_versions` and client `id` / `merged_id` on the requests.
  - `NewBankWord` and `WordBankSyncAddRequest` (word adds as `{text, id}`).
  - `TagLinkRequest`; `TagLinkResponse.version`.
  - `Sync.kt`: `SyncSnapshot`, `SnapshotPlacement`, `SnapshotSnippet`, `SyncErrorCode`.
  - `ApiError.NeedsConnection`, and `ApiError.Server.current` (`detail.current` of a 409).
- **`:core:data`**
  - `ApiClient.delete(path, query)`.
  - `observe*` methods with empty defaults on `CanvasRepository`, `TagRepository`,
    `TagPresetRepository` and `WordBankRepository`.
  - `CanvasRepository.delete`.
- **`:core:logic`**: every exhaustive `ApiError` mapping handles `NeedsConnection` as a network
  failure.
- **`:app`**
  - `app/sync/SyncGraph.kt`: builds the store, engine, gate and center. It holds `LogcatSyncLog`
    and the pure `isOnline` and `cameBack`.
  - `app/sync/SyncUi.kt`: `SyncUi`, `LocalSyncUi`, `SyncStatusChip`, `NotSyncedBadge`,
    `NeedsConnectionButton`, `NeedsConnectionNote`.
  - `app/sync/NotSyncedScreens.kt`: `NotSyncedScreen` and `ReviewScreen`.
  - `app/sync/ReviewText.kt`: button labels and summaries.
  - `AppContainer` and `LyricsApp`, which starts the engine.
  - `MainActivity` (foreground).
  - `AppNavHost`: provides `LocalSyncUi`, holds the conflict snackbar, and adds `NotSyncedRoute`
    and `ReviewRoute`.
  - The VMs that follow the copy: `CanvasViewModel.followCopy`, `CanvasListViewModel` (also
    `delete`), `TagControlsViewModel` and `WordBankViewModel`.
  - Badges in `CardRenderer`, `SnippetList`, the canvas list rows, `TagPickerSheet` and `WordsTab`.
  - "Needs connection" in `CanvasTopBars`, `GridEditorRoute`, `RecordingsSection`,
    `WordBankScreen`, `PresetMenu` and the canvas list's Voice dataset item.

## Data model

`LocalData` holds:

- `account`;
- `canvases` (the API `Canvas`, version included);
- `snippets` (content once per snippet);
- `placements` (one per snippet and canvas);
- `tags`, `presets`, `words`;
- `outbox`;
- `snapshotAt` (the last snapshot's `server_time`, null before the first one).

The views assemble `CanvasDetail` and `Snippet` exactly as the server would, with `canvases` refs
sorted by lowercase title then id, tags embedded and sorted by name, and tag counts computed from
the local links. That is why the ViewModels work unchanged on the copy.

**Versions.** Each entity keeps the last server version known.

- A content operation records that version as its `base_version` when it is queued.
- When an operation **based on** an entity succeeds (or one that created it), every operation still
  queued with a base for that entity is rebased to the answer's version, because they were built on
  top of it.
- A layout answer never rebases. Its version may include another device's content change, and a
  rebase would hide that conflict.

## Outbox state machine

```
            enqueue (coalesced into a Pending/Parked edit of the same entity, or appended)
                 v
             Pending ──nextBatch──> InFlight ──2xx, or 404 on a delete/remove──> removed
                ^                     │ ├──network / 5xx / 401 / 408 / 429──> Failed(n) ──backoff or a refreshing wake──> InFlight
                │                     │ ├──409 version_conflict / 404 on an edit / other 4xx──> Parked(conflict)
                │                     │ └──a 2xx it cannot read──> removed (and a snapshot is fetched)
                └──process restart (InFlight) / a word batch refused for another word's sake
             Parked ──Keep server's──> removed (with dependants) | Overwrite / Retry merge──> Pending (rebased) | Save mine as new──> removed + a create
```

**Coalescing.** Only Pending entries are rewritten; Parked entries are rewritten for content edits
only, and InFlight and Failed entries never, since the server may already have applied them.

- Content edits of one snippet, a canvas title, a tag (field by field) or a word text merge into
  the latest edit of the same entity, which keeps the first base version. Layout operations on the
  same entity are skipped over.
- Layout updates per (snippet, canvas) and a canvas viewport keep the last value. Content
  operations on the same entity are skipped over.
- A create, delete, remove, tag link, merge or transfer on the entity stops the search, so nothing
  coalesces across it.

**Ordering.** `waitsFor` decides when a later operation must wait for an earlier one:

- they change the same entity (FIFO per entity, so everything on an entity waits behind its parked
  conflict);
- the later one names something the earlier one creates (a create runs first);
- the later one deletes something the earlier one names.

Operations on other entities pass a parked one. A canvas-title conflict does not block snippet creates
on that canvas.

**Batches.** Consecutive eligible word operations of one kind go as one bulk request (at most 500):

- adds with the same tags;
- deletes;
- link or unlink of the same tag.

## Sync flow

```
edit -> repository -> store.update { LocalEdits.x(...) (copy + enqueue) } -> answer at once -> engine.wake(EDIT)
SyncEngine.run() (ProcessScope, for the process lifetime)
  wake-ups (Channel<Wake>): EDIT, FOREGROUND (MainActivity STARTED), HEALTHY (health monitor came back),
                            REFRESH (sign-in, resolved conflict, refresh button), TICK (30 s while foreground and unsent > 0)
  pass: loop { batch = outbox.nextBatch() (marked InFlight) -> OpSender.send -> store.update { Outcomes.apply } }
        Transient -> stop; backoff 2 s doubling to 60 s (only FOREGROUND / HEALTHY / REFRESH end it early)
        parked -> ConflictAlert on engine.alerts (once) -> snackbar "Change to "…" isn't synced: …" [Review]
  after a drain that sent something, or on FOREGROUND / HEALTHY / REFRESH, or with no snapshot yet:
        (no account yet -> GET /api/auth/me -> store.claim) GET /api/sync/snapshot -> SnapshotMerge.merge
reads: repository -> LocalFirst.read (fresh install: awaitSnapshot, up to 30 s) -> Views.*
ViewModels: observe*() flows of the store -> list / canvas / tags / word bank follow every change
```

**Snapshot merge.** The server wins for every entity that no queued or parked operation touches.
An entity an operation touches keeps its local copy, or its local absence for a pending delete.
That covers pending creates and parked conflicts. Everything else that is missing from the
snapshot goes locally. A placement needs its canvas and its snippet, and a server snippet with no
placement left goes.

**Results by answer:**

| Answer | Effect |
|---|---|
| 2xx | The response replaces the local copy when nothing else is queued on the entity. Otherwise only the version moves, for based or creating operations. A create's answer never re-creates something deleted locally meanwhile. |
| 409 `version_conflict` | Parked as `Changed` with `current` decoded per entity. A merge parks as `MergeChanged`, with `current` a list. A word batch parks only the words in `current` and sends the rest again. When `current` already equals the change (a retry whose answer was lost), it counts as a success. An unreadable `current` parks as `Rejected`. |
| 404 on a delete, remove or unlink | Done. |
| 404 on an edit or move | Parked as `DeletedOnServer`. |
| 404 `tag_not_found` on a link | The tag is gone: the link is dropped with the tag. |
| 404 on a merge | `MergeChanged(missing)`. |
| 404 on a transfer or a snippet create | `Rejected`. |
| 409 `tag_name_taken` on a tag create | `GET /api/tags`; the same name becomes the server's tag (`TagMatched`: ids remapped in the copy and the queue), otherwise `Rejected`. |
| 409 `id_conflict` on a create | It already happened (its answer was lost, it was edited since): done, and a snapshot is fetched. |
| Word add, text already banked under another id | That word takes the server's id everywhere (`existing` in the response). |
| Other 4xx | Parked as `Rejected` with the server's message. |

## Conflict UX

**Status indicator.** It shows Synced, Syncing, Offline (N pending) or N not synced, in the canvas
list and canvas top bars and on the Not-synced list. Tapping it opens the Not-synced list.

**"Not synced" badges** appear on:

- snippet cards (a red outline with the label under the card) and list-mode rows;
- canvas list rows (a parked title);
- tag picker rows;
- word bank rows.

**Snackbar.** It is app-wide and shows once per conflict: `Change to "<first line / title / name>"
isn't synced: it was also changed on the server`. Its Review action opens the review screen.

**Review screen** (`ReviewRoute(opId)`):

- Mine and the server's, side by side. A snippet shows its full text with the lines the other side
  lacks highlighted, then its type and tags.
- The buttons follow `resolutionsFor`:

  | Conflict | Choices |
  |---|---|
  | Snippet changed | Keep server's, Overwrite with mine, Save mine as new |
  | Canvas title, tag or word changed | Keep server's, Overwrite with mine |
  | Snippet deleted on the server | Keep server's (accept the deletion), Save mine as new |
  | Canvas, tag or word deleted on the server | Keep server's |
  | Merge | Keep server's (drop the merge), Retry merge (not offered when an original is missing) |
  | Rejected | Discard mine |

- The screen closes once the change is resolved.

**What each choice does:**

- **Keep server's** applies the server's copy, or restores it when my change was a delete. It drops
  the parked operation and every later content operation on the entity, but keeps later layout
  changes. On a deletion (or a dropped create) every operation on the entity goes, and so does
  everything that depended on the dropped create. A snapshot then shows the server's state.
- **Overwrite with mine** resends my whole local version, based on the server's version. For a
  snippet that is a full content PUT, plus tag link/unlink differences against the server's tags.
  A delete is resent based on the current version.
- **Save mine as new** (snippets) creates a new snippet on the same canvas, 40 units to the right
  of the original, with my content and tags. The original then takes the server's copy.
- **Retry merge** rebases the changed originals' versions and sends the merge again.

**Offline actions.** Server-only actions are disabled and say "Needs connection", and the health
banner keeps working as before. Affected:

- Record, on the canvas and in the editor;
- Rhymes, on the top bar and the selection bar;
- the Recordings section;
- the word bank's Rhymes and Related tabs;
- the preset save / rename / overwrite / delete items;
- the Voice dataset item.

**Sign-out** asks first when changes have not reached the server ("Signing out deletes them from
this phone").

## Invariants and constraints

- **A local edit is never lost silently.** It is synced, parked visibly, or discarded by the user's
  explicit choice. The exception is sign-out, which warns first.
- **Writes are durable before they show.** Each store write is atomic and happens before the copy
  shows it.
- **At most one parked conflict per entity.** Later edits of a parked entity merge into the parked
  change (content) or wait behind it.
- **Versions only rebase from based or creating operations.** A layout answer never rebases.
- **Retries never duplicate.** Creates carry client UUIDs, so a retry is safe. An in-flight
  operation from a killed process is sent again.
- **One coroutine talks to the server for the outbox.** All state is in the store (mutex-guarded
  updates, published as immutable values). Wake-ups arrive over a channel. There are no threads.
- **Errors are typed.** `ApiResult` and `ApiError` (with `NeedsConnection`), `SendOutcome`,
  `Conflict`, `ResolveError`, `StoreRead` and `StoreWriteError`. Only `SerializationException`,
  `IllegalArgumentException` and `IOException` are caught, at the decode and file boundaries.
- **Sign-out deletes the copy, and so does an account switch.** A 401 only signs out; the copy and
  its queue stay for the same account.
- **Reachability is the health monitor's.** Online means not `HealthState.Down`. The engine is woken
  when the monitor turns Healthy or BackOnline from any other state.
- **Logging is structured.** Logs go to logcat, tag `Sync`, as `event=sync.<name> key=value`. Events:
  - `store_loaded`, `store_corrupt`, `store_write_failed`, `store_account_switch`, `store_wiped`;
  - `op_sent`, `conflict_parked`, `backoff`;
  - `snapshot_merged`, `snapshot_failed`.

## Decisions and resolved ambiguities

1. **Room was not used** (see Storage choice).
2. **`version` defaults to 1 on decode.** That keeps old fixtures and the existing tests valid; the
   server always sends it.
3. **Permanent refusals are parked too.** The contract lists only conflict and transient states.
   But dropping a refused change would lose it silently, so 422s, a transfer whose canvas is gone
   and the like park as `Rejected`, with "Discard mine".
4. **Snapshot keep rule.** Entities with any queued or parked operation keep their local copy, not
   only pending creates, so a parked change's local version stays reviewable.
5. **Deleting a canvas was added to the Android canvas list.** The contract lists it as
   offline-capable, and Android had no canvas delete.
6. **Voice takes wait for the snippet's (and its canvas's) queued changes to sync** (up to 15 s,
   then `NOT_SYNCED_YET`). The take appends to the server's copy, so an unsynced edit would
   otherwise conflict with the user's own take.
7. **Word operations are queued per word and batched when sent**, so a bulk conflict parks only the
   conflicting words.
8. **A layout PUT is used for moves** (`PUT /api/snippets/{id}?canvas_id=`, layout fields only), not
   `batch-position`, so each move gets its placement back.
9. **No snapshot after a pass that only parked or failed.** After a drain it is fetched only when
   something was sent, the wake was a refresh, or there is no snapshot yet.

## Tests

- **`:core:sync`**, pure JVM:
  - `OutboxTest`: coalescing, ordering, batching, rebase, remap.
  - `LocalEditsTest`, `LocalMergeTest`: the backend's merge rules.
  - `ViewsAndSearchTest`, `SnapshotMergeTest`.
  - `OutcomesTest`: success, rebase rules, 409, the self-conflict check, 404, remaps, `id_conflict`.
  - `ResolverTest`: every resolution and the review items.
  - `LocalStoreTest`: file round trip, corrupt file, claim, wipe.
  - `SyncEngineTest`: virtual time, covering order, backoff, tick, alerts, cancellation and the
    first snapshot.
  - `LocalRepositoriesTest`: local-first writes, the gate, presets, auth, settle.
  - `HttpSyncTest`: MockWebServer end to end for every operation kind, including 409 and 404.
- **`:core:model`**: `SyncDtoTest`.
- **`:app`**: `OfflineAppTest`. ViewModels follow the copy, a dragged card keeps its position, a
  canvas deleted underneath shows as gone, the list deletes; plus reachability and the review
  wording.
