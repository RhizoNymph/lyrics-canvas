# Web Score Tools: drafts, export, Fit rhythm

Web side of musical mode's server-backed tools (round-4 contract §5, §6, §7 "web-score-tools"): drafting a
first score for a snippet (from its grid or from one of its voice takes), downloading a snippet's score as
MIDI or MusicXML, and the rhyme sidebar's "Fit rhythm" switch. The score format and its algorithms are in
[score_format.md](./score_format.md) (`spec/score_format.md` is normative); the snippet score fields, the
optimistic score save flow, the TS algorithm port (`frontend/src/lib/score/`) and the staff editor belong to
feat/web-score-editor. This branch merges that branch's first push (store fields + port) and uses its
`draftFromGrid` and `updateSnippet` save path; it reimplements none of them.

## Scope

- `api/score.ts`: `POST /api/snippets/{id}/score/draft` and `GET /api/snippets/{id}/score/export?format=midi|musicxml`
  over an injected axios instance, rejecting only with a typed `ScoreApiError` (every contract §5 code plus
  `invalid_score` with its reason / line / note).
- `ScoreDraftDialog` (`components/score/ScoreDraftDialog.tsx`): the "Make score" dialog. From grid: note length
  (the five snaps), meter, bpm (required). From recording: the snippet's takes (newest first; takes without
  word timings listed but disabled), meter, optional bpm (empty = estimate). Shows the returned draft's estimated
  tempo and unplaced / unmatched counts, then "Use score" hands it to `onDraft(score)`. Errors are shown with a
  title and hint. Nothing is saved by the dialog itself.
- `ScoreDraftHost` + context menu "Make score…" (snippets without a score): a canvas-level mount of the dialog
  that saves the chosen draft right away with `score_view: true`.
- Export: an "Export" dropdown (MIDI / MusicXML) in the inline editor header, and "Export MIDI (.mid)" /
  "Export MusicXML (.musicxml)" items in the snippet context menu, both only for snippets with a score; the
  file is saved under the server's Content-Disposition name and the outcome shows in the bottom notice.
- Rhyme sidebar "Fit rhythm" (contract §6): a per-viewer switch (localStorage `lyrics-canvas.rhymeRhythmFit`),
  shown only while a selected snippet anchors the query; sends `rhythm_fit: true`; reads `rhythm_applied` /
  `rhythm_unavailable_reason`; a quiet `♩NN%` per row; band headers whenever rhythm was applied (both orders);
  the unavailable reason as a note; "More" pages keep the result set's choice.

## Non-scope

- The staff editor, Grid / Staff toggle, playback, Remove score, the inline editor's own "Make score" button
  (feat/web-score-editor mounts `ScoreDraftDialog` there).
- The score algorithms (validate, rest link, reconcile, draft from grid): the editor's port, used as is.
- Server work: draft alignment, tempo estimation, MIDI / MusicXML writers, rhythm-fit scoring.
- Fit rhythm in pattern mode columns and word bank rhymes (contract §6 non-scope).
- Android (feat/android-score-tools).

## Data and control flow

### Draft dialog

```
open (editor "Make score" with onDraft, or context menu -> scoreDraftStore.open(id) -> ScoreDraftHost)
  ScoreDraftDialog (portal on document.body, data-keep-editor-open overlay, focus trap, Escape closes)
    useReducer(reduceDraftDialog, initialDraftDialog(source))      lib/scoreTools/draftDialog.ts
    effect: voiceApi.listRecordings(id) -> recordings_loaded       (auto-picks newest take with timings)
                                         | recordings_failed(message)
    edit field  -> {type: edit} (aborts a pending draft; phase back to editing)
    Draft       -> buildDraftRequest(form)                          lib/scoreTools/draftForm.ts
                   invalid -> editing{issues}  (meter / bpm / recording, all at once)
                   valid   -> drafting{request}
                            -> runDraft(id, request, {draft: scoreApi.draft, local: draftGrid}, signal)
                                 grid + local drafter -> draftGrid(request)  (localGridDrafter(grid) =
                                                         lib/score draftFromGrid, ScoreInvalid -> invalid_score)
                                 otherwise            -> POST /api/snippets/{id}/score/draft
                            -> drafted{draft} | failed{ScoreApiError}
    drafted     -> draftSummary(draft) lines; "Draft again" | "Use score" -> onDraft(score); onClose()
ScoreDraftHost.onDraft -> saveDraftedScore(id, score, canvasStore.updateSnippet)
                          -> updateSnippet(id, {score, score_view: true})  (the editor branch's save flow)
                          ScoreSaveError / other -> bottom notice (error)
```

### Export

```
editor header ScoreExportMenu (hasScore(snippet)) ─┐
context menu scoreExportEntries(snippet) items ────┴─> exportScoreWithNotice(id, format)
  exportScoreFile: scoreApi.exportScore(id, format)  GET .../score/export?format=…, responseType blob
     filename = filenameFromContentDisposition(header) ?? exportFallbackFilename(id, format)
     error: blob body decoded as JSON first, then toScoreApiError(err, 'export')
  ok -> saveBlobAs(blob, filename) -> notice "Saved <file>";  failure -> notice "Export failed: <title>"
```

The export reads the **saved** score (the server has it); unsaved editor changes are not in it.

### Fit rhythm

```
RhythmFitToggle (visible iff anchorSnippets(selection, snippets, excluded) non-empty)
  -> rhymeRhythmFitStore.on (saveRhythmFit -> localStorage)
useRhymeQuery deps += on  -> 150 ms debounce -> rhymeStore.runQuery()
  buildRequest(offset, order, rhythmOn): ...orderField(order), ...rhythmFitField(rhythmOn, anchors)
     rhythmRequested = rhythmOn && rhythmEligible(anchors)   (some anchor of kind "snippet")
  response -> rhythm = rhythmFitting(rhythmRequested, res.data)   {requested, applied, unavailable}
loadMore -> buildRequest(next, ordering.requested, rhythm.requested)   (the loaded set's choices)
CandidateListView: presentation = listPresentation(ordering, rhythm)   (applied -> 'bands')
                   meaning note from presentationOf(ordering); RhythmNote(rhythm) for the reason
CandidateRow: RhythmBadge(candidate.rhythm_fit) "♩NN%" before the similarity and the score
```

## Files

| File | Role / key exports |
|------|--------------------|
| `frontend/src/types/scoreTools.ts` | Wire types `DraftMeter`, `GridDraftRequest`, `RecordingDraftRequest`, `ScoreDraftRequest`, `ScoreDraftResponse`, `ScoreExportFormat` (+ `SCORE_EXPORT_FORMATS`, `_LABELS`, `_EXTENSIONS`), `ScoreExportDownload`; errors `SCORE_SERVER_ERROR_CODES`, `ScoreServerErrorCode`, `ScoreApiErrorKind`, `ScoreApiOperation`, class `ScoreApiError` (`kind`, `operation`, `status`, `reason`, `line`, `note`), guards `isScoreServerErrorCode`, `isScoreInvalidReason` |
| `frontend/src/api/score.ts` | `ScoreApi` (`draft`, `exportScore`), `createScoreApi(http)`; validates the draft response shape |
| `frontend/src/api/client.ts` | `scoreApi = createScoreApi(api)` |
| `frontend/src/lib/scoreTools/errors.ts` | `toScoreApiError(err, operation)` (code in `detail` or top level wins, then 404 / 422 / 5xx / network / aborted), `describeScoreApiError` → `{title, hint, retryable}` (invalid_score hint names reason and 1-based line / note) |
| `frontend/src/lib/scoreTools/draftForm.ts` | `DraftForm`, `DEFAULT_DRAFT_FORM` (grid, 1/8, 4/4, 90), `METER_DENS`, `parseMeter`, `parseBpm`, `buildDraftRequest` → `DraftFormResult`, `DraftFormIssue` + `DRAFT_ISSUE_TEXT`, `recordingChoices` (`RecordingChoice {id, label, usable}`), `draftSummary` |
| `frontend/src/lib/scoreTools/draftDialog.ts` | `DraftDialogState`, `RecordingsState`, `DraftPhase`, `DraftDialogEvent`, `initialDraftDialog`, `reduceDraftDialog` |
| `frontend/src/lib/scoreTools/drafter.ts` | `LocalDraftResult`, `LocalGridDrafter`, `DraftDeps`, `runDraft`, `localGridDrafter(grid)` (wraps `draftFromGrid` from `lib/score`) |
| `frontend/src/lib/scoreTools/exportFile.ts` | `exportFallbackFilename`, `hasScore`, `ExportOutcome`, `exportNotice` |
| `frontend/src/components/score/ScoreDraftDialog.tsx` | `ScoreDraftDialog` (`ScoreDraftDialogProps`: `snippet`, `onDraft(score)`, `onClose`, `initialSource?`, `draftGrid?`) |
| `frontend/src/components/score/ScoreDraftView.tsx` | Store-free dialog body (`ScoreDraftViewProps`) |
| `frontend/src/components/score/ScoreDraftHost.tsx` | Canvas-level dialog for `scoreDraftStore.target`, local grid drafts over the stored grid, saves on use |
| `frontend/src/components/score/saveDraftedScore.ts` | `saveDraftedScore(id, score, update)` → `SaveDraftResult` (never rejects) |
| `frontend/src/stores/scoreDraftStore.ts` | `useScoreDraftStore` (`target`, `open(id)`, `close()`) |
| `frontend/src/components/score/exportScore.ts` | `exportScoreFile(id, format, deps?)` → `ExportOutcome` (never rejects), `exportScoreWithNotice` |
| `frontend/src/components/score/ScoreExportMenu.tsx` | `scoreExportEntries(snippet)`, `ScoreExportMenuView`, default `ScoreExportMenu` (editor header); optional `beforeExport()` runs first (the open editor saves its pending edits, see web_score_editor.md) |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | Mounts `ScoreExportMenu` in the header |
| `frontend/src/components/canvas/SnippetContextMenu.tsx` | "Make score…" (no score) and the two export items (score) |
| `frontend/src/components/pages/CanvasPage.tsx` | Mounts `ScoreDraftHost` |
| `frontend/src/lib/rhyme/rhythmFit.ts` | `rhythmEligible`, `rhythmFitField`, `RhythmUnavailable`, `RhythmFitting`, `RHYTHM_OFF`, `rhythmFitting`, `rhythmNote`, `rhythmPercent`, `listPresentation`; persistence `RHYTHM_FIT_STORAGE_KEY`, `parseRhythmFit`, `loadRhythmFit`, `saveRhythmFit` (typed `SaveRhythmFitResult`) |
| `frontend/src/stores/rhymeRhythmFitStore.ts` | `useRhymeRhythmFitStore` (`on`, `setOn`), `currentRhythmFit()` |
| `frontend/src/types/rhyme.ts` | `RhymeCandidate.rhythm_fit?`, `RhythmUnavailableReason`, `RhymeSuggestRequest.rhythm_fit?: true`, `RhymeSuggestResponse.rhythm_applied?`, `.rhythm_unavailable_reason?` |
| `frontend/src/stores/rhymeStore.ts` | `rhythm: RhythmFitting` (reset with `ordering`); `buildRequest(offset, order, rhythmOn)` |
| `frontend/src/hooks/useRhymeQuery.ts` | The switch in the re-query dependencies |
| `frontend/src/components/rhyme/rhythm/RhythmFitToggle.tsx` | `RhythmFitToggleView`, default export bound to the store and the selection |
| `frontend/src/components/rhyme/rhythm/rhythmParts.tsx` | `RhythmBadge`, `RhythmNote` |
| `frontend/src/components/rhyme/CandidateList.tsx`, `CandidateRow.tsx`, `QueryControls.tsx` | Optional `rhythm` prop / banding / note; badge; toggle under "Order" |

Tests: `api/score.test.ts`, `lib/scoreTools/{errors,draftForm,draftDialog,drafter,exportFile}.test.ts`,
`components/score/{ScoreDraftView.test.tsx,ScoreExportMenu.test.tsx,exportScore.test.ts,scoreDraftHost.test.tsx}`,
`lib/rhyme/rhythmFit.test.ts`, `stores/rhymeStore.rhythm.test.ts`, `components/rhyme/rhythm/rhythm.test.tsx`.

## Invariants

- Every `api/score.ts` call rejects with a `ScoreApiError` only; a known code in the body beats the status;
  `reason` / `line` / `note` are set only for `invalid_score` (an unknown reason reads as `malformed`).
- A draft request is built only from a valid form: meter numerator 1..32 over an allowed denominator, bpm
  20..400 with at most two decimals (required for grid, empty = null for recording), a take for recording.
- The dialog never saves; `onDraft` is called only with a draft the dialog showed. A draft answer that arrives
  after an edit (or a newer submit, or close) is dropped (abort + phase check).
- Recording drafts always go to the server; grid drafts go local only when a drafter is passed.
- Export items exist only for a snippet whose `score` is an object; a failed export saves nothing.
- `rhythm_fit` is sent only as `true`, only with a snippet anchor; otherwise the request is byte-identical to
  before. `rhythm.unavailable` is set exactly when rhythm was requested and not applied.
- Pages appended to a result set reuse that set's rhythm choice; a switch change re-queries from offset 0.
- Storage failures never break the switch (reads fall back to off, writes log
  `rhythm fit: write failed key=… on=…` at warn).

## Decisions

- **Draft wire types in `types/scoreTools.ts`**, not `types/score.ts`: the format file belongs to score-spec
  and the editor branch; a separate file avoids edits in a file both siblings touch.
- **Grid drafts local when a drafter is given** (`draftGrid` prop; `ScoreDraftHost` passes
  `localGridDrafter(snippet.grid_data)`). The contract says the editor's grid draft is local; the server
  route drafts the stored grid, which can differ from unsaved editor rows. Without a drafter the dialog uses
  the server route, so the dialog works for any caller.
- **"Make score…" in the context menu** (`ScoreDraftHost`): the editor's own Make score button was not pushed
  when this branch was written; the context menu entry is the named mount that works today and saves through
  the editor branch's `updateSnippet` score flow (`{score, score_view: true}`). The inline editor should mount
  `ScoreDraftDialog` itself with `draftGrid={localGridDrafter(currentGrid)}` and its own `onDraft`.
- **`snippet_not_found` added** to the typed codes (a draft or export of a deleted snippet), besides the four
  §5 codes and `invalid_score`; `invalid_request` covers FastAPI's 422 validation bodies.
- **Fallback file name** `snippet-<id[0..8]>.mid|.musicxml` when Content-Disposition is unreadable (the
  server's own name is `<title-or-snippet>`; snippets have no title field on the client).
- **Band headers when rhythm is applied in rhyme order**: the server bands candidates whenever rhythm fit is
  on (contract §6), so the list shows headers then; the meaning note still shows when meaning fell back.
- **Rhythm percent as `♩NN%`**, next to the meaning `≈NN%`, same quiet style.
- **`rhythm_fit` request and response fields optional in TS** like `band` / `order_applied`: wire fixtures
  written before them keep compiling; a missing `rhythm_applied` reads as not applied (`unknown` reason).
