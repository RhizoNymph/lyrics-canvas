# Rhyme Rhythm Fit

"Fit rhythm" for `POST /api/nlp/rhyme-suggest`: when the anchor snippet has a score (musical mode,
[score_format.md](./score_format.md), normative spec `spec/score_format.md`), the rhyme list keeps only
candidates with the slot's syllable count and orders them by how well their stress sits on the slot's
rhythm. Backend only; the web and Android "Fit rhythm" toggles are in the score-tools branches.

## Scope and non-scope

Scope:

- The request flag `rhythm_fit`, the response fields `rhythm_applied` and
  `rhythm_unavailable_reason`, the per-candidate `rhythm_fit`.
- The slot rhythm: per slot syllable, the `metric_weight` of its note's onset and the note's accent,
  read from the anchor snippet's stored score.
- The syllable-count filter (before paging and `total`).
- The deterministic rhythm-fit score in [0, 1] and the ordering band -> rhythm fit -> similarity
  (meaning order) or rhyme score.

Non-scope:

- Rhyme-pattern slot columns and word bank rhymes (no rhythm there; `PatternCandidateModel` has no
  `rhythm_fit`).
- Note durations, tempo, the candidate's own rhythm (a dictionary word has none), pitch.
- Migrations or snippet columns: `snippets.score` is owned by feat/score-backend (migration 012).
- Client toggles and the percent display.

## Wire

Request (`RhymeSuggestRequest`, `app/schemas/rhyme.py`):

```jsonc
{ /* existing fields */ "rhythm_fit": false }   // bool, default false
```

Response (`RhymeSuggestResponse`):

```jsonc
{
  /* existing fields */
  "rhythm_applied": false,                // true iff rhythm_fit was asked and the slot has a rhythm
  "rhythm_unavailable_reason": null,      // "no_score" | "slot_unplaced" | null
  "candidates": [{ /* existing fields */ "rhythm_fit": 0.75 }]   // number in [0,1] (4 decimals) | null
}
```

- `rhythm_fit: false` (or absent): `rhythm_applied: false`, reason `null`, every `rhythm_fit: null`;
  the candidates, order and `total` are exactly today's.
- `rhythm_fit: true` and not applicable: `rhythm_applied: false`, reason set, every `rhythm_fit:
  null`, candidates / order / `total` exactly as with `rhythm_fit: false`.
  - `no_score`: the first anchor is a text anchor, or its snippet has no score (or a stored score
    that no longer validates against the stored grid, logged as `rhythm_fit.stored_score_invalid`).
  - `slot_unplaced`: at least one slot syllable has no note (or the slot has no syllable cell).
- `rhythm_fit: true` and applicable: `rhythm_applied: true`, reason `null`, only candidates with exactly
  the slot's syllable count (dropped from the list and from `total`), each with its `rhythm_fit`.

The candidate model is `RhymeSuggestCandidateModel` (a subclass of `RhymeCandidateModel` with
`rhythm_fit`), so pattern candidates keep their wire shape.

## The slot

The slot is the request's **first** anchor, matching how the clients derive `slot` (first anchor).

- Text anchor: no score -> `no_score`.
- Snippet anchor: the resolved line (`line_index`, or the last line with words when null) and word
  range `[word_start, word_end)` (whole line without a range). Slot syllables are the syllable cells
  of that grid line whose `word_index` is in the range, in order; each is identified by its ordinal
  among the line's syllable cells (rests not counted), which is what notes' `s` refers to.
- Every slot syllable must have a note in `score.lines[line]`; then per syllable
  `SlotBeat(onset = note.t, weight = metric_weight(score, note.t), accent = note.a)`.

The slot's own rests are expressed by the onset ticks; they never become slot positions. The request's
`slot` (manual or from the grid, used for `fits_slot`) is independent and unchanged.

## The score

Integer levels, exact rationals, one rounding at the end (4 decimals, like `similarity`), so the value
is deterministic and identical in any port.

1. **Slot level** per syllable, 0..3: `max(metric_weight, ACCENT_LEVEL[accent])` with
   `ACCENT_LEVEL = {0: 0, 1: 2, 2: 3}`. Accent 1 counts like a beat, accent 2 like a downbeat; an
   accent only raises a level (an unaccented syllable on a downbeat is still metrically strong).
2. **Target** per syllable, 0..1: when the slot's levels differ, `(level - lo) / (hi - lo)` (the
   strongest slot syllable is 1, the weakest 0); when they are all equal (including a one-syllable
   slot), `level / 3`.
3. **Candidate prominence** per syllable from its stress digit: primary `1` -> 1, secondary `2` -> 1/2,
   unstressed `0` -> 0.
4. **Fit** = mean over syllables of `1 - |prominence - target|`, rounded to 4 decimals.

Why this shape:

- Stress is relative, so the targets are normalized within the slot: a two-syllable slot on beat 2 and
  its "and" asks for a trochee as clearly as one on a downbeat; a syllable on beat 4 leading into the
  downbeat is the weak one (iamb), even though beat 4 is a beat.
- A slot without contrast (a single syllable, or all syllables equally strong) has nothing to
  normalize against; its absolute strength says whether a stress belongs there.
- Secondary stress sits halfway, so a secondary-stressed syllable on a beat next to a downbeat scores
  better than a primary one, and `"12"` is neither a full match nor a full miss for an iambic slot.
- Mean absolute agreement is symmetric: a stress on a weak position costs as much as a missing stress
  on a strong one, which is the same rule `fits_slot` applies as a hard check.

Rests in candidates: a candidate's stress string has one digit per syllable (engine units never put
rests in `stress`), and a note candidate's `grid_line` can contain interior rest cells. Rests are not
syllables: `candidate_stresses` skips `_` marks, so a rest neither counts toward the syllable count nor
takes a slot position; the candidate's syllables are sung on the slot's onsets.

### Examples (4/4)

| slot (weight, accent) | levels | targets | candidate | fit |
|---|---|---|---|---|
| downbeat, 16th after (3,0) (0,0) | 3, 0 | 1, 0 | `10` letter | 1.0 |
| | | | `01` delight | 0.0 |
| | | | `12` midnight | 0.75 |
| | | | `11` at night | 0.5 |
| "and" of 4 -> downbeat (1,0) (3,1) | 1, 3 | 0, 1 | `01` delight | 1.0 |
| | | | `21` alright | 0.75 |
| | | | `12` midnight | 0.25 |
| same, "and" accented 2 (1,2) (3,1) | 3, 3 | 1, 1 | `01` delight | 0.5 |
| | | | `12` midnight | 0.75 |
| downbeat, 16th, beat (3,0) (0,0) (2,0) | 3, 0, 2 | 1, 0, 2/3 | `102` | 0.9444 |
| | | | `101` | 0.8889 |
| | | | `100` | 0.7778 |
| | | | `010` | 0.1111 |
| one syllable on a beat (2,0) | 2 | 2/3 | `1` | 0.6667 |
| | | | `2` | 0.8333 |
| | | | `0` | 0.3333 |
| one syllable off the beat (0,0) | 0 | 0 | `1` night | 0.0 |
| | | | `0` the | 1.0 |
| pickup 48 in 4/4: tick 0 then 48 (2,0) (3,1) | 2, 3 | 0, 1 | `01` away | 1.0 |

## Ordering

When rhythm applies, after `apply_order` (rhyme or meaning, whichever was applied), a stable sort by
`(band rank, -rhythm_fit, similarity missing, -similarity)` (`order_by_rhythm`). `apply_order`'s
output is already band -> similarity -> score -> today's position (meaning) or today's order (rhyme:
score first), so the full order is:

- order `rhyme` (or meaning unavailable): band -> rhythm fit desc -> today's order (score, depth,
  notes first, context tags, id);
- order `meaning`: band -> rhythm fit desc -> similarity desc (candidates past the 1500 meaning pool,
  with `similarity: null`, after the pooled ones of the same band and fit) -> score -> today's position.

Bands come from the score as always; rhythm fit never lifts a candidate above a better band. The fit
compared is the wire value (4 decimals).

## Data and control flow

```
suggest(req)
  -> resolve anchors (unchanged)
  -> rhythm_plan(req.rhythm_fit, resolved[0])                         [rhyme_suggest_service]
       off                 -> RhythmOff
       text anchor         -> RhythmUnavailable("no_score")
       snippet anchor      -> stored_slot_rhythm(record.score, record.grid_data, line, word_offset, word_end)
                                None -> no_score; check_score fails -> no_score (warn)
                                slot_rhythm: ordinals of the range's syllable cells -> notes
                                  any missing -> slot_unplaced
                                  else SlotRhythm(onset, metric_weight(onset), accent) per syllable
  -> run sources in a worker thread (unchanged)
  -> drop anchor texts (unchanged)
  -> SlotRhythm only: keep candidates with len(candidate_stresses(stress)) == slot count
       (before dedupe_note_suffixes, so a note line keeps its suffix of the right length)
  -> dedupe, rank, meaning target, apply_order (unchanged; the meaning pool is over the filtered list)
  -> SlotRhythm only: fit per ranked candidate; order_by_rhythm
  -> page (offset/limit), total = len(ranked) (filtered)
  -> response: rhythm_applied, rhythm_unavailable_reason, candidate rhythm_fit
  -> log rhyme_suggest.done ... rhythm=off|applied|no_score|slot_unplaced ...
```

`SnippetRecord.score` carries the stored score JSON (`DbSnippetRepository` reads `snippets.score`).

## Files

| File | Role | Key exports |
|---|---|---|
| `backend/app/services/rhythm_fit/types.py` | Typed slot rhythm and unavailable reason | `SlotBeat`, `SlotRhythm`, `RhythmUnavailable`, `RhythmUnavailableReason`, `SlotRhythmResult`, `ACCENT_LEVEL`, `MAX_LEVEL` |
| `backend/app/services/rhythm_fit/fit.py` | The score | `rhythm_fit`, `rhythm_fit_exact`, `slot_targets`, `candidate_stresses`, `wire_fit`, `STRESS_PROMINENCE`, `FIT_DECIMALS` |
| `backend/app/services/rhythm_fit/slot.py` | Slot rhythm from score + grid + word range | `slot_ordinals`, `slot_rhythm`, `stored_slot_rhythm` |
| `backend/app/services/rhythm_fit/order.py` | Band -> fit -> similarity re-sort | `order_by_rhythm` |
| `backend/app/services/rhythm_fit/errors.py` | Typed failures | `RhythmFitError`, `StressPatternError`, `SyllableCountMismatch` |
| `backend/app/services/rhyme_suggest_service.py` | `rhythm_plan`, `RhythmOff`, `fits_rhythm_count`, `candidate_rhythm_fit`, `suggest_candidate_model`; `SnippetRecord.score` | |
| `backend/app/schemas/rhyme.py` | `RhymeSuggestRequest.rhythm_fit`, `RhymeSuggestCandidateModel`, response fields | |
| `backend/tests/rhythm_fit/` | Score by hand, slot extraction, ordering, service-level behaviour, DB route test | |

## Invariants

- `rhythm_fit` false: same candidates, order and `total` as before this feature; no score is read.
- `rhythm_applied` true iff `rhythm_fit` was asked and the first anchor's slot syllables are all
  placed in a valid stored score; then `rhythm_unavailable_reason` is null and every candidate has
  `rhythm_fit` in [0, 1] (4 decimals) and exactly the slot's syllable count.
- `rhythm_fit` asked and not applied: reason non-null, every candidate's `rhythm_fit` null, and
  candidates / order / `total` equal the `rhythm_fit: false` response.
- Applied: bands are non-decreasing down the list; within a band `rhythm_fit` is non-increasing; within
  a band and fit, similarity is non-increasing among pooled candidates (meaning applied) or today's
  order holds (rhyme).
- `total` counts the filtered list; filtering and ordering happen before paging, so pages of one
  request are disjoint and in order.
- `band == band_of(score)` always; rhythm never rescores.
- The score is a pure function of the slot levels and the candidate stress digits.

## Decisions

1. **First anchor gives the slot** (contract §6 says "a snippet anchor"): the clients already derive
   `slot` from the first anchor; a text first anchor answers `no_score` even when a later anchor is a
   scored snippet.
2. **Text anchors answer `no_score`**: the contract's reason union has no separate text reason.
3. **Filter before the note-suffix dedupe**: otherwise a note line whose best suffix has the wrong
   length would vanish even when a shorter suffix of it fits.
4. **`RhymeSuggestCandidateModel`** instead of a field on `RhymeCandidateModel`, so pattern candidates
   (non-scope) do not gain a meaningless `rhythm_fit: null`.
5. **An invalid stored score is `no_score`** (logged warn) rather than an error: the rhyme list still
   works.
6. **Durations are ignored**: the fit reads onsets and accents only, as the contract specifies.
