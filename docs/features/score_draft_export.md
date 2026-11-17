# Score Drafts and Export (musical mode, phase 2)

Two backend endpoints around a snippet's score ([score_format.md](./score_format.md),
normative spec [`spec/score_format.md`](../../spec/score_format.md)):

- `POST /api/snippets/{snippet_id}/score/draft` builds a first score, either laid out from the grid
  or timed from one of the snippet's voice takes, and returns it **without saving** it. The client
  shows it and saves it with `PUT /api/snippets/{id}` (feat/score-backend's save flow then validates
  it and applies the rest link).
- `GET /api/snippets/{snippet_id}/score/export?format=midi|musicxml` downloads the stored score as a
  Standard MIDI File or a MusicXML 4.0 document. Both writers are hand-written (no new dependency).

## Scope

- Grid drafts: the spec's `draft_from_grid` with typed errors and counts.
- Recording drafts: word alignment between the take and the grid, syllable times from vowel weights,
  tempo estimation with numpy when `bpm` is null, quantization to 1/16 or 1/16 triplets with a tempo
  follower, the pickup / bar 0 choice, default accents from stress.
- MIDI export (format 1, one track, tempo / meter / lyric meta events).
- MusicXML export (one percussion staff with one line, notes split at barlines with ties, tuplets,
  rests, lyrics with `syllabic`, accents, meter and tempo changes).
- Typed errors and ownership for both endpoints.

## Non-scope

- Saving drafts, migrations, model columns, the save flow, `invalid_score` on PUT
  (feat/score-backend).
- Client UI: draft dialog and export menu (feat/web-score-tools, feat/android-score-tools).
- Drafts or exports through a canvas share link (`/api/canvases/shared/{token}`): share views are
  unauthenticated and read-only; they get neither endpoint.
- Several takes merged into one draft, pitch, beat tracking with tempo changes inside a draft (a
  draft has exactly one meter and one tempo, at bar 0), MusicXML beaming and beat-aware note
  spelling, MIDI import.

## API

### `POST /api/snippets/{snippet_id}/score/draft`

Request (JSON, discriminated by `source`; unknown keys are rejected with FastAPI's plain 422):

```jsonc
// from the grid
{"source": "grid", "note": "1/8" | "1/16" | "1/32" | "1/8t" | "1/16t",
 "meter": {"num": int, "den": int}, "bpm": number}
// from a voice take on this snippet; bpm null or missing -> estimated
{"source": "recording", "recording_id": "<uuid>", "meter": {"num": int, "den": int}, "bpm": number | null}
```

Response 200 (`ScoreDraftResponse`):

```jsonc
{"score": Score,                 // format v1, valid against the snippet's current grid
 "bpm_estimated": number | null, // the tempo the server estimated (recording, bpm null); else null
 "unplaced": int,                // grid syllables without a note (0 for a grid draft)
 "unmatched_words": int}         // take words that matched no grid word (0 for a grid draft)
```

Example (recording, `bpm: null`, grid `the night is young`, take sung as `the` on a sixteenth
before the downbeat):

```json
{
  "score": {"v": 1, "ppq": 48, "pickup": 12, "meters": [{"bar": 0, "num": 4, "den": 4}],
            "tempos": [{"bar": 0, "bpm": 90}], "snap": "1/16",
            "lines": [{"notes": [{"s": 0, "t": 0, "d": 12, "a": 0}, {"s": 1, "t": 12, "d": 24, "a": 1},
                                 {"s": 2, "t": 36, "d": 12, "a": 0}, {"s": 3, "t": 48, "d": 48, "a": 1}]}]},
  "bpm_estimated": 90,
  "unplaced": 0,
  "unmatched_words": 0
}
```

Errors (checked in this order):

| Status | Body | When |
|--------|------|------|
| 422 | FastAPI validation list | body shape: unknown `source`, missing field, extra key, non-integer `num`/`den`, bad `recording_id` uuid |
| 404 | `{"error": "snippet_not_found", "message"}` | snippet missing or not the caller's |
| 404 | `{"error": "recording_not_found", "message"}` | recording missing, not the caller's, or on another snippet |
| 422 | `{"error": "recording_has_no_timings", "message"}` | the recording predates word timings (`words` or `line_spans` null) or its stored timings are unreadable |
| 422 | `{"error": "invalid_score", "reason": "meter_invalid" \| "tempo_invalid" \| "snap_invalid" \| "exceeds_max_ticks"}` | meter outside 1..32 / {1,2,4,8,16,32}, bpm outside 20..400 or with more than 2 decimals, unknown note; a draft too long for the format |
| 422 | `{"error": "draft_empty", "message"}` | grid without syllables, or no take word matched a grid word |

Order: the snippet, then (recording) the recording and its timings, then `meter`, `bpm` and (grid)
`note` in that order (the spec's `draft_from_grid` argument checks), then `draft_empty`, then
`exceeds_max_ticks`.

### `GET /api/snippets/{snippet_id}/score/export?format=midi|musicxml`

200 with the file: `Content-Type: audio/midi` or `application/vnd.recordare.musicxml+xml`,
`Content-Disposition: attachment; filename="<name>.mid"` / `"<name>.musicxml"`,
`Cache-Control: no-store`. `<name>` is the slug (lowercase ASCII letters and digits, other runs
become `-`, at most 48 characters) of the snippet's first non-blank text line; when that has no ASCII
letter or digit it is `snippet-<first 8 hex digits of the id>`. The title inside the file (MIDI
track name, MusicXML `work-title`) is that line with whitespace collapsed (at most 80 code points),
or `Snippet`.

| Status | Body | When |
|--------|------|------|
| 422 | FastAPI validation list | `format` missing or not `midi` / `musicxml` |
| 404 | `{"error": "snippet_not_found", "message"}` | snippet missing or not the caller's |
| 404 | `{"error": "score_not_found", "message"}` | the snippet's `score` is null |
| 500 | `{"error": "stored_score_invalid", "message"}` | the stored score does not validate against the stored grid (never expected; logged as an error) |

### Ownership

Like the other snippet routes (`DELETE /api/snippets/{id}`, `GET /api/snippets/{id}/recordings`): the
snippet must have `owner_id` = the caller, whatever canvas it is placed on, and a recording must
belong to that snippet (`snippet_recordings.snippet_id`). Login JWTs and API tokens both work.
Share-token canvas views are unauthenticated and read-only and have no draft or export route
(decision: exports are user actions on owned content; a public export can be added later on the
share route).

## Grid draft

`draft_grid(grid, meter, bpm, note)` is the spec's `draft_from_grid` (section 12: each syllable one
`note` long, each rest cell one beat unit `192 / den`, each line on a new bar, accent 1 for stress
1) plus `draft_empty` when the grid has no syllable. Every syllable is placed (`unplaced` 0); the
draft round-trips through the rest link (`apply_rest_link(draft, grid) == grid`).

## Recording draft

`draft_from_recording(grid, timings, meter, bpm)`, pure, in five steps. The constants are in the
modules named.

### 1. Word alignment

`draft/align.py`.

- **Grid words**: every word group of every grid line (`score.grid_view.word_groups`: by
  `word_index`, first appearance, anonymous cells alone), lines in order, with its syllable
  ordinals, syllable texts, default accents (stress 1 -> 1, else 0) and key
  `word_key(spelling)` (spec section 10: NFC, per-code-point lowercase, `’` -> `'`, letters, digits
  and apostrophes; `#` + text for punctuation-only words).
- **Take words**: the recording's `words` in service order with the same `word_key` of their text.
  Times are made monotonic: a start before the previous start is moved up to it, an end before its
  start is moved up to the start.
- **Exact pairs**: the spec's `lcs_pairs` (its tie-break) over the **reversed** take keys and the
  reversed grid keys, mapped back. On reversed sequences the equality-first walk pairs a word the
  grid holds several times with its **latest** occurrence: voice takes create a snippet or are
  appended at its end, so a take of a repeated line belongs to the last copy.
- **Gap fill**: between consecutive exact pairs (and before the first, after the last), a gap with
  the same number `n >= 1` of take words and grid words is paired position by position (`fill`
  pairs: a misheard `wee` for `we`, `r` for `are`). Unequal gaps stay unpaired.
- `unmatched_words` = take words with no pair. Grid words with no pair stay unplaced.

The alignment is global over all take words, not per take line: the snippet may have been edited or
re-split since the take, so take lines and grid lines need not correspond. `line_spans` are required
(a take without them is `recording_has_no_timings`) and validated through `voice.timings.Timings`
(spans tile the words), but they do not steer the alignment.

### 2. Syllable times

`draft/syllable_split.py`. A paired grid word of `k` syllables shares its take word's
`[start_ms, end_ms]`: syllable `r` gets the contiguous part proportional to its **vowel weight**, the
number of letters `a e i o u y` in the syllable text after NFD and lowercasing, at least 1 (`beaut
if ul` = 3 : 1 : 1, so `beautiful` sung over 1000 ms gives 600, 200, 200 ms). The first syllable of a
take word is a **real onset** (it feeds the tempo follower); the others are interpolated.

The placed syllables are then put in **score order** (line, then ordinal). A syllable that would
start before the previous kept syllable is dropped (unplaced): this only happens after a chunk drag
interleaved two words' syllables, where score order and sung order disagree.

### 3. Tempo estimate (bpm null)

`draft/tempo.py`, numpy only, deterministic.

- Input: the start times of **all** take words (paired or not; they are all real onsets), deduped.
- Intervals: differences at lags 1 and 2 between consecutive distinct onsets, kept when
  `0 < interval <= 1500 ms` (longer gaps are pauses, not rhythm).
- Candidates: bpm 60.00..180.00 in hundredths, a coarse pass every 0.5 bpm, then a fine pass every
  0.05 bpm within ±0.5 of the coarse best (clipped to the range).
- Score of a candidate: `fit - 0.1 * |log2(bpm / 95)|` with
  `fit = max(mean(cos(2 pi i / q16)), mean(cos(2 pi i / q16t)) - 0.1)`, `q16 = 15000 / bpm` ms (a
  sixteenth), `q16t = 10000 / bpm` ms (a sixteenth triplet). Intervals that are whole numbers of
  sixteenths score 1. Scoring intervals rather than absolute onsets needs no phase and tolerates
  drift (rubato).
- The prior breaks octave ties: straight sixteenths at 90 fit 180 just as well (as eighths) and the
  prior picks 90; ties between `x` and `2x` go to the tempo at or below `95 * sqrt 2 ≈ 134`.
  The 0.1 triplet penalty prefers a straight reading: sixteenths at 140 are not read as sixteenth
  triplets at 93.3.
- Best = first maximum (the slowest of equal candidates). A whole bpm next to it (floor or ceil)
  replaces it when it scores within 0.01 (millisecond rounding moves a 90 bpm take to 90.05).
- Fewer than 2 intervals: 90 (`DEFAULT_BPM`). The result has at most 2 decimals and lies in
  60..180, so it is a valid score tempo; it is reported as `bpm_estimated`.

Consequences (all tested): a take at 200 bpm in eighths reads as sixteenths at 100; sixteenths at
200 (75 ms) read as sixteenth triplets at 133.33; a pure triplet flow at 90 reads as straight
sixteenths at 135 (indistinguishable from onsets alone; the client can pass `bpm` to get triplets).

### 4. Quantization

`draft/quantize.py`. Ticks are absolute (`ppq` 48); the beat here is the quarter note (48 ticks),
because bpm counts quarters. Two lattices: straight (`1/16`, step 12) and triplet (`1/16t`, step 8),
both anchored at the **lattice origin** (the start of bar 0).

One pass over the placed syllables in score order:

1. The first syllable is tick 0.
2. Syllable `k > 0`: predicted position `p = tick[k-1] + (start_ms[k] - start_ms[k-1]) /
   (tick_ms * scale)` with `tick_ms = 60000 / bpm / 48` and the follower's `scale` (starts at 1).
3. Its beat `b = floor((p - origin) / 48)`. The first onset that reaches a beat decides the beat's
   lattice once: it looks ahead at every later syllable whose predicted position (from the same
   base) falls in that beat and takes **triplet** when the beat has at least 2 such onsets (or the
   previous beat was triplet) and `sum(err8) + m * n < sum(err12)`, where `errS` is the distance
   to the nearest point of step `S` and `m = 2` ticks per onset (`TRIPLET_MARGIN`), or `-1` when
   the previous beat was triplet (`TRIPLET_STAY_MARGIN`: a triplet run continues through ties).
   Triplets are disabled when the bar length is not a multiple of 8 ticks (for example 7/16).
4. The tick is `p` snapped to the beat's lattice (round half up). If that is not after the previous
   tick, it is the next lattice point after it, so notes are strictly ordered and never collide
   (zero-length or overlapping STT words still give distinct notes).
5. Tempo follower: at a real onset, with the previous real onset `j` and
   `0 < tick[k] - tick[j] <= 96` (two beats), `ratio = ms / (ticks * tick_ms * scale)` clamped to
   [0.8, 1.25] and `scale *= 1 + 0.2 * (ratio - 1)`, clamped to [2/3, 1.5]. A take that speeds up
   or slows down keeps its note values (quarters accelerating from 90 to 110 bpm stay quarters).

Durations, per syllable with step `S` of its beat and the `scale` at its onset:

- **Legato**: if the next syllable starts less than half a step after this one ends, the note lasts
  until the next note (`d = tick[k+1] - tick[k]`); syllables split from one word are always legato.
- Otherwise `d = max(S, round(sung length / S) * S)`, at most the room to the next note; the last
  note has no cap. The rest link later turns the remaining gap into `floor(gap / beat unit)` rest
  cells.

**Pickup / bar 0.** Tick 0 is always the first placed syllable (the start of the pickup, or of bar 0
when there is none). Bar 0 starts at the **first accented syllable** (grid stress 1) whose first-pass
tick is inside the first bar (`< bar length`):

- If that syllable is the first one, or there is none in the first bar, `pickup = 0`.
- Otherwise a second pass runs with the lattice origin at that syllable's first-pass tick `r`, so
  both lattices line up with bar 0, and `pickup` = the syllable's second-pass tick (used when
  `0 < pickup < bar length`; otherwise the first pass with `pickup = 0` is kept).

So `the NIGHT is young` with `the` a sixteenth early gets `pickup = 12` and `night` on the downbeat;
`and the MORN-ing` in 3/4 gets a two-eighth pickup of 48 ticks.

### 5. Score

`meters = [{bar 0, num, den}]`, `tempos = [{bar 0, bpm}]` (the given or estimated bpm), the pickup,
`snap = "1/16t"` when more beats were quantized as triplets than as straight, else `"1/16"`, and one
line per grid line (lines the take did not reach have no notes). Accents are the defaults (stress 1
-> 1, else 0). The score is validated against the grid (`check_typed`); the only possible failure
is `exceeds_max_ticks` for a take longer than the format holds. `unplaced` = grid syllables -
notes.

### Rest link

The draft is valid for the grid it was made from, and `apply_rest_link` is idempotent on it. It is
not always a no-op: rests between two placed syllables become `floor(gap / beat unit)` of the take's
gap, so saving a draft rewrites those rests (and their `_` in the text) to what was sung. Rests next
to unplaced syllables and at line edges are kept. A take whose pauses match the grid's rests leaves
the grid unchanged (tested). This is the "linked to rests" rule of the contract applied to a new
score, and it happens on save, not in the draft endpoint (the response has no grid).

## MIDI

`export/midi.py`, `write_midi(score, grid, title) -> bytes`.

- Header `MThd`: format 1, **one track**, division 48 (the score's ppq, ticks map 1:1).
- Track events, sorted by tick, then note off < meta (tempo, meter) < lyric < note on:
  - tick 0: track name (`FF 03`, the title, UTF-8), time signature of `meters[0]`, tempo of
    `tempos[0]`;
  - a time signature (`FF 58 04 nn dd cc 08`, `dd = log2 den`, `cc = 96 / den` MIDI clocks per
    click, `* 3` for compound meters) at every later meter's bar start, and a tempo (`FF 51 03`,
    `round(60 000 000 / bpm)` µs per quarter) at every later tempo's bar start, also past the last
    note;
  - per note: a lyric (`FF 05`, the syllable text, followed by `-` when more syllables of the same
    word follow; omitted for an empty syllable), note on and note off.
- Pitch and channel (decision): **middle C (60) on channel 1** rather than GM percussion side stick
  on channel 10: the file is a vocal rhythm line, and a DAW or notation program can give it a melody
  or any sound; channel 10 would lock it to drums. Velocity by accent: 0 -> 64, 1 -> 96, 2 -> 120.
  Note off is `80 3C 00`.
- **Pickup**: MIDI has no partial first bar, so every event moves right by
  `bar_length(meters[0]) - pickup` ticks (when `pickup > 0`): MIDI bar 1 is a full bar whose tail is
  the pickup, and the score's bar 0 is MIDI bar 2.
- End of track at the end of the last bar (`bar_start_tick(bar_count)`, shifted), or at the last
  meta event when later.
- Variable-length quantities up to `0x0FFFFFFF`; no running status.

## MusicXML

`export/notation.py` (measures and values) and `export/musicxml.py` (the document, built with
`xml.etree.ElementTree`, so text is escaped).

- `<?xml ... encoding="UTF-8"?>`, the MusicXML 4.0 partwise DOCTYPE, `score-partwise version="4.0"`,
  `work-title`, `encoding/software`, one `score-part` `P1` "Voice".
- Measures: the pickup as measure `0 implicit="yes"` (length `pickup`) when `pickup > 0`, then bar
  `b` as measure `b + 1` for `b` in `0 .. bar_count - 1`. Meter and tempo changes after the last
  bar holding notes are not written.
- First measure: `divisions` 48 (a duration is its tick count), the time signature, `clef` sign
  `percussion` and `staff-details/staff-lines` 1. A later measure gets `time` when a meter starts at
  its bar. Tempo: `direction placement="above"` with `metronome` (quarter = bpm) and
  `sound tempo` at the first measure and at every tempo change bar.
- Every note and every gap is cut at barlines; each part is written as notated values:
  `decompose(ticks)` takes, when `ticks % 3 != 0`, one triplet value (the largest of 64, 32, 16, 8,
  4, 2, 1 ticks with the same remainder mod 3) and fills the rest greedily with straight and dotted
  values (288 dotted whole .. 3 = 64th). Parts of one note are tied (`tie` + `notations/tied`
  start / stop).
- Notes are `unpitched` B4 (the staff's one line), `voice` 1. Triplet values carry
  `time-modification` 3:2; each run of consecutive triplet values (notes or rests) in a measure is
  bracketed by `tuplet type="start" bracket="yes"` on its first and `tuplet type="stop"` on its last
  piece (both on a run of one).
- Gaps are rests decomposed the same way; a measure with no note is one `<rest measure="yes"/>` with
  the bar's duration.
- The first piece of a note carries the accent (`articulations/accent` for 1, `strong-accent
  type="up"` for 2) and `lyric number="1"` with `syllabic` (single / begin / middle / end by the
  syllable's position in its word group, `export/lyrics.py`) and `text`; tied continuations carry
  neither. An empty syllable has no lyric.

Measures always sum to their bar lengths (the pickup to `pickup`), tested for drafts in 4/4, 3/4,
6/8, 7/8 and 5/16.

## Data and control flow

```
POST /api/snippets/{id}/score/draft  (api/score_tools.py)
  body: GridDraftRequest | RecordingDraftRequest (schemas/score_tools.py, pydantic shape only)
  -> service.draft(db, id, owner, GridDraft | RecordingDraft)
       load_snippet: SELECT id, raw_text, grid_data, score WHERE id AND owner_id   -> 404 snippet_not_found
       grid:      draft_grid -> draft_from_grid (ScoreInvalid) -> draft_empty when no note
       recording: load_timings: SELECT words, line_spans WHERE id AND snippet_id AND owned
                    -> 404 recording_not_found; null / unreadable -> 422 recording_has_no_timings
                  draft_from_recording(grid, timings, meter, bpm)
                    parse_meter / parse_bpm (ScoreInvalid meter_invalid / tempo_invalid)
                    align.grid_words + take_words -> align_words (reversed lcs_pairs + gap fill)
                    syllable_times (split_word by vowel weight, score order, drop out-of-order)
                    -> draft_empty when nothing placed
                    tempo.estimate_bpm(word starts) when bpm is null
                    quantize.quantize(syllables, bpm, bar length) -> ticks, durations, pickup
                    Score(...) -> check_typed(score, grid); log score.draft.recording ...
  <- ScoreDraftResponse {score: ScoreModel.from_score, bpm_estimated, unplaced, unmatched_words}
  ScoreToolError -> {error, message} with its status; ScoreInvalid -> 422 as_dict()

GET /api/snippets/{id}/score/export?format=midi|musicxml
  -> service.export: load_snippet -> stored_score (score null -> 404 score_not_found;
       check_score fails -> 500 stored_score_invalid, logged)
     -> write_midi | write_musicxml (title = export_title(raw_text)); log score.export ...
  <- Response(bytes, media type, Content-Disposition attachment; filename=export_filename(...))
```

Nothing is written to the database. Logging is key=value: `score.draft.grid snippet_id notes`,
`score.draft.recording take_words matched filled placed unplaced bpm estimated fit pickup`,
`score.draft.timings_malformed recording_id error` (warning), `score.export snippet_id format notes
bytes`, `score.export.stored_invalid snippet_id reason` (error). Lyrics text is never logged.

## Files

| File | Role | Key exports |
|------|------|-------------|
| `backend/app/api/score_tools.py` | Routes, error mapping | `router`, `draft_score`, `export_score`, `tool_http_error`, `invalid_score_http_error` |
| `backend/app/schemas/score_tools.py` | Wire shapes | `DraftMeter`, `GridDraftRequest`, `RecordingDraftRequest`, `ScoreDraftRequest`, `ScoreDraftResponse`, `ScoreToolErrorBody`/`Response` (the invalid_score body is score-backend's `ScoreInvalidResponse`) |
| `backend/app/services/score_tools/errors.py` | Typed errors | `ScoreToolErrorCode`, `ScoreToolError` (code, status, message), `SnippetNotFoundError`, `RecordingNotFoundError`, `RecordingHasNoTimingsError`, `DraftEmptyError`, `ScoreNotFoundError`, `StoredScoreInvalidError` |
| `backend/app/services/score_tools/service.py` | DB lookups, ownership, orchestration | `ExportFormat`, `SnippetContent`, `GridDraft`, `RecordingDraft`, `DraftRequest`, `ExportFile`, `load_snippet`, `load_timings`, `draft`, `stored_score`, `render`, `export` |
| `backend/app/services/score_tools/draft/types.py` | Draft value types | `DraftResult`, `GridWord`, `TakeWord`, `PairKind`, `WordPair`, `Alignment`, `SyllableTime`, `Grid` (lattice, `step`), `QuantizedNote`, `Quantized` |
| `backend/app/services/score_tools/draft/grid.py` | Grid draft | `draft_grid` |
| `backend/app/services/score_tools/draft/align.py` | Word alignment | `grid_words`, `take_words`, `align_words` |
| `backend/app/services/score_tools/draft/syllable_split.py` | Syllable times | `vowel_weight`, `split_word`, `VOWELS` |
| `backend/app/services/score_tools/draft/tempo.py` | Tempo estimate (numpy) | `estimate_bpm -> TempoEstimate(bpm, fit, intervals)`, `onset_intervals`, constants (`DEFAULT_BPM`, `TRIPLET_PENALTY`, `PRIOR_*`, ...) |
| `backend/app/services/score_tools/draft/quantize.py` | Quantization, follower, pickup | `quantize -> Quantized`, `choose_grid`, `snap`, `next_point_after`, constants |
| `backend/app/services/score_tools/draft/recording.py` | Recording draft | `draft_from_recording`, `parse_meter`, `parse_bpm`, `syllable_times` |
| `backend/app/services/score_tools/export/lyrics.py` | Lyric text and syllabic per note | `Syllabic`, `Lyric` (`continues`), `line_lyrics`, `note_lyrics` |
| `backend/app/services/score_tools/export/notation.py` | Measures, values, ties | `NoteValue`, `STRAIGHT_VALUES`, `TRIPLET_VALUES`, `decompose`, `Piece`, `Measure`, `measures` |
| `backend/app/services/score_tools/export/midi.py` | MIDI writer | `write_midi`, `events`, `track`, `vlq`, `meta`, `time_signature`, `tempo`, `pickup_offset`, `PITCH`, `VELOCITY` |
| `backend/app/services/score_tools/export/musicxml.py` | MusicXML writer | `write_musicxml`, `build` |
| `backend/app/services/score_tools/export/filename.py` | Titles and file names | `export_title`, `export_filename`, `slug` |
| `backend/app/main.py` | Router include | |
| `backend/tests/score_tools/` | `test_align`, `test_syllable_split`, `test_tempo`, `test_quantize`, `test_recording_draft` (synthetic takes: steady, triplets, pickups, rubato, missing / extra / misheard words, appended takes, interleaving, random grids), `test_grid_draft`, `test_notation`, `test_midi` (with the test-only `midi_reader.py`), `test_musicxml` (xml.etree), `test_filename`, `test_routes` (lookups faked), `test_score_tools_db` (real Postgres, `TAG_TEST_DATABASE_URL`); `takes.py` builds synthetic takes | |
| `docs/api/openapi.json` | Snapshot with the two routes | |

## Invariants

- Neither endpoint writes; a draft is valid (`check_score`) against the snippet's grid at the time of
  the request, and `apply_rest_link` is idempotent on it.
- Recording drafts: notes strictly ordered in time across the whole score (single voice), tick 0 is
  the first placed syllable, `0 <= pickup < bar_length(meters[0])`, one meter and one tempo at bar 0,
  every placed syllable belongs to a take word paired with its grid word, `unplaced + notes = grid
  syllables`, `unmatched_words + pairs = take words`, deterministic for the same input.
- The estimated bpm is in 60..180 with at most 2 decimals (`is_valid_bpm`).
- Grid drafts place every syllable and round-trip through the rest link unchanged.
- MIDI: format 1, one track, division 48, note on / off pairs at `t` / `t + d` (shifted by the pickup
  offset), one lyric per note with text, ends with end-of-track.
- MusicXML: every measure's durations sum to its bar length (pickup measure: `pickup`), every note's
  pieces sum to `d` and are tied, every triplet value has `time-modification` 3:2 and sits inside a
  tuplet bracket run, one lyric per note on its first piece.
- Only the snippet's owner can draft or export; a recording is only used by its own snippet.

## Decisions

Deviations from the round-4 contract §5 and choices it left open:

1. **`score_not_found` is for export only**; a missing or foreign snippet is `snippet_not_found`
   (404) on both endpoints, like the voice routes. The contract listed `score_not_found` among the
   draft errors; a draft does not need a stored score.
2. **Extra error codes**: `snippet_not_found` (above) and `stored_score_invalid` (500, a stored score
   that no longer fits its grid; never expected). Bad meter / bpm / note values answer the shared
   `invalid_score` with the spec's reasons (`meter_invalid`, `tempo_invalid`, `snap_invalid`), and an
   enormous draft `exceeds_max_ticks`.
3. **`draft_empty` applies to grid drafts too** (a grid with no syllable).
4. **Recording drafts need `line_spans`** like `has_timings`, but the alignment is global over the
   take's words; spans are validated, not used to pair lines (the text may have changed since).
5. **Latest occurrence wins** in the alignment (reversed LCS), because takes are appended at the end
   of a snippet. Equal-size gaps are filled by position (misheard words).
6. **"First onset becomes the pickup / bar 0 boundary"** is read as: tick 0 is the first placed
   syllable, and bar 0 starts there unless an accented syllable later in the first bar takes the
   downbeat (then the syllables before it are the pickup).
7. **1/16t "when it fits clearly better"**: decided per quarter-note beat, by summed lattice error
   with a 2-tick-per-onset margin and hysteresis (a triplet run continues through near-ties); never
   for bar lengths that are not a multiple of 8 ticks. `snap` is `1/16t` when triplet beats
   outnumber straight beats.
8. **Tempo follower**: a small proportional follower keeps note values through rubato; the score
   still gets one tempo (the given or estimated bpm), not a tempo map.
9. **Estimate range and prior**: 60..180 is the search range (not only a preference): faster takes
   read at half time or as triplets, slower ones at double time. Octave ties go to the tempo at or
   below about 134 (prior centre 95); a whole bpm within 0.01 of the best wins.
10. **`unmatched_words`** counts take words without a grid partner; grid words without a take word
    show in `unplaced` (per syllable).
11. **MIDI pitch**: middle C on channel 1, not GM side stick on channel 10. **MIDI pickup**: written
    as the tail of a full first bar.
12. **MusicXML**: B4 unpitched on a one-line percussion staff; values are not split at beats (only at
    barlines), so syncopations may show as long values rather than beat-aware ties; beams are left
    to the reader.
13. **Ownership**: owner only; share-token views have neither endpoint.
14. **File names** are ASCII slugs of the first text line (fallback `snippet-<id8>`), so the header
    needs no RFC 5987 encoding; the title inside the file keeps the full Unicode line.
