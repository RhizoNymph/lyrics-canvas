# Web score editor (musical mode)

A snippet can carry a rhythm **score** (format v1, `spec/score_format.md`): the syllables of its beat grid
placed on a one-line staff with start, length and accent, under meters and tempos that may change at any
bar, with an optional pickup. This feature is the web side of it: the TypeScript port of the shared
algorithms, the snippet fields and save flow, the staff editor, the compact staff on cards and list rows,
and playback. Drafts from a recording, export and rhyme "Fit rhythm" are
[web_score_tools.md](./web_score_tools.md).

## Scope

- `frontend/src/lib/score/`: the port of every algorithm of the spec (validate, bars and timing, metric
  weight, tick to ms, rest link both ways, rest-only predicate, normalization and LCS, reconcile, draft
  from grid, text splice), reading raw JSON values, passing every case of `spec/score_vectors.json` (read
  in place). Plus editor logic: staff edit operations, layout, playback plan, grid-follow track, history,
  and the content save flow.
- `Snippet` gains `score: Score | null` and `score_view: boolean` (`SnippetScoreFields`); `PUT` bodies may
  send `score` (object, or `null` = Remove score) and `score_view`.
- `canvasStore.updateSnippet` runs the save flow locally and applies it optimistically, so the local state
  equals what the server stores; it sends the final `score`, `grid_data` and `raw_text`.
- Inline editor: Grid / Staff toggle (`score_view`), "Make score…" (the `ScoreDraftDialog` of
  web-score-tools, grid drafts local over the live grid), the staff editor, Remove score (confirmed),
  editor-local undo / redo covering grid, staff and score together, export flushes pending edits first.
- Compact staff on canvas cards (Pixi) and list rows when `score_view` is on.
- Playback with Web Audio: click per beat (downbeats higher and louder), a tone per note with gain by
  accent, playhead, stop.

## Non-scope

- Pitch (the format keeps unknown note keys; the editor carries them unchanged).
- Loop selection (optional in the contract; not done).
- Recording drafts, export files, rhythm fit (web-score-tools); the backend (score-backend).
- Offline / outbox (web has none).

## Data and control flow

### Opening the editor

`InlineGridEditor` builds its rows from `raw_text` and `grid_data` (`initEditorState`, unchanged). The
stored score is carried onto the rows' grid with `reconcile(score, snippet.grid_data, rows.gridData)`
(identical syllables keep every note; a row the saved grid did not match loses its notes). A score that
does not fit the stored grid is ignored with a warning. That gives the **track** (`lib/score/track.ts`):
`{score, base: {score, grid}}`, and the view starts as `score_view` (only when a score exists).

### Grid edits move the staff

An effect watches `rows.gridData` against the grid the track was last computed for:

1. **Rest-only edit** (`isRestOnlyEdit`: same lines, same `syllable` + `word_index` per syllable cell; rest
   insert / remove, a chunk drag over rests): `applyGridRestEdit(score, prev, next)` moves later notes of
   the line by one beat unit per rest (removals clamp to closing the gap), ripples later lines right, and
   returns the canonical grid (rest link), which replaces the rows' changed lines (`replaceGridData`).
   The result becomes the new base. An edit that would pass `MAX_TICKS` is refused and the rows go back.
2. **Any other change** (typing, a syllabify result, a chunk drag across syllables, line moves, splits and
   joins): `reconcile(base.score, base.grid, next)`. Reconciling from the base instead of the previous
   step means typing "hello" -> "hell" -> "hello" gives the notes of "hello" back. The rest link is not
   applied while typing (it would rewrite the row being typed); the save flow applies it.

### Staff edits write rests into the grid

Every staff operation (`lib/score/edit.ts`: place, move, resize, accent cycle, unplace, meter / tempo
change at a bar, pickup, snap) returns a new valid score or a typed refusal (`EditRefusal`, shown as a
message). The editor commits it with `commitTrack(score, grid)`: `applyRestLink` sets the rest cells between
consecutive placed syllables to `floor(gap / beat unit)`, the rows take the changed lines (their text
becomes the canonical `_` text), and the score + linked grid become the base. "Make score" (a draft from
`ScoreDraftDialog`) commits the same way and switches to the staff; a grid draft round-trips through the
rest link unchanged.

### Saving

`contentBody()` sends `raw_text` (`rawTextForSave`), `grid_data`, `snippet_type`, and `score` +
`score_view` when there is a score, or `score: null` when the stored snippet had one and it was removed.
`canvasStore.updateSnippet` calls `planContentSave(current, body)` (`lib/score/saveFlow.ts`):

- `score: null`: Remove score, `score_view` forced false.
- a sent score: validated against the sent (or current) grid (`checkScore`), then rest-linked;
- no score sent, grid changed, the snippet has a score: rest-only -> `applyGridRestEdit`, else
  `reconcile`, then rest-linked;
- `score_view` alone: passed through (forced false without a score);
- finally `spliceLineTexts(raw_text, changedLineTexts(grid, linked))` keeps the snippet text in step.

The plan's `local` fields are applied at once with one undo entry (`pushUndoState`); the body sent is the
final content, so the server's own pass (the same algorithms, idempotent) stores exactly the local state.
On a failed request the snippet and the undo stack are restored and the error is rethrown. A score that
does not fit is refused before any request (`ScoreSaveError` wrapping `ScoreInvalid`); the editor then
stays open with a notice. Bodies that do not touch the score are sent unchanged.

### Undo

- **Editor-local**: Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z and Ctrl/Cmd+Y (outside text inputs, which undo natively)
  step through `{rows, track, scoreView}` snapshots recorded before structural grid edits, staff edits,
  Make score and Remove score (`lib/score/history.ts`, 100 entries). The keys stop propagation, so the
  canvas undo does not run (and desync the open editor) while it is open.
- **Canvas**: the save is one `updateSnippet` undo entry, so after closing, Ctrl/Cmd+Z restores the whole
  previous content including score and view.

### Staff editor

`StaffEditor` lays the score out with `layoutStaff` (bars, the pickup as bar -1, wrapped greedily into
rows of 768 px at 1 px per tick, one empty bar after the last note) and renders one `StaffRowView` SVG
per row: staff line, bar lines (dashed for the pickup), beat and half-beat ticks (dotted beats in
compound meters), bar labels (number, meter and tempo changes; click opens the bar change editor),
dashed green line boundaries (`L1`, `L2` at each grid line's first note), notes as head + length bar
(a note crossing rows continues as a bar), accent marks (`>` accent, `>>` strong) and syllables under
the heads. Below the staff, the tray lists the unplaced syllables of each line.

- Drag a tray chip onto the staff (HTML drag and drop), or click it then click the staff, or press Enter
  to put it right after the previous note: `placeNote` at the snapped tick, an eighth long (clamped into
  the free window between its neighbours in score order, shortened if needed).
- Drag a note to move it, its right edge to change its length (pointer, snapped, previewed, one commit on
  release).
- Keys on a selected note: ←/→ nudge by the snap (Shift: by a beat unit), Alt+←/→ shorter / longer, `a`
  accent cycle, Delete / Backspace back to the tray, Tab / Shift+Tab next / previous note, Space play /
  stop, Escape deselect.
- Toolbar: Play / Stop, Snap (1/8, 1/16, 1/32, 1/8 and 1/16 triplets, saved as `snap`), Pickup (multiples
  of the snap shorter than bar 1), Meter / tempo… (bar number, meter num / den, bpm, set or remove the
  change), Remove score.
- Snapping counts from the start of the bar holding the tick (the pickup counts back from bar 0), so odd
  meters and pickups keep a sensible grid.

Speed: layout and segments are linear in bars + notes (4000 notes lay out well under 200 ms in tests);
rows are memoized by content, so an edit re-renders the rows it changes and the playhead only its row.

### Compact staff

`compactStaffShapes` (pure) lays the score out at half resolution in the card's inner width (no empty
bar) and flattens it into lines, bars, heads and texts. `SnippetCard` draws them with `drawCompactStaff`
(`staffDraw.ts`) in place of the beat grid when `showsStaff(snippet)` (score and `score_view`), with
syllables and accent marks as Pixi texts. `ListRow` shows `CompactStaff` (the same `StaffRowView`,
read-only) in place of `GridPreview`.

### Playback

`playbackPlan(score, fromTick)` lists clicks (each beat of every bar up to the end of the last bar with a
note; downbeats marked) and notes (`ms`, `durationMs` from `tickToMs`, gain 0.35 / 0.6 / 0.9 by accent).
`usePlayback` schedules them with a 1 s lookahead timer on an `AudioContext` (square blips for clicks,
1600 Hz / 1000 Hz; a triangle tone at 440 Hz per note, 85 % of its length up to 1 s), and moves the
playhead with `requestAnimationFrame` through `msToTick` (the inverse of the tempo map). Play starts at
the selected note, else at tick 0. Stop or closing the editor closes the context.

## Files

| File | Role / key exports |
|------|--------------------|
| `frontend/src/lib/score/errors.ts` | `ScoreError`, `ScoreInvalid` (reason, line?, note?, `asBody()`), `NotRestOnlyEdit`, `ScoreTextMismatch`, `ScoreArgumentError` (all with `kind`) |
| `frontend/src/lib/score/json.ts` | `asInteger` (12.0 is 12, no booleans, within 2^53-1), `asNumber`, `isJsonObject`, `hasExactKeys`, `jsonEqual` |
| `frontend/src/lib/score/gridView.ts` | `GridJson` / `LineJson` / `CellJson`, `isRest`, `syllablePositions`, `syllableCounts`, `restsBetween`, `wordGroups`, `syllableIdentity`, `rebuildLineText` (JSON form), `syllableTexts` |
| `frontend/src/lib/score/validate.ts` | `checkScore(raw, grid) -> Score` (throws), `validateScore` (result), `isValidBpm`, `isValidMeterValue`, `meterBarLength`, `parseSnap`, limits |
| `frontend/src/lib/score/timing.ts` | `barLength`, `barStartTick`, `barAtTick`, `meterAtBar`, `tempoAtBar`, `beatUnit`, `metricWeight`, `tickToMs`, `endTick`, `barCount`, `PICKUP_BAR` |
| `frontend/src/lib/score/normalize.ts` | `normalizeWord`, `wordKey`, `lineKey`, `lcsPairs` |
| `frontend/src/lib/score/restLink.ts` | `applyRestLink`, `isRestOnlyEdit`, `applyGridRestEdit` |
| `frontend/src/lib/score/reconcile.ts` | `reconcile` |
| `frontend/src/lib/score/draft.ts` | `draftFromGrid`, `defaultAccent` |
| `frontend/src/lib/score/text.ts` | `changedLineTexts`, `spliceLineTexts` |
| `frontend/src/lib/score/saveFlow.ts` | `planContentSave`, `scoreContentOf`, `ScoreSaveError` |
| `frontend/src/lib/score/edit.ts` | `noteWindow`, `placeNote`, `moveNote`, `resizeNote`, `cycleAccent`, `unplaceNote`, `setMeterAt`, `setTempoAt`, `setPickup`, `setSnap`, `snapTick`, `unplacedSyllables`, `EditResult` / `EditRefusal` |
| `frontend/src/lib/score/layout.ts` | `staffBars`, `layoutStaff`, `rowAtTick`, `xOfTick`, `tickAtX`, `beatTicks`, `noteSegments`, `lineStarts` |
| `frontend/src/lib/score/playback.ts` | `playbackPlan`, `msToTick`, `ACCENT_GAIN` |
| `frontend/src/lib/score/track.ts` | `ScoreTrack`, `NO_SCORE`, `commitTrack`, `followGrid` |
| `frontend/src/lib/score/history.ts` | `History`, `record`, `undo`, `redo` |
| `frontend/src/lib/score/index.ts` | barrel |
| `frontend/src/lib/score/*.test.ts` | vectors (every case), save flow, edits, layout, playback, track and history |
| `frontend/src/types/index.ts` | `Snippet extends SnippetScoreFields` |
| `frontend/src/api/placements.ts` | `SnippetUpdateBody` gains `score`, `score_view` |
| `frontend/src/stores/canvasStore.ts` | optimistic `updateSnippet` with the save plan |
| `frontend/src/components/canvas/editorState.ts` | `replaceGridData` (rows take rest-linked lines) |
| `frontend/src/components/canvas/InlineGridEditor.tsx` | track, view toggle, Make score dialog mount, staff body, local undo, save body, export flush |
| `frontend/src/components/score/StaffEditor.tsx` | the staff editor |
| `frontend/src/components/score/StaffRowView.tsx` | one memoized SVG row (editor and compact) |
| `frontend/src/components/score/ScoreTray.tsx` | unplaced syllables; `SYLLABLE_DRAG_TYPE`, `parseSyllableDrag` |
| `frontend/src/components/score/BarChangeEditor.tsx` | meter / tempo change at a bar |
| `frontend/src/components/score/usePlayback.ts` | Web Audio playback hook |
| `frontend/src/components/score/staffGeometry.ts` | `EDITOR_STAFF`, `COMPACT_STAFF`, colours, `accentMark` |
| `frontend/src/components/score/compactStaff.ts` | `compactLayout`, `compactStaffShapes`, `showsStaff` |
| `frontend/src/components/score/CompactStaff.tsx` | list row staff |
| `frontend/src/components/canvas/staffDraw.ts` | Pixi `drawCompactStaff`, `createStaffTextStyles` |
| `frontend/src/components/canvas/SnippetCard.tsx`, `frontend/src/components/list/ListRow.tsx` | compact staff when `score_view` |
| `frontend/src/components/score/ScoreExportMenu.tsx` | (web-score-tools) gains `beforeExport` |

## Invariants

- The editor never holds an invalid score: every operation re-validates against the rows' grid
  (`checkScore`); the track's score always fits `rows.gridData` after the follow effect.
- `score.lines.length` equals the number of grid lines (non-blank rows).
- Local optimistic content equals the server's stored content: the client sends the output of the same
  algorithms the server runs, and those are idempotent on it.
- Notes keep their absolute ticks under meter, tempo and pickup changes (bar lines move, the rhythm does
  not); a new first meter that makes the pickup too long drops it to 0.
- Extra note keys (future pitch) survive every edit.
- `score_view` is false whenever `score` is null (store and editor).

## Decisions

1. **Reconcile from a base** while typing (not from the previous keystroke), so partial words do not
   lose notes; rest-only edits, staff edits and drafts move the base.
2. **No rest link while typing**: it would rewrite the row being typed; the save flow applies it, and the
   switch to the staff view applies it.
3. **Explicit save body**: the editor always sends the final score with the grid (not only the grid), so
   the server takes path (a) + (d) and both sides agree trivially.
4. **Optimistic `updateSnippet`** only when the save touches the score (local fields differ); other saves
   keep the existing wait-for-response behaviour.
5. **Editor-local undo** for the open editor (canvas undo would replace the store snapshot under an open
   editor); the canvas undo still covers the whole save afterwards.
6. **Default dropped note length**: an eighth (24 ticks), clamped into the free window.
7. **`MakeScoreMenu` dropped**: the merged `ScoreDraftDialog` (web-score-tools) provides the grid draft
   (local, through `localGridDrafter(rows.gridData)`) and the recording draft.
8. **Fixtures**: `score: null, score_view: false` added to three `Snippet` test fixtures (type maintenance
   for the new required fields; no assertion changed).
9. A snippet from a server without the fields reads as having no score (`scoreContentOf`).
