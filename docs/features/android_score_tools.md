# Android Score Tools: drafts, export, "Fit rhythm"

Musical mode gives a snippet a rhythm score: its syllables placed on a one-line staff
([score_format.md](./score_format.md), normative spec in `spec/score_format.md`). This branch
(`feat/android-score-tools`) adds the Android parts that need the server:

- **Draft sheet**: ask the server for a draft score, from the grid or from a recorded take.
- **Export**: the snippet's score as MIDI or MusicXML, to the share sheet or saved as a file.
- **"Fit rhythm"** on the rhyme sheet: rank candidates by how their stress fits the rhythm of the
  snippet anchor's placed syllables.

The staff editor, the offline score fields and the Kotlin algorithm port belong to
`feat/android-score-editor`. That branch mounts this one's sheet and menu (see Hooks).

## Scope

- **API calls** (round-4 contract section 5):
  - `POST /api/snippets/{id}/score/draft`, with a grid body `{source: "grid", note, meter, bpm}`
    or a recording body `{source: "recording", recording_id, meter, bpm}`. A recording body always
    sends `bpm`; `null` means "estimate it".
  - `GET /api/snippets/{id}/score/export?format=midi|musicxml`, a file download. The server's
    `Content-Disposition` gives the file name.
  - Typed errors (`ScoreToolErrorCode` / `ScoreToolErrorKind`): `score_not_found`,
    `recording_not_found`, `recording_has_no_timings`, `draft_empty`, `snippet_not_found` and
    `invalid_score`. Also mapped: Needs connection, Not synced yet, network, server, auth and
    unknown failures.
- **Rhyme wire additions** (contract section 6):
  - The request gains `rhythm_fit`.
  - The response gains `rhythm_applied` and `rhythm_unavailable_reason` (`no_score` /
    `slot_unplaced`; an unknown code reads as `OTHER`).
  - Each candidate gains `rhythm_fit`.
- **`ScoreDraftSheet`** is a modal bottom sheet with two sources:
  - "From grid": note length (1/8, 1/16, 1/32, 1/8 triplet, 1/16 triplet), meter and tempo.
  - "From a recording": pick a take on the snippet (newest take with word timings by default;
    takes without timings are listed but disabled), meter, and an optional tempo (blank = estimate).
  - Meters are typed or picked from presets (4/4, 3/4, 6/8, 2/4, 5/4, 7/8). Inputs are checked on
    the device (beats 1..32, beat note 1/2/4/8/16/32, tempo 20..400 with at most two decimals).
  - "Make draft" shows the result: the summary (estimated tempo, syllables unplaced, words not
    matched), meter, tempo, note and line counts, and "Nothing is saved until you use it".
  - "Use this draft" hands the `Score` to `onDraft`.
  - The sheet is online only. Offline it shows a message pointing to the editor's on-device
    "From grid".
- **`ScoreExportMenu`** is an "Export" top-bar menu with four items: Share MIDI, Save MIDI file…,
  Share MusicXML and Save MusicXML file…. Share uses a FileProvider URI with the chooser. Save uses
  the system "save as" picker (SAF `ACTION_CREATE_DOCUMENT`) and copies the file there. The menu is
  disabled offline ("Needs connection") or when the snippet has no score. Failures and "Saved
  <name>" go to the host's snackbar.
- **"Fit rhythm" switch** on the rhyme sheet:
  - It sits under the order chips and is remembered per device (DataStore `rhyme_order`, key
    `rhyme_rhythm_fit`, default off).
  - It is shown only while a snippet chip anchors the query.
  - A change refetches from the first page.
  - It works with both orders and with paging.
  - When it is applied, rows are banded (like meaning order) and each row shows a quiet "♩NN%".
  - When it is unavailable, a note under the switch gives the reason.

## Non-scope

- Drafting from the grid offline, the staff editor, playback, the offline score fields, "Remove
  score" and the Kotlin algorithm port. These belong to `feat/android-score-editor`.
- Server logic: draft alignment, quantization, tempo estimation, MIDI / MusicXML writing and the
  rhythm-fit score. These belong to the backend branches.
- Rhythm fit for pattern columns and word bank rhymes (contract non-scope).
- Saving a draft. The sheet returns a `Score`, and the editor's save path stores it.

## Data and control flow

```
Draft
  ScoreDraftSheet(container, snippetId, onDraft, onDismiss)
    -> ScoreDraftViewModel(snippetId, container.scoreTools, container.voice)
       form edits -> DraftForm (switchSource / withRecordings); any edit cancels an in-flight draft
                     and drops a shown one (phase = Editing)
       source RECORDING (first time) -> voice.recordings(snippetId) -> DraftTakes.Loaded -> default take
       state.check = form.check(takes) -> Ready(ScoreDraftRequest) | Invalid(DraftFormProblem)
       draft() -> scoreTools.draft(snippetId, request)
          GatedScoreToolsRepository: OnlineGate (NeedsConnection offline)
            -> LocalFirst.awaitSettled({Snippet(id)})  (false -> SCORE_NOT_SYNCED_YET, code not_synced)
            -> HttpScoreToolsRepository: POST /api/snippets/{id}/score/draft  body = request.toJson()
          Success -> DraftPhase.Ready(draft, draftSummary(draft)); Failure -> Failed(toScoreToolError)
       accept() -> Score -> onDraft(score)  (the host closes the sheet and saves it)

Export
  ScoreExportMenu(container, snippetId, hasScore, onMessage)
    -> ScoreExportViewModel(snippetId, container.scoreTools, container.scoreExportsDir)
       export(format, action): <cache>/score-exports/<snippet id>/ emptied, then
         scoreTools.export(...)  (same gate + settle) -> ApiClient.downloadAttachment(path, [format], target)
           (.part file then rename; Content-Disposition -> DownloadedFile.suggestedName)
         -> rename to exportFileName(suggested, format, "snippet-<id8>") -> ready (Channel) ExportedFile
       Failure -> ExportPhase.Failed -> snackbar, dismissError()
    menu collects ready:
       SHARE -> shareExport: FileProvider(<pkg>.scoreexports) URI, ACTION_SEND chooser, read grant
       SAVE  -> CreateTypedDocument(name, mime) picker -> saveExport: copy into the picked Uri (IO)
       (the pending file survives activity recreation through rememberSaveable)

Fit rhythm
  DataStore rhyme_rhythm_fit -> RhythmFitStore.enabled -> RhymeViewModel.applyRhythmFit -> params.rhythmFit
  RhythmFitToggle -> vm.setRhythmFit(on): apply at once, then RhythmFitStore.save(on)
  planRhymeQuery: request.rhythmFit = true only when params.rhythmFit and a snippet anchor remains
     (otherwise null, omitted: a typed-only query is the same query with the switch on or off)
  first page -> ResultOrdering(asked, applied, rhythm = rhythmOutcome(asked, rhythm_applied, reason))
     presentation BANDED when rhythm Applied (or meaning applied); loadMore keeps the plan's rhythm_fit
     and the first page's ordering
  RhymePanel: band headers; "Meaning order unavailable" from ordering.meaningUnavailable (also
     shown when banded by rhythm); CandidateRow: SimilarityLabel + RhythmFitLabel (♩NN%)
  QueryControls: RhythmFitToggle shown when state.rhythmToggleShown; note = state.rhythmNote
```

## Files and key exports

| File | Role |
|------|------|
| `android/core/model/.../model/score/ScoreTools.kt` | `DraftMeter`, `ScoreDraftRequest` (`FromGrid` / `FromRecording`, `toJson()`), `ScoreDraftResponse`, `ScoreExportFormat` (wire, extension, mime, label), `ScoreToolErrorCode`, `NOT_SYNCED_ERROR_CODE` |
| `android/core/model/.../model/Rhyme.kt` | `RhymeSuggestRequest.rhythmFit`, `RhymeCandidate.rhythmFit`, `RhymeSuggestResponse.rhythmApplied` / `rhythmUnavailableReason`, `RhythmUnavailableReason` (lenient serializer) |
| `android/core/data/.../data/Attachments.kt` | `DownloadedFile`, `contentDispositionFileName` (RFC 5987 `filename*`, quoted / bare `filename`, last path segment only) |
| `android/core/data/.../data/ApiClient.kt` | `downloadAttachment(path, query, target)` |
| `android/core/data/.../data/repository/ScoreToolsRepository.kt` | `ScoreToolsRepository` (`draft`, `export`), `HttpScoreToolsRepository` |
| `android/core/sync/.../sync/repo/GatedScoreToolsRepository.kt` | Online gate, waits for the snippet's changes to sync, `SCORE_NOT_SYNCED_YET` |
| `android/core/logic/.../logic/score/tools/DraftForm.kt` | `DraftSource`, `DraftForm` (`switchSource`, `withRecordings`, `check`), `DraftFormCheck`, `DraftFormProblem`, `DRAFT_DENOMINATORS`, `DRAFT_METER_PRESETS`, `defaultDraftRecording`, `takeLabel` |
| `android/core/logic/.../logic/score/tools/ScoreToolError.kt` | `ScoreToolErrorKind`, `ScoreToolError`, `toScoreToolError`, `draftSummary`, `exportFileName` |
| `android/core/logic/.../logic/rhyme/RhythmFit.kt` | `RHYTHM_FIT_LABEL`, `rhythmFitEligible`, `RhythmOutcome`, `rhythmOutcome`, `rhythmNote`, `rhythmPercent` |
| `android/core/logic/.../logic/rhyme/RhymeOrder.kt` | `ResultOrdering.rhythm`, `presentation` (banded by rhythm), `meaningUnavailable` |
| `android/core/logic/.../logic/rhyme/RhymeQuery.kt` | `RhymeQueryParams.rhythmFit`, `planRhymeQuery` sets `rhythmFit` |
| `android/app/.../app/rhyme/rhythm/RhythmFitStore.kt` | `RhythmFitStore`, `DataStoreRhythmFitStore`, `InMemoryRhythmFitStore` |
| `android/app/.../app/rhyme/rhythm/RhythmFitUi.kt` | `RhythmFitToggle`, `RhythmFitLabel` |
| `android/app/.../app/rhyme/RhymeViewModel.kt`, `RhymeUiState.kt` | `setRhythmFit`, ordering with the rhythm outcome, `rhythmToggleShown`, `rhythmNote` |
| `android/app/.../app/rhyme/ui/QueryControls.kt`, `CandidateRow.kt`, `RhymePanel.kt` | The switch, the row label, the meaning note from `meaningUnavailable` |
| `android/app/.../app/score/tools/ScoreDraftViewModel.kt` | `DraftTakes`, `DraftPhase`, `ScoreDraftUiState`, `ScoreDraftViewModel` |
| `android/app/.../app/score/tools/ScoreDraftSheet.kt` | `ScoreDraftSheet(container, snippetId, onDraft, onDismiss)`, `ScoreDraftSheet(vm, ...)`, `ScoreDraftContent`, `DraftActions`, `DRAFT_OFFLINE_MESSAGE` |
| `android/app/.../app/score/tools/ScoreExportViewModel.kt` | `ExportAction`, `ExportPhase`, `ExportedFile`, `ScoreExportViewModel` |
| `android/app/.../app/score/tools/ScoreExportMenu.kt` | `ScoreExportMenu(container, snippetId, hasScore, onMessage)` |
| `android/app/.../app/score/tools/ScoreFileActions.kt` | `shareExport`, `saveExport`, `CreateTypedDocument`, `FileActionResult`, `scoreExportAuthority` |
| `android/app/src/main/AndroidManifest.xml`, `res/xml/score_export_paths.xml` | FileProvider `${applicationId}.scoreexports`, limited to `cache/score-exports/` |
| `android/app/.../app/di/AppContainer.kt` | `rhythmFit`, `scoreTools`, `scoreExportsDir` |
| `android/app/.../app/canvas/CanvasScreen.kt` | Passes `container.rhythmFit` to the rhyme ViewModel |

Tests:

- `ScoreToolsDtoTest` (model)
- `ScoreToolsRepositoryTest` (MockWebServer)
- `GatedScoreToolsRepositoryTest`
- `RhythmFitTest`, `DraftFormTest`, `ScoreToolErrorTest` (logic)
- `RhymeViewModelRhythmTest`, `ScoreDraftViewModelTest`, `ScoreExportViewModelTest` (app)

## Hooks for feat/android-score-editor

- **Make score → From recording / server draft**: show
  `ScoreDraftSheet(container, snippet.id, onDraft = { score -> /* set score, save */ }, onDismiss)`.
  The editor stops showing it after `onDraft`. On-device "From grid" stays in the editor
  (`draft_from_grid` port).
- **Export**: in the editor top bar (e.g. through `GridEditorRoute`'s `extraTopBarActions`), add
  `ScoreExportMenu(container, snippet.id, hasScore = snippet.score != null, onMessage = { snackbar... })`.

Neither is mounted on this branch. The editor branch's score fields and editor UI had not landed
when this branch was written.

## Invariants and constraints

- Drafts and exports read the server's copy. They are sent only online and only after the
  snippet's queued changes have synced. Otherwise the answer is "Wait until this snippet's changes
  have synced".
- A draft is never saved by this code. `accept()` returns the score once and goes back to editing.
- Any form edit cancels an in-flight draft and drops a shown draft, so the shown draft always
  matches the form.
- A recording request always sends `bpm` (explicit `null` to estimate). A grid request always
  has a tempo.
- Only one export runs at a time. The export folder holds only the last export.
- The FileProvider exposes only `cache/score-exports/`. Shared files get a read grant for the
  receiving app only.
- `rhythm_fit` is sent only when the switch is on and a snippet anchor remains. A typed-only
  query is byte-identical with the switch on or off, and an old server's body is unchanged.
- The rhythm outcome and band presentation come from the first page; later pages keep them.
- No new dependencies: FileProvider (androidx.core), activity-result (activity-compose) and
  DataStore were already in the build.

## Decisions

- **`rhythm_fit` is omitted when off** (`Boolean?`, null), like `order`. This keeps every
  existing request body byte-identical (an existing test compares the exact JSON).
- **Rhythm fit applied bands the rows** even in rhyme order (contract: "when order=meaning or
  rhythm_fit is on, candidates are banded"). The "Meaning order unavailable" note now comes from
  `ResultOrdering.meaningUnavailable`, so it still shows when the rows are banded by rhythm.
- **Asked but neither applied nor given a reason** (a server without the field) reads as
  `Unavailable(OTHER)`: "Fit rhythm unavailable on this server".
- **The whole sheet is online-only**, including "From grid" (a server draft). The offline grid
  draft is the editor's local `draft_from_grid`, so it is not reimplemented here.
- **Not-synced code**: the gate's refusal is a 409 `Validation` with the client-side code
  `not_synced` (`NOT_SYNCED_ERROR_CODE`), so the logic layer can tell it apart.
- **Export file name**: the server's `Content-Disposition` name (unsafe characters replaced, the
  extension added when missing), else `snippet-<first 8 of id>.<ext>`.
- **SAF contract**: a small `CreateTypedDocument` contract takes the mime type at launch. The stock
  `CreateDocument(mime)` fixes it when remembered, so a MusicXML save would be offered as
  `audio/midi`.
