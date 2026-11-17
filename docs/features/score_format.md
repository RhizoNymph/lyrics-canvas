# Score Format (musical mode, phase 1)

A snippet can carry a **score**: its syllables placed on a one-line rhythm staff (start, duration,
accent per syllable) in a time signature, with pickups and meter / tempo changes at any bar. The score
is a separate layer over the beat grid, "linked to rests": gaps between placed syllables are written
into the grid as rest cells (and so into the text as `_`), and rest edits in the grid move the staff.

This document covers phase 1: the format, the shared algorithms, the Python reference, the vectors
and the wire types. The normative definition is [`spec/score_format.md`](../../spec/score_format.md);
where this page and the spec differ, the spec wins.

## Scope

- Score format v1 (JSON): `v`, `ppq` (48), `pickup`, `meters`, `tempos`, `snap`, `lines` of notes
  `{s, t, d, a}` plus preserved extra note keys.
- The shared algorithms: `validate` (24 reason codes in a fixed check order), bars and timing
  (`bar_start_tick`, `bar_length`, `bar_at_tick`, `beat_unit`, `metric_weight`, `tick_to_ms`,
  `end_tick`, `bar_count`), the rest link in both directions (`apply_rest_link`,
  `apply_grid_rest_edit`, `is_rest_only_edit`), `reconcile` after text edits (normalized word keys,
  LCS with a fixed tie-break), `draft_from_grid`, and the snippet text splice
  (`splice_line_texts`, `changed_line_texts`).
- The Python reference in `backend/app/services/score/` with typed errors and a pydantic wire model.
- `spec/score_vectors.json` (version 1), generated from the reference, asserted by backend tests and
  read by the Android wire-model test.
- Wire types: `frontend/src/types/score.ts` and `android/core/model/.../model/score/`.

## Non-scope (phase 2 branches)

- Snippet columns, migration 012, API fields, `invalid_score` HTTP mapping, server save flow, agent
  `set_score` (feat/score-backend).
- Drafts from recordings, tempo estimation, MIDI / MusicXML export (feat/score-draft-export-backend).
- Rhythm fit in rhyme-suggest (feat/rhyme-rhythm-fit-backend).
- The TS and Kotlin ports of the algorithms, editors, playback and store fields
  (feat/web-score-editor, feat/web-score-tools, feat/android-score-editor, feat/android-score-tools).
- Merging scores (a merged snippet gets no score), pitch.

## Data and control flow

```
client edit ──► local port of the same algorithms (optimistic / offline state)
              │
              ▼
PUT /api/snippets/{id} (phase 2) ──► server, authoritative:
   (a) score sent       ─► check_score(raw, grid)            ─► ScoreInvalid → 422 invalid_score {reason, line?, note?}
   (b) grid changed, no score sent:
         is_rest_only_edit(old, new)? ─ yes ─► apply_grid_rest_edit(score, old, new) → (score, grid)
                                        └ no ─► reconcile(score, old, new)
   (d) always          ─► grid' = apply_rest_link(score, grid)
                          raw_text' = splice_line_texts(raw_text, changed_line_texts(grid, grid'))
   store score JSON = score_to_json(score), grid', raw_text'

score JSON ─ check_score ─► Score (frozen dataclasses) ─ algorithms ─► Score ─ score_to_json ─► JSON
grid JSON  ──────────────────────────────────────────────► (read as JSON; returned grids are new dicts,
                                                            untouched lines are the same objects)
timing: Score ─► bar_at_tick / beat_unit / metric_weight / tick_to_ms (playback, rhythm fit, export)
draft:  grid + meter + bpm + note ─► draft_from_grid ─► Score (round-trips through the rest link)
```

Inside the reference:

- `validate.check_score` walks the JSON once in the spec's order and builds the typed `Score`;
  `check_typed` re-validates a typed score against another grid (it round-trips through
  `score_to_json`). Every algorithm validates its input against the grid it is given and validates its
  output, so a `Score` leaving the package is always valid for the grid it was produced for.
- `grid_view` is the only place that interprets cells: rest = `rest is True`, syllable ordinals, rests
  between syllables, word groups (by `word_index`, first appearance, anonymous cells alone) and their
  spelling (as `rest_marks.rebuild_line_text`).
- `rest_link._linked_cells` rebuilds a line's cells (keeping the first existing rest cells of a run,
  adding `{"rest": true}`), and a changed line gets `raw_text = rebuild_line_text(cells)`.
- `apply_grid_rest_edit` walks lines in order: ripple shift right past the previous lines, then the
  changed placed-placed boundaries left to right (`delta * beat_unit(end of the left note)`, removals
  clamped to closing the gap), validates (overflow refuses), then applies the rest link.
- `reconcile` pairs lines (by index when the counts match, else LCS of line keys), pairs words (LCS of
  word keys), maps the first `min` syllables of each matched word, sorts by `s` and drops candidates
  that would start before the previous kept note ends.
- `scripts/gen_score_vectors.py` builds every vector from hand-written cases plus cases from a
  self-contained LCG (`scripts/score_vector_random.py`), so the file does not depend on the Python
  version; `tests/score/test_vectors.py` re-renders it and compares byte for byte.

## Files

| File | Role | Key exports |
|------|------|-------------|
| `spec/score_format.md` | Normative spec: format, every algorithm, decisions, vectors layout | |
| `spec/score_vectors.json` | Vectors v1 (15 sections, about 1 MB) | |
| `backend/app/services/score/__init__.py` | Package API | everything below |
| `backend/app/services/score/constants.py` | Format numbers | `PPQ`, `WHOLE`, `MAX_BAR`, `MAX_TICKS`, `MAX_METERS`, `MAX_TEMPOS`, `MAX_SAFE_INT`, `DENS`, `BPM_MIN`, `BPM_MAX` |
| `backend/app/services/score/types.py` | Typed format | `Score`, `ScoreLine`, `Note`, `Meter`, `Tempo`, `Snap`, `SNAP_TICKS`, `GridJson`, `JsonValue` |
| `backend/app/services/score/errors.py` | Typed errors | `ScoreReason`, `ScoreError`, `ScoreInvalid` (`reason`, `line`, `note`, `as_dict()`), `NotRestOnlyEdit`, `ScoreTextMismatch`, `ScoreArgumentError` |
| `backend/app/services/score/codec.py` | Canonical JSON out | `score_to_json`, `note_to_json` |
| `backend/app/services/score/validate.py` | validate | `check_score(raw, grid) -> Score`, `validate(raw, grid) -> Valid \| Invalid`, `check_typed`, `as_integer`, `is_valid_bpm`, `is_valid_meter_value`, `parse_snap` |
| `backend/app/services/score/grid_view.py` | Grid reading | `is_rest`, `grid_lines`, `line_cells`, `syllable_positions`, `syllable_counts`, `rests_between`, `WordGroup`, `word_groups`, `syllable_identity` |
| `backend/app/services/score/timing.py` | Bars and time | `bar_length`, `bar_start_tick`, `bar_at_tick -> BarPosition`, `meter_at_bar`, `meter_at_tick`, `tempo_at_bar`, `beat_unit`, `is_compound`, `metric_weight`, `tick_to_ms`, `end_tick`, `bar_count`, `PICKUP_BAR` |
| `backend/app/services/score/rest_link.py` | Rest link | `apply_rest_link`, `is_rest_only_edit`, `apply_grid_rest_edit -> GridEditResult(score, grid)` |
| `backend/app/services/score/normalize.py` | Keys and LCS | `normalize_word`, `word_key`, `word_keys`, `line_key`, `lcs_pairs` |
| `backend/app/services/score/reconcile.py` | reconcile | `reconcile` |
| `backend/app/services/score/draft.py` | Grid draft | `draft_from_grid(grid, meter, bpm, note)`, `default_accent` |
| `backend/app/services/score/text.py` | Text splice | `splice_line_texts`, `changed_line_texts` |
| `backend/app/services/score/wire.py` | Pydantic wire model (not yet wired into schemas) | `ScoreModel` (`from_score`, `to_json`, `to_score`), `ScoreNoteModel` (extra keys kept), `ScoreLineModel`, `ScoreMeterModel`, `ScoreTempoModel`, `SnapValue` |
| `backend/scripts/gen_score_vectors.py` | Vector generator | `VECTORS_PATH`, `build_vectors`, `render_vectors` |
| `backend/scripts/score_vector_random.py` | Deterministic random grids, scores, rest edits and text edits (also used by property tests) | `Lcg`, `random_grid`, `random_line`, `random_score`, `rest_edit`, `text_edit` |
| `backend/tests/score/` | Unit tests per module, vectors test, property tests | |
| `frontend/src/types/score.ts` | TS wire types (types and constants only) | `Score`, `ScoreLine`, `ScoreNote`, `ScoreMeter`, `ScoreTempo`, `ScoreSnap`, `ScoreAccent`, `ScoreMeterDen`, `SnippetScoreFields`, `ScoreInvalidReason`, `ScoreInvalidBody`, `SCORE_SNAP_TICKS`, `SCORE_SNAPS`, `SCORE_INVALID_REASONS`, `SCORE_PPQ`, `SCORE_MAX_TICKS` |
| `android/core/model/src/main/kotlin/dev/nymph/lyrics/model/score/Score.kt` | Kotlin wire model (kotlinx.serialization, `LyricsJson`) | `Score`, `ScoreMeter`, `ScoreTempo`, `ScoreLine`, `ScoreSnap`, `ScoreAccent`, `ScoreInvalidReason`, `ScoreInvalidDetail` |
| `android/core/model/src/main/kotlin/dev/nymph/lyrics/model/score/ScoreNote.kt` | Note with preserved extra keys | `ScoreNote`, `ScoreNoteSerializer` |
| `android/core/model/src/test/kotlin/dev/nymph/lyrics/model/score/ScoreDtoTest.kt` | Wire model test, reads every vector grid and canonical score | |
| `android/core/model/build.gradle.kts` | `scoreVectors` system property for the test | |

## Invariants

- A `Score` produced by the package is valid against the grid it was produced for (inputs and outputs
  are validated); `score_to_json` writes the canonical key order with integers.
- Single voice: notes are strictly ordered in time across the whole score and never overlap.
- `apply_rest_link` changes only rest cells between two consecutive placed syllables; syllables, words,
  stress and every other key stay; untouched lines are returned as the same objects; it is idempotent.
- `apply_grid_rest_edit` keeps every note's `s`, `d`, `a` and extra keys, never moves a later line left,
  never makes note `k+1` start before note `k` ends, and returns a grid on which the rest link is a no-op.
- `reconcile` only drops or re-numbers notes (never changes `t`, `d`, `a` or extras), keeps meters,
  tempos, pickup and snap, and is the identity for an unchanged grid.
- `draft_from_grid` round-trips: `apply_rest_link(draft, grid) == grid`.
- `spec/score_vectors.json` equals `render_vectors()` byte for byte; every grid in it conforms to the
  `GridCell` wire type (cases with extra grid keys carry `unknown_grid_keys: true`).

## Decisions

Listed in full in [spec section 14](../../spec/score_format.md#14-decisions). Deviations from the
round-4 contract:

- **Draft rests take one beat unit** (`192 / den`), not one `note` length: with note lengths the rest
  link would erase every rest of a fresh draft (floor(24/48) = 0), so a draft would not survive its
  first save.
- **Kotlin wire model in `core/model`** (`dev.nymph.lyrics.model.score`), not in `app/`: every other
  wire model lives there and the phase-2 Kotlin port in `core/logic` needs it.
- **The rest link rewrites `raw_text` only on lines whose cells changed**, and the snippet text is
  updated by splicing those lines (`splice_line_texts`), because rebuilding untouched lines from cells
  without `word` would respell them.

## For phase 2

- Request bodies: accept the score as raw JSON and call `check_score(raw, grid)` (reasons and order
  match the ports); `ScoreModel` is for responses and the OpenAPI document. Errors map to
  `422 {"detail": {"error": "invalid_score", "reason", "line"?, "note"?}}` via `ScoreInvalid.as_dict()`.
- Decide the save flow with `is_rest_only_edit(old_grid, new_grid)`: rest-only edits go through
  `apply_grid_rest_edit`, every other grid change through `reconcile`; finish with `apply_rest_link`
  and `splice_line_texts(raw_text, changed_line_texts(grid, linked))`.
- Ports read `spec/score_vectors.json` in place (web: `new URL('../../../../spec/score_vectors.json',
  import.meta.url)`; Android: a Gradle `systemProperty` like `scoreVectors` in `core/model`). Compare
  as JSON values, `tick_to_ms` to 1e-6; typed Kotlin grids may skip or relax `unknown_grid_keys` cases.
- `validate` vectors feed raw JSON (including `12.0` integers, booleans, strings): ports validate a
  JSON value (TS `unknown`, Kotlin `JsonElement`), not a typed model.
