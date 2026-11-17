# Android Score Editor (musical mode)

A snippet can carry a rhythm **score**: the syllables of its beat grid placed on a one-line staff,
with meter and tempo changes at any bar, accents, a pickup and a snap choice
([score_format.md](./score_format.md), normative: `spec/score_format.md`). This feature is the
Android client: the Kotlin port of the shared algorithms, the score as offline content, and the
editor's Grid / Staff toggle with a touch staff editor and playback. The server-side tools
(draft from a recording, MIDI / MusicXML export, rhyme "Fit rhythm") are
[android_score_tools.md](./android_score_tools.md); this branch mounts them.

## Scope

- **Algorithm port** in `:core:logic` (`dev.nymph.lyrics.logic.score`): validate (raw JSON and typed),
  bars and timing, metric weight, tick to ms (and its inverse for the playhead), rest link both ways,
  rest-only predicate, reconcile, draft from grid, text splice, plus the server's save flow run locally.
  Every case of `spec/score_vectors.json` passes, read in place (Gradle `scoreVectors` property).
- **Offline content**: `score` + `score_view` on the snippet DTOs, the local copy, the outbox patch,
  the snapshot merge, the views and the conflict review ("Cadence changed").
- **Local save flow** (`applyScoreSave`), so the optimistic / offline copy equals what the server stores.
- **Editor**: "Make score" (from grid, offline; from a recording through the score tools' sheet),
  Grid / Staff toggle, touch staff editor, meter / tempo change at a bar, pickup, snap picker,
  "Remove score" (confirmed), playback (AudioTrack synthesis: click, downbeat accent, tone per note,
  playhead), and the score tools' export menu in the editor top bar.
- **Canvas cards and list rows** draw a compact staff instead of the grid when `score_view` is on.

## Non-scope

- Server calls for drafts / export / rhythm fit (android-score-tools).
- Pitch (notes keep unknown keys, the editor never writes them), loop selection, undo (the Android
  editor has no undo; Discard drops the whole edit).
- Merging scores (the server gives a merged snippet no score; the local merge does the same).
- Scrubbing playback from a position: playback always starts at tick 0.

## Data and control flow

```
open editor:  Snippet(score, score_view, grid) -> EditorUiState.initial
              rows = initEditorState(text, grid); layer = ScoreLayer.open(score, view, savedGrid, rows.grid)
grid view:    every reducer -> VM.update -> rows' grid changed?
                 rest-only (isRestOnlyEdit)  -> layer.afterGridEdit: applyGridRestEdit, commit (score, grid)
                 any other change           -> nothing; staffScore = reconcile(layer.score, layer.grid, rows.grid) on read
Staff toggle: showingStaff: commit(staffScore) -> applyRestLink -> withLinkedGrid(rows) (rests + `_` in the row text)
staff edits:  StaffCanvas / StaffEditor -> ScoreActions -> logic.score.edit.* (placeSyllable, moveNote, resizeNote,
              nudgeNote, stepLength, cycleAccent, unplace, setMeter/removeMeter, setTempo/removeTempo,
              setPickup, setSnap) -> StaffEdit.Changed -> commit + rest link into the rows
Make score:   from grid: draftFromGrid(rows.grid, meter, bpm, note) -> commit, staff view
              from a recording: ScoreDraftSheet(onDraft) -> useDraft (validated) -> save -> close
save:         saveRequest(state) = type + grid + text + ScoreLayer.saveFields (Set(score for rows' grid) + view,
              or REMOVE when it had one, or nothing)
              -> LocalSnippetRepository -> LocalEdits.updateSnippet
                 applyScoreSave(stored, oldGrid, newGrid, text, Keep | Remove | Set)
                   Set -> validateScore vs new grid (422 invalid_score on failure, nothing stored)
                   Keep -> unchanged grid keeps / rest-only -> applyGridRestEdit / else reconcile
                   then applyRestLink(score, grid) and spliceLineTexts(text, changedLineTexts(grid, linked))
                 local snippet = (linked grid, spliced text, score, scoreViewAfter)
                 queued EditSnippet patch = the stored result (grid, text, score) when a score is involved
              -> engine -> PUT /api/snippets/{id} {score | null, score_view, base_version}
sync:         snapshot / responses -> LocalSnippet.of (score, score_view forced false without a score)
conflict:     ReviewItem.cadenceChanged (mine.score != server.score) -> "Cadence changed" + "Score: …" lines
playback:     ScorePlayer.play(score) -> playbackPlan (clicks per beat / dotted beat, pickup beats, notes)
              -> synthesize (16-bit mono 22.05 kHz) -> static AudioTrack; playhead = msToTick(positionMs)
canvas:       cardLayout: showsStaff(snippet) -> compactStaff(score, grid) -> drawCard (cards and list rows)
```

**Text edits and reconcile.** The editor never reconciles per keystroke. The committed score stays
valid against the grid it was committed with, and the staff reads
`reconcile(committed, committedGrid, rowsGrid)`. So retyping a word inside one session keeps its
notes, the same way the server reconciles once per save. Rest-only grid edits commit immediately
because they move notes: a later text edit must not undo that move.

**What the PUT carries.** When the snippet has (or had) a score and the edit sends a grid or a score,
the queued patch carries the stored result: the linked grid, the spliced text and the score. The
server's own flow is then a no-op on top (validate, rest link idempotent). Text sent without a grid
(not used by the editor) leaves the score to the server's reconcile, and coalescing drops an earlier
score `Set` together with the earlier grid.

## Files

| File | Role / key exports |
|---|---|
| `core/logic/.../logic/score/ScoreError.kt` | `ScoreError` (`Invalid(reason, message, line?, note?)` + `detail()`, `NotRestOnly`, `TextMismatch`), `ScoreResult` (`Ok` / `Err`, `getOrNull`, `map`, `flatMap`) |
| `.../score/ScoreRules.kt` | constants, `isValidMeter`, `isValidBpm`, `snapOf`, `ScoreSnap.wire`; raw JSON integer / number readers |
| `.../score/ScoreValidate.kt` | `checkScore(raw, grid)`, `validateScore(score, grid)` (spec order, first failure) |
| `.../score/ScoreTiming.kt` | `barLength`, `barStartTick` (Long), `barAtTick`, `meterAtBar`, `tempoAtBar`, `meterAt`, `beatUnit`, `metricWeight`, `tickToMs`, `msToTick`, `endTick`, `barCount`, `PICKUP_BAR` |
| `.../score/GridView.kt` | internal: syllable positions, rests between, word groups, syllable identity, `rebuildLineText` (rest_marks rule) |
| `.../score/ScoreNormalize.kt` | `normalizeWord`, `wordKey`, `lineKey`, `lcsPairs` |
| `.../score/RestLink.kt` | `applyRestLink`, `isRestOnlyEdit`, `applyGridRestEdit` -> `GridEditResult` |
| `.../score/Reconcile.kt`, `Draft.kt`, `TextSplice.kt`, `ScoreJson.kt` | `reconcile`; `draftFromGrid` / `draftFromGridRaw`, `defaultAccent`; `spliceLineTexts`, `changedLineTexts`; `scoreToJson`, `Score.noteAt`, `allNotes` |
| `.../score/SaveFlow.kt` | `ScoreEdit` (`Keep`, `Remove`, `Set`), `SavedScoreContent`, `applyScoreSave`, `scoreViewAfter` |
| `.../score/edit/Snap.kt` | `snapTick` (grid restarts at each bar line; backwards from bar 0 in the pickup), `snapLength` |
| `.../score/edit/StaffEdits.kt` | `SyllableRef`, `StaffEdit` (`Changed` / `Refused(StaffRefusal)`), `noteWindow`, `placeSyllable`, `moveNote`, `resizeNote`, `stepLength`, `nudgeNote`, `cycleAccent`, `unplace`, `unplacedSyllables`, `Score.noteOf` |
| `.../score/edit/BarEdits.kt` | `setMeter`, `removeMeter`, `setTempo`, `removeTempo`, `tempoValue`, `setPickup`, `setSnap` |
| `.../score/play/PlaybackPlan.kt`, `Synth.kt` | `playbackPlan` -> `PlaybackPlan(clicks, notes, durationMs, endTick)`, `accentGain`; `synthesize`, `SynthVoice` |
| `core/logic/.../editor/EditorRowsState.kt` | `withLinkedGrid(state, grid)`: a rest-linked grid written back into the rows (changed rows arranged, text rebuilt) |
| `core/logic/build.gradle.kts` | `scoreVectors` system property (`../spec/score_vectors.json`) |
| `core/model/.../model/Snippet.kt`, `Sync.kt` | `Snippet.score` / `scoreView`, `SnapshotSnippet` the same, `SnippetCreateRequest.score` / `scoreView`, `SnippetUpdateRequest.score: ScoreChange?` / `scoreView` |
| `core/model/.../model/score/ScoreChange.kt` | `ScoreChange(score?)` (`REMOVE`, `set`), its store serializer, `ScoreChangeWireSerializer` (object or explicit `null`) |
| `core/sync/.../store/LocalData.kt` | `LocalSnippet.score` / `scoreView` (defaults: older store files read no score) |
| `core/sync/.../outbox/Patches.kt` | `ContentPatch.score` / `scoreView`; `then` (later wins; text-only drops an earlier grid and score) |
| `core/sync/.../apply/LocalEdits.kt` | `updateSnippet` runs `applyScoreSave` and queues `sentPatch`; `createSnippet` validates a given score; `INVALID_SCORE` |
| `core/sync/.../engine/OpSender.kt`, `Outcomes.kt` | the PUT sends `score` / `score_view`; the self-conflict check compares them |
| `core/sync/.../store/Views.kt` | assembled `Snippet` carries score / view |
| `core/sync/.../conflict/ReviewItem.kt`, `Resolver.kt` | `ReviewSide.OfSnippet.score`, `ReviewItem.cadenceChanged`; Overwrite resends my score (or removal) when either side has one; Save mine as new copies it |
| `app/.../editor/score/ScoreLayer.kt` | `ScoreLayer` (`scoreFor`, `afterGridEdit`, `committed`, `linkedFor`, `saveFields`, `open`) |
| `app/.../editor/score/ScoreActions.kt`, `ScoreStateEdits.kt` | the score actions (VM implements them); pure state transitions (`committing`, `showingStaff`, `drafted`, `withDraft`, `withoutScore`, `staffEdited`), `DraftProblem` |
| `app/.../editor/EditorUiState.kt`, `GridEditorViewModel.kt` | `score`, `draftProblem`, `staffScore`, `staffShown`; `saveRequest` adds the score fields; the update hook for rest-only edits |
| `app/.../editor/score/ui/StaffLayout.kt` | `StaffGeometry` (rows of wrapped bars, beat marks, note boxes split at row breaks, line marks, `hit`, `tickAt`, `positionOf`), `StaffMetrics`, `bpmLabel` |
| `app/.../editor/score/ui/StaffCanvas.kt` | the staff drawing and touch handling (tap select / place, drag body = move, drag right end = length, bar label tap = meter/tempo) |
| `app/.../editor/score/ui/StaffEditor.kt` | transport, snap picker, pickup stepper, selected-note bar, tray (tap to arm, drag onto the staff), hint |
| `app/.../editor/score/ui/BarDialog.kt` | meter / tempo from a bar, remove a change; `parseBarInput` |
| `app/.../editor/score/ui/ScoreModeBar.kt` | Make score dialog (from grid; "From a recording…" opens `ScoreDraftSheetSlot`), Grid / Staff, Remove score (confirmed) |
| `app/.../editor/score/ui/ScorePlayer.kt` | static `AudioTrack` playback, `positionMs`, `PlaybackError` |
| `app/.../editor/ui/GridEditorScreen.kt`, `GridEditorRoute.kt` | mount the mode bar and the staff; the route passes the score tools' `ScoreDraftSheet` and `ScoreExportMenu` |
| `app/.../canvas/render/CompactStaff.kt`, `CardLayout.kt`, `CardRenderer.kt`, `canvas/SnippetCanvas.kt` | `showsStaff`, `compactStaff`; `CardLayout.staff`; drawing; the layout cache keys on score / view |
| `app/.../sync/ReviewText.kt`, `NotSyncedScreens.kt` | `CADENCE_CHANGED`, `scoreSummary`; the review shows "Cadence changed" |

## Invariants and constraints

- The local score is always null or valid against the local grid (`applyScoreSave` validates or
  derives it; snapshot and responses come from the server, which guarantees it).
- `score_view` is false whenever the score is null: locally (`scoreViewAfter`, `LocalSnippet.of`) and
  in views.
- The rows are the only source of the grid in the editor. A commit writes the linked grid back
  (`withLinkedGrid`), so the grid view shows the staff's gaps as rests and the text as `_`.
- Staff edits never break the single voice. A note stays between its neighbours in (line, s) order
  (`noteWindow`), and an edit that cannot fit is `Refused`, leaving everything as it was.
- Every algorithm reproduces the spec bit for bit (`tickToMs` sums in the spec's order); the
  vectors are read in place, never copied.
- Errors are typed: `ScoreError`, `ScoreResult`, `StaffEdit.Refused(StaffRefusal)`, `DraftProblem`,
  `PlaybackError`, and the 422 `invalid_score` as `ApiError.Validation`. The only catches are
  AudioTrack's construction exceptions and the port's internal `Stop` signal.
- Logging: `Score` tag, `event=score.play` / `score.play_failed` with key-values.

## Decisions

1. **No Room migration: the store is a file.** The contract says "Room migration", but the offline
   copy is the JSON file store ([android_offline_sync.md](./android_offline_sync.md#storage-choice-file-backed-json-kotlinxserialization-not-room)).
   The new `LocalSnippet` fields default to null / false, so older store files read as "no score"
   (tested).
2. **`ScoreChange` for the three PUT states.** No change is a missing field, Remove score is
   `ScoreChange(null)` and a new score is `ScoreChange(score)`. On the wire it is the score object or
   an explicit `null`, despite `explicitNulls = false`. In the store it is `{}` or `{"score": …}`.
3. **The queued PUT carries the stored result** (linked grid, spliced text, score) when a score is
   involved, rather than the request as typed. That way the server stores what the phone shows, even
   where the server's derived grid would differ.
4. **Overwrite with mine** sends the score only when either side has one, so conflicts on snippets
   without scores resend exactly as before.
5. **Pickup changes move the notes with bar 0.** Notes keep their beat positions, so a draft that
   starts on the downbeat still does. Meter and tempo edits leave note ticks alone.
6. **Snapping restarts at every bar line** and runs backwards from bar 0 in the pickup, so triplets
   and odd meters line up with their bars.
7. **Drags commit continuously.** Every snapped move is a commit, which rest-links the rows. A drag
   on empty staff is left to the page scroll; a drag that starts on a note or its handle moves it.
8. **Tray placement** works by tap (select a tray syllable, then tap the staff) and by dragging a
   chip onto the staff. A placed note gets the snap length (or what fits) and the stress accent.
9. **A draft from the recording sheet is saved at once.** It is applied with `useDraft` and then
   saved and closed, as the coordinator asked. The local from-grid draft stays open in the staff.
10. **Playback is synthesized up front** (sine bursts into a static AudioTrack, at most 10 minutes)
    and always starts at tick 0. The playhead polls `playbackHeadPosition` once per frame.
11. **The compact staff on cards** draws one row per grid line, from the bar before its first note to
    the bar after its last, on a common scale. Lines with no notes get an empty one-bar row. Syllable
    and accent labels appear only when text is legible.
12. **The port has its own `rebuildLineText`.** It does not reuse `logic.grid.rebuildRawText`, which
    takes the first non-null `word` where rest_marks takes the first non-empty one.

## Tests

- `:core:logic`
  - `ScoreVectorsTest` covers every vector case of the 15 sections.
  - `ScoreAlgorithmsTest` covers the spec examples; `SaveFlowTest` covers the save flow.
  - `StaffEditsTest`, `BarEditsTest`, `PlaybackTest` (plan and synth), `EditorRowsLinkedGridTest`.
- `:core:model`: `SnippetScoreFieldsTest` (fields, explicit-null removal, store round trip).
- `:core:sync`
  - `ScoreSyncTest` covers the save flow through `LocalEdits`, coalescing, the store file, the
    snapshot, review and resolve.
  - `ScoreHttpSyncTest` checks the PUT body and that the response lands in the copy.
- `:app`
  - `GridEditorScoreTest` covers the VM: toggle, Make score, rest edits, staff edits, typing, save,
    Remove, and drafts.
  - `StaffLayoutTest`, `CompactStaffTest`, `CadenceReviewTextTest`.
