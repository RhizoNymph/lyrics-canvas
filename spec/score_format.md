# Snippet score format v1 and shared algorithms

Status: normative. Version 1. Vectors: `spec/score_vectors.json` (`"version": 1`).
Reference implementation: `backend/app/services/score/` (Python). Vector generator:
`backend/scripts/gen_score_vectors.py`. Ports (TypeScript `frontend/src/lib/score/`, Kotlin
`android/core/logic/.../score/`) are written from this file and must pass every vector.

Where this file and `contract.md` of round 4 differ, this file wins. Every choice not fixed by the
contract is listed in [Decisions](#14-decisions).

Contents:

1. [Terms](#1-terms)
2. [Format v1](#2-format-v1)
3. [Grid model used by the algorithms](#3-grid-model-used-by-the-algorithms)
4. [validate](#4-validate)
5. [Bars and timing](#5-bars-and-timing)
6. [metric_weight](#6-metric_weight)
7. [tick_to_ms](#7-tick_to_ms)
8. [Rest link, score to grid: apply_rest_link](#8-rest-link-score-to-grid-apply_rest_link)
9. [Rest link, grid to score: apply_grid_rest_edit](#9-rest-link-grid-to-score-apply_grid_rest_edit)
10. [Text normalization and LCS](#10-text-normalization-and-lcs)
11. [reconcile](#11-reconcile)
12. [draft_from_grid](#12-draft_from_grid)
13. [Text splice](#13-text-splice)
14. [Decisions](#14-decisions)
15. [Vectors file](#15-vectors-file)

---

## 1. Terms

- **Tick**: the time unit. `PPQ = 48` ticks per quarter note. A whole note is `WHOLE = 192` ticks.
- **Grid**: a snippet's `grid_data` (`{"lines": [{"cells": [...], "syllable_count", "raw_text"}]}`).
- **Rest cell**: a grid cell whose `rest` value is exactly JSON `true`. Every other cell is a
  **syllable cell**.
- **Syllable ordinal** of a syllable cell: its 0-based position among the syllable cells of its
  line (rest cells are not counted). Notes refer to syllables by ordinal (`s`).
- **Placed** syllable: a syllable that has a note in the score. **Unplaced**: one with no note
  (clients show these in a tray).
- `floor(a / b)` for non-negative integers is integer division. Every division in this file
  whose operands are integers is exact or explicitly floored; the only floating point arithmetic
  is in `tick_to_ms` (section 7) and the bpm check (section 4).

Constants (also in the vectors' `constants` section):

| Name | Value |
|------|-------|
| `PPQ` | 48 |
| `WHOLE` | 192 (= 4 * PPQ) |
| `MAX_BAR` | 2048 |
| `MAX_TICKS` | 393216 (= 48 * 4 * 2048) |
| `MAX_METERS` | 256 |
| `MAX_TEMPOS` | 256 |
| `MAX_SAFE_INT` | 9007199254740991 (= 2^53 - 1) |
| `NUM_RANGE` | 1..32 |
| `DENS` | {1, 2, 4, 8, 16, 32} |
| `BPM_RANGE` | 20..400 (inclusive) |
| `SNAP_TICKS` | `"1/8"` = 24, `"1/16"` = 12, `"1/32"` = 6, `"1/8t"` = 16, `"1/16t"` = 8 |

## 2. Format v1

A score is a JSON object with exactly these seven keys:

```json
{
  "v": 1,
  "ppq": 48,
  "pickup": 0,
  "meters": [{"bar": 0, "num": 4, "den": 4}],
  "tempos": [{"bar": 0, "bpm": 90}],
  "snap": "1/16",
  "lines": [{"notes": [{"s": 0, "t": 0, "d": 12, "a": 1}]}]
}
```

| Key | Type | Rule |
|-----|------|------|
| `v` | integer | 1 |
| `ppq` | integer | 48 (fixed) |
| `pickup` | integer | ticks before bar 0 starts; `0 <= pickup < bar_length(meters[0])`. Tick 0 is the start of the pickup. |
| `meters` | array of `{bar, num, den}` | 1..256 entries; first `bar` is 0; `bar` strictly increasing; `bar <= 2048`; `num` in 1..32; `den` in {1,2,4,8,16,32}. A meter holds from its bar until the next entry's bar. |
| `tempos` | array of `{bar, bpm}` | 1..256 entries; first `bar` is 0; strictly increasing; `bar <= 2048`; `bpm` a number in 20..400 with at most 2 decimals (quarter notes per minute). A tempo holds from its bar start until the next entry. Tempo changes only at bar starts. |
| `snap` | string | `"1/8"`, `"1/16"`, `"1/32"`, `"1/8t"` or `"1/16t"`: the editor's last snap choice. It is content (saved, synced) but constrains nothing: note starts and durations need not lie on the snap grid. |
| `lines` | array of `{notes}` | exactly one entry per grid line, same index (`grid_data.lines[i]`), rest-only lines included. |

A **note** is an object with the integer keys:

| Key | Rule |
|-----|------|
| `s` | syllable ordinal within the grid line, `0 <= s < syllable count of the line`; strictly increasing within the line (each syllable has at most one note) |
| `t` | start tick, `t >= 0`, absolute from tick 0 |
| `d` | duration in ticks, `d >= 1`; `t + d <= MAX_TICKS` |
| `a` | accent: 0 none, 1 accent, 2 strong |

Any other key of a note is an **extra key**. Extra keys and their values (any JSON) are preserved
unchanged by every algorithm that carries the note (for example a future pitch). No algorithm reads
them. Unknown keys anywhere else (score, meter, tempo, line) make the score `malformed`.

**Single voice**: walking the lines in order and each line's notes in order, every note starts at or
after the previous note's end (`t >= previous.t + previous.d`). So notes are strictly ordered in time
and never overlap, also across lines.

**Integers.** A field typed integer must be a JSON number (not a boolean) with an integral value and
`|value| <= MAX_SAFE_INT`. `12.0` is the integer 12 (JavaScript cannot tell them apart); `12.5`,
`1e20`, `true`, `"12"` and `null` are not integers. Implementations store the value as an integer and
serialize it without a fraction.

**Numbers** (`bpm`): a JSON number, not a boolean. Serialized as given (an integer stays an integer).

**Key order** on output: `v, ppq, pickup, meters, tempos, snap, lines`; meter `bar, num, den`; tempo
`bar, bpm`; note `s, t, d, a`, then extra keys in their input order. Consumers compare scores as
JSON values (key order and `12` vs `12.0` do not matter); the vectors are compared that way.

**Bar length** of a meter: `num * WHOLE / den` ticks, an integer for every allowed `den`.

**Default accent** when a score is created from a grid or a voice take: grid stress 1 gives `a = 1`,
any other stress (0, 2, missing) gives `a = 0`.

## 3. Grid model used by the algorithms

The algorithms read a grid as JSON: `null` (no grid) or an object with `lines`, each line an object
with `cells` (array of objects). `grid == null` is a grid with zero lines. Other line keys
(`syllable_count`, `raw_text`, unknown) and other grid keys are preserved by every algorithm that
returns a grid; only `cells` and `raw_text` are ever rewritten (section 8).

Per line:

- `cells`: the cell array. Rest cell: `cell.rest === true`. Syllable cell: anything else.
- `syl[k]`: the cell index of the syllable with ordinal `k` (`k = 0 .. n-1`, `n` syllable cells).
- **Syllable count** of a line: `n` (counted from the cells; the stored `syllable_count` is not read).
- **Rests between** `k` and `k+1`: `syl[k+1] - syl[k] - 1` (all cells between two consecutive
  syllable cells are rest cells). **Leading** rests: the cells before `syl[0]`; **trailing**: after
  `syl[n-1]`. A line with no syllables is all rests (or empty).
- **Word groups** (used by reconcile, section 11): walk the syllable cells in order. A cell whose
  `word_index` is an integer (not a boolean) belongs to the group of that `word_index`; groups are
  ordered by the first appearance of their `word_index`. A syllable cell without an integer
  `word_index` is an anonymous group of its own, in its position. A group's **ordinals** are the
  ordinals of its cells in cell order (a word may be interleaved with others after a chunk drag).
  A group's **spelling** follows `rest_marks.rebuild_line_text`: for a `word_index` group, the `word`
  value of the first syllable cell (in cell order) with that `word_index` and a non-empty string `word`; if
  none, the concatenation of the `syllable` values (missing or null = `""`) of its cells; for an
  anonymous group, its `syllable` (missing or null = `""`).
- **Canonical line text**: `rebuild_line_text(cells)` from `docs/features/rest_marks.md` (rest =
  `_`, each word once at its first syllable, joined by single spaces). Vectors for it are in
  `spec/rest_mark_vectors.json` (`rebuild`).

## 4. validate

`validate(raw, grid) -> ok | invalid(reason, line?, note?)`

`raw` is the score as a JSON value. Checks run in exactly this order and the **first** failure is the
result. `line` and `note` are 0-based indexes into `lines` and that line's `notes`; they are present
only where stated.

1. `raw` is not an object: `malformed`.
2. The key set of `raw` is not exactly `{v, ppq, pickup, meters, tempos, snap, lines}` (a key missing
   or an unknown key): `malformed`.
3. `v` not an integer: `malformed`. `v != 1`: `unsupported_version`.
4. `ppq` not an integer: `malformed`. `ppq != 48`: `bad_ppq`.
5. `meters` not an array: `malformed`. Empty: `meters_empty`. More than 256 entries:
   `too_many_meters`. Then for each entry `i` in order:
   1. not an object, key set not exactly `{bar, num, den}`, or any of the three not an integer:
      `malformed`;
   2. `i == 0` and `bar != 0`: `meters_first_not_zero`;
   3. `i > 0` and `bar <= meters[i-1].bar`: `meters_not_increasing`;
   4. `bar > 2048`: `bar_out_of_range`;
   5. `num` not in 1..32 or `den` not in {1,2,4,8,16,32}: `meter_invalid`.
6. `tempos` not an array: `malformed`. Empty: `tempos_empty`. More than 256: `too_many_tempos`.
   Then for each entry `i` in order:
   1. not an object, key set not exactly `{bar, bpm}`, `bar` not an integer, or `bpm` not a number:
      `malformed`;
   2. `i == 0` and `bar != 0`: `tempos_first_not_zero`;
   3. `i > 0` and `bar <= tempos[i-1].bar`: `tempos_not_increasing`;
   4. `bar > 2048`: `bar_out_of_range`;
   5. `bpm < 20` or `bpm > 400` or `|bpm * 100 - round(bpm * 100)| > 1e-6` (more than 2 decimals;
      `round` to the nearest integer, IEEE double arithmetic): `tempo_invalid`.
7. `pickup` not an integer: `malformed`. `pickup < 0` or `pickup >= bar_length(meters[0])`:
   `bad_pickup`.
8. `snap` not one of the five strings (any other value or type): `snap_invalid`.
9. `lines` not an array: `malformed`. `len(lines) != number of grid lines`: `line_count_mismatch`.
10. For each line `i` in order: not an object, key set not exactly `{notes}`, or `notes` not an
    array: `malformed` (`line`). Then for each note `j` of the line in order (all with `line`, `note`):
    1. not an object, a key of `s, t, d, a` missing, or one of them not an integer: `malformed`;
    2. `s < 0` or `s >= syllable count of grid line i`: `syllable_out_of_range`;
    3. `j > 0` and `s <= notes[j-1].s`: `syllables_not_increasing`;
    4. `t < 0`: `start_negative`;
    5. `d < 1`: `duration_invalid`;
    6. `a` not in {0, 1, 2}: `accent_invalid`;
    7. `t + d > MAX_TICKS`: `exceeds_max_ticks`;
    8. `t < prev_end`: `notes_overlap`, where `prev_end` is `t + d` of the previous note of the whole
       score (lines in order, notes in order), 0 before the first note.

Reason codes (complete list, the order of the checks above):

| Reason | `line` | `note` |
|--------|--------|--------|
| `malformed` | at step 10 | at step 10.1 |
| `unsupported_version` | | |
| `bad_ppq` | | |
| `meters_empty` | | |
| `too_many_meters` | | |
| `meters_first_not_zero` | | |
| `meters_not_increasing` | | |
| `bar_out_of_range` | | |
| `meter_invalid` | | |
| `tempos_empty` | | |
| `too_many_tempos` | | |
| `tempos_first_not_zero` | | |
| `tempos_not_increasing` | | |
| `tempo_invalid` | | |
| `bad_pickup` | | |
| `snap_invalid` | | |
| `line_count_mismatch` | | |
| `syllable_out_of_range` | yes | yes |
| `syllables_not_increasing` | yes | yes |
| `start_negative` | yes | yes |
| `duration_invalid` | yes | yes |
| `accent_invalid` | yes | yes |
| `exceeds_max_ticks` | yes | yes |
| `notes_overlap` | yes | yes |

The API error is `422 {"error": "invalid_score", "reason", "line"?, "note"?}`.

The grid itself is not validated (the snippet schema does that). The other algorithms take a score
that is valid against the grid they are given and say what they do otherwise.

Example: with a grid of one line of 2 syllables,
`{"v":1,"ppq":48,"pickup":0,"meters":[{"bar":0,"num":4,"den":4}],"tempos":[{"bar":0,"bpm":90}],"snap":"1/16","lines":[{"notes":[{"s":0,"t":0,"d":24,"a":0},{"s":1,"t":12,"d":12,"a":0}]}]}`
is `notes_overlap` at line 0, note 1 (12 < 0 + 24).

## 5. Bars and timing

Notation: `M(b)` is the meter in force at bar `b >= 0`: the last entry of `meters` with
`entry.bar <= b`. `L(b) = M(b).num * WHOLE / M(b).den`. Meters hold forever after the last entry; bars
past 2048 exist (they use the last meter).

- `bar_length(score, bar)` for `bar >= 0`: `L(bar)`. For `bar == -1` (the pickup): `pickup`.
- `bar_start_tick(score, bar)` for `bar >= 0`: `pickup + L(0) + L(1) + ... + L(bar-1)` (so bar 0
  starts at `pickup`). For `bar == -1`: 0. Implementations sum per meter segment (bars `[e.bar,
  next.bar)` share one length) rather than bar by bar. `bar < -1` is an argument error.
- `bar_at_tick(score, tick) -> (bar, offset)` for `tick >= 0`: if `tick < pickup` then
  `(-1, tick)`; otherwise the unique `bar >= 0` with `bar_start_tick(bar) <= tick <
  bar_start_tick(bar + 1)`, and `offset = tick - bar_start_tick(bar)`. `tick < 0` is an argument
  error.
- Meter in force at a tick: `M(bar)` for `bar_at_tick(tick).bar >= 0`, and `meters[0]` in the pickup.
- `beat_unit(score, tick) = WHOLE / den` of the meter in force at `tick` (one denominator note:
  48 in x/4, 24 in x/8, 96 in x/2). Compound meters are not special here.
- `end_tick(score)`: `max(t + d)` over all notes, 0 when there are none.
- `bar_count(score)`: the number of bars from bar 0 needed to hold every note:
  `max(1, n)` where `n` is the smallest integer `>= 0` with `bar_start_tick(n) >= end_tick(score)`.
  (The pickup is not counted; a score whose notes all end in the pickup has `bar_count` 1.)

Example: `pickup = 24`, meters `[{0, 4/4}, {2, 3/4}, {3, 6/8}]`. Bar lengths: bar 0 and 1 = 192,
bar 2 = 144, bar 3+ = 144. Starts: bar -1 = 0, bar 0 = 24, bar 1 = 216, bar 2 = 408, bar 3 = 552,
bar 4 = 696. `bar_at_tick(10) = (-1, 10)`, `bar_at_tick(24) = (0, 0)`, `bar_at_tick(500) = (2, 92)`,
`bar_at_tick(700) = (4, 4)`. `beat_unit(10) = 48` (pickup, meter of bar 0), `beat_unit(600) = 24`.

## 6. metric_weight

`metric_weight(score, tick) -> 3 | 2 | 1 | 0` (3 bar downbeat, 2 on a beat, 1 on a half beat or
beat subdivision, 0 otherwise), for `tick >= 0`:

```
(bar, off) = bar_at_tick(score, tick)
if bar == -1:                       # the pickup is the tail of a full bar of meters[0]
    m = meters[0]; off = L(0) - pickup + tick
else:
    m = M(bar)
if off == 0: return 3
unit = WHOLE / m.den
if m.num % 3 == 0 and m.num >= 6 and m.den >= 8:   # compound: 6/8, 9/8, 12/8, 6/16, 12/16 ...
    beat = 3 * unit; sub = unit
else:                                               # simple: 2/4, 3/4, 4/4, 6/4, 3/8, 5/8, 7/8 ...
    beat = unit;     sub = unit / 2                 # integer: unit >= 6 and even
if off % beat == 0: return 2
if off % sub == 0:  return 1
return 0
```

In the pickup, `off` is never 0 (since `pickup < L(0)`), so the pickup never has a downbeat.

Examples (4/4, pickup 0): tick 0 = 3, 48 = 2, 24 = 1, 12 = 0, 192 = 3. In 6/8 (bar = 144): 0 = 3,
72 = 2, 24 = 1, 48 = 1, 12 = 0. In 3/8: 24 = 2, 12 = 1. With 4/4 and pickup 48: tick 0 is the 4th
beat of a virtual bar (off = 144), weight 2; tick 24 (off 168) weight 1.

## 7. tick_to_ms

`tick_to_ms(score, tick) -> float` for `tick >= 0` (argument error otherwise). The tempo in force at
a bar `b >= 0` is the last entry of `tempos` with `entry.bar <= b`; the pickup uses `tempos[0]`.
Exactly this computation (IEEE 754 double, in this order, so every port gets the same bits):

```
ms = 0.0
for j in 0 .. len(tempos)-1:
    start = 0 if j == 0 else bar_start_tick(score, tempos[j].bar)
    end   = +infinity if j == len(tempos)-1 else bar_start_tick(score, tempos[j+1].bar)
    if tick <= start: break
    n = min(tick, end) - start                          # integer ticks
    ms = ms + (double(n) * 60000.0) / (double(tempos[j].bpm) * 48.0)
return ms
```

Vectors compare with an absolute tolerance of 1e-6 ms (ports following the formula match exactly).

Example: 4/4, pickup 0, tempos `[{0, 120}, {1, 60}]`. Bar 1 starts at 192. `tick_to_ms(96) =
96 * 60000 / 5760 = 1000.0`; `tick_to_ms(240) = 2000.0 + 48 * 60000 / 2880 = 3000.0`.

## 8. Rest link, score to grid: apply_rest_link

`apply_rest_link(score, grid) -> grid`. Precondition: `score` is valid against `grid` (else
`ScoreInvalid`). The returned grid has the same lines, syllable cells, words and stress; only the
number of rest cells between two **consecutive placed** syllables changes.

For each line `i` (grid line and `score.lines[i]`), with `note(k)` the note with `s == k` if any:

```
out = cells[0 .. syl[0]]                # leading rests and syllable 0, unchanged (all cells if n == 0)
for k in 0 .. n-2:
    run = cells[syl[k]+1 .. syl[k+1]-1] # the rest cells between k and k+1
    if note(k) and note(k+1):
        gap    = note(k+1).t - (note(k).t + note(k).d)          # >= 0 in a valid score
        target = floor(gap / beat_unit(score, note(k).t + note(k).d))
        keep the first min(len(run), target) cells of run, then append
        target - len(run) new rest cells {"rest": true} when target > len(run)
    else:
        keep run unchanged
    append cells[syl[k+1]]
append the trailing cells (after syl[n-1]) unchanged
```

If the line's new cell list equals the old one (same length, same cells in the same order), the line
is returned unchanged (its `raw_text` too, byte for byte). Otherwise the line gets the new `cells`
and `raw_text = rebuild_line_text(new cells)`; its other keys are kept. A new rest cell is exactly
`{"rest": true}`.

Rest cells between a placed and an unplaced syllable, between two unplaced syllables, and leading and
trailing rests are never touched. A rest inside a word shows in the grid and lands after the word in
the text (the rebuild rule): accepted.

The function is idempotent: `apply_rest_link(s, apply_rest_link(s, g)) == apply_rest_link(s, g)`.

Example: 4/4, line "I go now" (one syllable each, `word_index` 0..2), cells `I go now`, notes
`s0 t0 d24`, `s1 t72 d24`, `s2 t96 d24`. Boundary 0-1: gap 48, beat unit at 24 is 48 → 1 rest.
Boundary 1-2: gap 0 → 0. New cells `I _ go now`, `raw_text` `"I _ go now"`. If the gap were 47:
floor(47/48) = 0, no rest.

## 9. Rest link, grid to score: apply_grid_rest_edit

`apply_grid_rest_edit(score, old_grid, new_grid) -> (score, grid)`.

Preconditions, checked in this order:

1. `score` is valid against `old_grid` (else `ScoreInvalid` with its reason).
2. `is_rest_only_edit(old_grid, new_grid)` (else `NotRestOnlyEdit`): same number of lines, and in
   each line the same number of syllable cells with pairwise equal `syllable` (missing = null) and
   `word_index` (missing = null) values, compared as JSON values. Rest cells may be added, removed or
   moved anywhere. (A chunk drag that crosses only rests is a rest-only edit; one that crosses another
   syllable reorders syllables and is not: it goes through reconcile.)

Then, with `old_r(i, k)` / `new_r(i, k)` the rests between syllables `k` and `k+1` of line `i` in the
old / new grid, the notes are moved line by line, in order (all ticks are the current, already moved
values):

```
prev_end = 0            # end of the last note of the lines already processed
for each line i in order:
    if line i has notes:
        # (a) ripple: never overlap the previous lines, never move left
        shift = max(0, prev_end - first note of line i .t)
        add shift to t of every note of line i
        # (b) boundaries, left to right
        for k in 0 .. n-2 where note(k) and note(k+1) both exist:
            delta = new_r(i, k) - old_r(i, k)
            if delta == 0: continue
            end_k  = note(k).t + note(k).d
            amount = delta * beat_unit(score, end_k)
            if amount < 0:                       # the clamp: close the gap at most
                amount = -min(-amount, note(k+1).t - end_k)
            add amount to t of every note of line i with s >= k+1
        prev_end = last note of line i .t + .d
```

Durations, accents, extra keys, meters, tempos, pickup and snap are unchanged. The moved score is then
validated against `new_grid`; the only possible failure is `exceeds_max_ticks` (a note pushed past
`MAX_TICKS`), raised as `ScoreInvalid` and the edit is refused. Finally the canonical grid is
`apply_rest_link(moved score, new_grid)` and the result is `(moved score, that grid)`.

So: adding rests at a placed-placed boundary pushes the rest of the line right by one beat unit per
rest (and the following lines right only as far as needed); removing rests pulls the rest of the line
left by up to one beat unit per rest, but never so far that note `k+1` would start before note `k`
ends; edits next to unplaced syllables or at the line edges move nothing and stay in the grid (the rest
link does not touch those rests).

Example: 4/4, one line `I go now` with notes `s0 t0 d24`, `s1 t24 d24`, `s2 t48 d24` (no rests).
The user inserts 2 rests between `go` and `now`: delta 2 at k = 1, end_1 = 48, beat unit 48, so `now`
moves by 96 to t 144. The rest link then recomputes floor((144 - 48) / 48) = 2 rests (`go` ends at
48), matching the grid. If instead the old grid had `I _ _ go now` and the
score had `I t0 d24`, `go t48 d24`, `now t72 d24` (a gap of only 24 after `I`), removing both rests
(delta -2, amount -96) is clamped to -24: `go` moves to t 24 and `now` to t 48, and the rest link
leaves no rest (vectors `grid_rest_edit` "spec example insert" / "spec example clamp").

## 10. Text normalization and LCS

**normalize_word(text) -> string**:

1. Unicode NFC.
2. Lowercase each code point independently with its full lowercase mapping (Python `c.lower()`,
   JavaScript `String.fromCodePoint(c).toLowerCase()`, Kotlin
   `String(Character.toChars(c)).lowercase(Locale.ROOT)`); no context rules (so `Σ` is always `σ`).
3. Map U+2019 (right single quotation mark) to U+0027 (`'`).
4. Keep only code points that are letters (general category L*: Lu, Ll, Lt, Lm, Lo), decimal digits
   (Nd) or `'` (U+0027). Drop everything else (punctuation, marks, symbols, whitespace).

Examples: `"Don't,"` → `don't`; `"HELLO!"` → `hello`; `"Café"` (decomposed) → `café`; `"’til"` →
`'til`; `"—"` → `""`; `"4ever"` → `4ever`.

**word_key(spelling)** = `normalize_word(spelling)` if non-empty, else `"#" + NFC(spelling)` (so two
identical punctuation-only words still match each other; `#` never occurs in a normalized word).

**line_key(line)** = the `word_key`s of the line's word groups (section 3), in group order, joined by a
single space; `""` when the line has no syllable cells.

**lcs_pairs(a, b) -> [(i, j)]** over two sequences of strings. Two elements are equal when they are
equal strings and **not empty** (`""` never matches anything). Exactly:

```
L[i][j] for i in 0..len(a), j in 0..len(b): L[len(a)][*] = L[*][len(b)] = 0
L[i][j] = L[i+1][j+1] + 1                 if eq(a[i], b[j])
        = max(L[i+1][j], L[i][j+1])       otherwise
i = 0; j = 0; pairs = []
while i < len(a) and j < len(b):
    if eq(a[i], b[j]):          pairs.append((i, j)); i += 1; j += 1
    elif L[i+1][j] >= L[i][j+1]: i += 1        # tie: skip the element of a (old)
    else:                        j += 1
```

The pairs are strictly increasing in both coordinates and form a longest common subsequence. Matching
on equality first means repeated words pair from the front: `a = [la, la]`, `b = [la, la, la]` gives
`[(0,0), (1,1)]`; `a = [x, y]`, `b = [y, x]` gives `[(1,0)]` (at the start `L[1][0] = L[0][1] = 1`,
the tie skips `a[0]`, then `a[1] = b[0] = y` matches).

## 11. reconcile

`reconcile(score, old_grid, new_grid) -> score`: after a text (or any grid) change, keep the notes of
syllables that still exist. Precondition: `score` is valid against `old_grid` (else `ScoreInvalid`).

1. **Line pairs.** If `old_grid` and `new_grid` have the same number of lines, line `i` pairs with
   line `i`. Otherwise `lcs_pairs([line_key(old lines)], [line_key(new lines)])`. Unpaired new lines
   get no notes.
2. **Word pairs** within a line pair: `lcs_pairs` over the `word_key`s of the old line's word groups
   and the new line's word groups.
3. **Mapping.** For a word pair `(wo, wn)` and `r` in `0 .. min(len(wo.ordinals), len(wn.ordinals)) -
   1`: if the old syllable `wo.ordinals[r]` has a note, it becomes a candidate note of the new line with
   `s = wn.ordinals[r]` and the old note's `t`, `d`, `a` and extra keys. All other new syllables are
   unplaced.
4. **Order.** Each new line's candidates are sorted by `s` (no two share an `s`). Then, walking the new
   lines in order and each line's candidates in `s` order, a candidate is kept when `t >= prev_end`
   (the end of the last kept note, 0 at first) and dropped otherwise. Dropping only happens when words
   were interleaved and reordered (e.g. after a chunk drag across a syllable); for every other edit the
   mapped notes are already in time order.
5. `meters`, `tempos`, `pickup`, `snap` are copied. The result has one line per new grid line and is
   valid against `new_grid` (by construction; implementations assert it).

Clients and the server then apply `apply_rest_link(result, new_grid)`.

Example: old line `hello world` (`hel lo | world`, ordinals 0 1 | 2) with notes on 0, 1, 2; new text
`hello big world` (ordinals `hel 0, lo 1 | big 2 | world 3`). Words pair hello↔hello, world↔world;
notes move to s 0, 1, 3 with the same t/d/a; `big` is unplaced. If the new text were `hellos world`
(`hel los`), `hellos` does not match `hello` (different key): ordinals 0 and 1 are unplaced.

## 12. draft_from_grid

`draft_from_grid(grid, meter, bpm, note) -> score` with `meter = {num, den}`, `bpm` a number and
`note` one of the snap strings.

Argument checks in order: `meter.num` / `meter.den` as for a meter entry (`meter_invalid`), `bpm`
as for a tempo (`tempo_invalid`), `note` a snap value (`snap_invalid`); raised as `ScoreInvalid`.

```
len  = SNAP_TICKS[note]
beat = WHOLE / meter.den
bar  = meter.num * WHOLE / meter.den
cursor = 0
for each grid line i in order:
    if i > 0: cursor = smallest multiple of bar that is >= cursor   # each line starts on a new bar
    notes = []
    for each cell in order:
        if rest cell: cursor += beat                                # a rest is one beat unit
        else:
            notes.append({s: ordinal, t: cursor, d: len, a: 1 if cell.stress == 1 else 0})
            cursor += len
    lines.append({notes})
score = {v: 1, ppq: 48, pickup: 0, meters: [{bar: 0, num, den}], tempos: [{bar: 0, bpm}],
         snap: note, lines}
```

The result is validated against `grid` (only `exceeds_max_ticks` can fail, for an enormous grid). An
empty grid (or `null`) gives `lines: []`. A line of `k` syllables and `r` rests occupies `k * len + r
* beat` ticks. `cell.stress == 1` means the JSON number 1.

Rest-link round trip: for every grid, `apply_rest_link(draft, grid) == grid` (each run of `r` rests
between two syllables becomes a gap of `r * beat`, and `floor(r * beat / beat) = r`).

Example: grid line `I _ go` (stress 1, -, 1), 4/4, note `"1/8"`: `I` t 0 d 24 a 1, rest 24..72,
`go` t 72 d 24 a 1. A second line starts at bar 1 = tick 192.

## 13. Text splice

The rest link rewrites a grid line's `raw_text`; the snippet's `raw_text` must follow.
`splice_line_texts(raw_text, replacements) -> string` where `replacements` maps grid line indexes to
new line texts:

- Split `raw_text` on `"\n"`. The **non-blank** lines (a line that is not empty after removing
  leading and trailing whitespace) are numbered 0, 1, 2... in order: number `g` is grid line `g`
  (the syllabifier skips blank lines the same way).
- Each text line whose number is in `replacements` is replaced entirely by the replacement; every
  other text line (blank lines included) is kept byte for byte. Join with `"\n"`.
- A replacement index with no such text line is an error (`ScoreTextMismatch`).

`changed_line_texts(old_grid, new_grid) -> {index: raw_text}`: for grids with the same number of
lines, the new `raw_text` of every line whose `raw_text` differs. The server stores
`splice_line_texts(raw_text, changed_line_texts(grid, apply_rest_link(score, grid)))`.

## 14. Decisions

Choices this file makes where the round-4 contract left room (or deviates from it):

1. **Unknown keys** are rejected (`malformed`) everywhere except in notes; notes keep extra keys.
   A future format change bumps `v`.
2. **Integers** must be integral JSON numbers within ±(2^53 − 1); `12.0` is 12. Portable across
   JavaScript, Kotlin and Python.
3. **validate** reports the first failure in a fixed order (section 4), so all ports report the same
   reason. Shape errors are one code, `malformed`.
4. **bpm decimals**: at most two decimals means `|bpm*100 - round(bpm*100)| <= 1e-6`.
5. **metric_weight, compound meters**: `num` divisible by 3, `num >= 6` and `den >= 8` is compound
   (dotted beats, the denominator note is the subdivision weight 1). Everything else, 6/4 included, is
   simple (the denominator note is the beat, half of it weight 1).
6. **Pickup weights** come from a virtual full bar of `meters[0]` whose tail is the pickup, so an
   upbeat on beat 4 weighs 2. The pickup uses `meters[0]` and `tempos[0]`.
7. **draft_from_grid rests take one beat unit** (`WHOLE / den`), not one `note` length (contract
   §3.6 said every cell takes one note length). With note lengths the rest link would immediately
   remove every rest of a fresh draft (`floor(24 / 48) = 0` in 4/4 with eighths); with beat units a
   draft round-trips through the rest link unchanged, which the vectors assert.
8. **Rest link and raw_text**: only lines whose cells changed get a rebuilt `raw_text`; other lines
   keep their text byte for byte (their cells may lack `word`, and rebuilding would respell words).
   Section 13 gives the snippet-text splice.
9. **apply_grid_rest_edit**: one pass over the lines in order: ripple first (block shift right to
   clear the previous lines), then the line's changed boundaries left to right with current
   positions; removals clamp to closing the gap; overflow past `MAX_TICKS` refuses the edit
   (`exceeds_max_ticks`). The rest-only predicate compares `syllable` and `word_index` of the
   syllable cells.
10. **reconcile ordering**: when a reorder of interleaved words would put notes out of time order,
    the later-`s` note is dropped (greedy in `s` order). Empty normalized words fall back to a `#`
    key so punctuation-only words still match themselves; empty line keys never match.
11. **Normalization**: per-code-point lowercasing (no final-sigma context rule, which JavaScript and
    Kotlin apply on whole strings but Python does not), `’` → `'`, letters L* and digits Nd.
12. **LCS tie-break**: match on equality first; otherwise skip the old element when
    `L[i+1][j] >= L[i][j+1]`.
13. **bar -1** (pickup) has start 0 and length `pickup`; `bar_count` counts bars from 0, at least 1.
14. **`grid == null`** is a grid with zero lines (a score then has `lines: []`).
15. **Wire model on Android** lives in `android/core/model` (`dev.nymph.lyrics.model.score`) beside
    every other wire model, so the `core/logic` port can use it; not in `app/`.

## 15. Vectors file

`spec/score_vectors.json`, generated by `backend/scripts/gen_score_vectors.py` from the Python
reference (`cd backend && python -m scripts.gen_score_vectors`); `backend/tests/score/test_vectors.py`
fails when the file is out of date. Ports read the file in place (web:
`new URL('../../../../spec/score_vectors.json', import.meta.url)`; Android: a Gradle system property
pointing at `../spec/score_vectors.json`, like `tagExprVectors`).

Top level: `{"version": 1, "constants": {...}, "<section>": [case, ...], ...}`. Every case has a
`name`. Every grid in the file conforms to the `GridCell` wire type (`syllable` string or null,
`stress` and `word_index` integers, `rest` boolean, `word` string, all optional), so typed decoders
can read it; scores in `validate` are arbitrary JSON and must be read as JSON values. A case whose
grids also carry keys outside those wire types (a grid `version`, a line `arranged`, a cell `mark`)
has `"unknown_grid_keys": true`: the reference preserves such keys, and a port whose grid type drops
them compares those cases after dropping the same keys from the expected output. Scores and grids are plain JSON as in sections 2 and 3. Compare outputs as JSON values (numbers
numerically, objects ignoring key order). Sections:

| Section | Case fields | Function |
|---------|-------------|----------|
| `validate` | `score` (any JSON), `grid`, `expect`: `{"ok": true}` or `{"ok": false, "reason", "line"?, "note"?}` | section 4 |
| `bars` | `score`, `grid` (null or the grid the score's notes refer to), `bars`: `[{"bar", "start", "length"}]` (bar -1 included), `end_tick`, `bar_count` | section 5 |
| `bar_at_tick` | `score`, `queries`: `[{"tick", "bar", "offset"}]` | section 5 |
| `beat_unit` | `score`, `queries`: `[{"tick", "beat_unit"}]` | section 5 |
| `metric_weight` | `score`, `queries`: `[{"tick", "weight"}]` | section 6 |
| `tick_to_ms` | `score`, `queries`: `[{"tick", "ms"}]` (tolerance 1e-6) | section 7 |
| `normalize_word` | `text`, `normalized`, `key` | section 10 |
| `lcs` | `a`, `b`, `pairs` (`[[i, j], ...]`) | section 10 |
| `line_key` | `line`, `key` | section 10 |
| `rest_link` | `score`, `grid`, `expect`: `{"grid"}` or `{"error": "invalid_score", "reason"}` (score not valid for the grid) | section 8 |
| `rest_only_edit` | `old_grid`, `new_grid`, `expect` (bool) | section 9 |
| `grid_rest_edit` | `score`, `old_grid`, `new_grid`, `expect`: `{"score", "grid"}` or `{"error": "not_rest_only"}` or `{"error": "invalid_score", "reason"}` | section 9 |
| `reconcile` | `score`, `old_grid`, `new_grid`, `expect`: `{"score"}` or `{"error": "invalid_score", "reason"}` | section 11 |
| `draft_from_grid` | `grid`, `meter`, `bpm`, `note`, `expect`: `{"score"}` or `{"error": "invalid_score", "reason"}` | section 12 |
| `text_splice` | `raw_text`, `replacements` (`{"<index>": text}`), `expect`: `{"text"}` or `{"error": "text_mismatch"}` | section 13 |
