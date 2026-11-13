# Rhyme Patterns Across Lines

Select 2..16 consecutive lines of one snippet. The server finds the **slots** where the lines rhyme
with each other (multis, internal rhymes, end rhyme), in order. For each slot it lists words and phrases
that rhyme with **every** member, so the next line can keep the whole pattern going. It also lists the
user's own lines that already hit 2+ slots in order. The client shows one independent list per slot
(no Cartesian product) and builds tuples (one pick per slot) itself.

Backend half (this doc). The web (`feat/web-rhyme-pattern`) and Android (`feat/android-rhyme-pattern`)
clients consume the API below; their docs are `web_rhyme_patterns.md` and `android_rhyme_patterns.md`.
Built on the [Rhyme View](./rhyme_view.md) engine ([nlp-phonetics](../backend/nlp-phonetics.md),
[nlp-rhymes](../backend/nlp-rhymes.md)).

## Scope

- `POST /api/nlp/rhyme-pattern/detect`: the pattern lines with per-word phonetics and grid lines, and the
  detected slots (members, coverage, full, default on, k, cohesion, shape).
- `POST /api/nlp/rhyme-pattern/suggest`: for client-edited slots, per-slot candidates from the dictionary
  and from spans of the user's other lines (tag-filterable), paged per slot, plus whole-line matches.
- Typed 422 errors `pattern_range_invalid`, `pattern_span_invalid`, `pattern_slot_order`.

## Non-scope

- One-line patterns: one line keeps the single-anchor rhyme-suggest behaviour.
- Slot editing state, tuple building, "Make snippet" from picks: client-side.
- Patterns across several snippets; lines that are not consecutive.
- Persisting detected slots. Every request recomputes them.
- Changing rhyme-suggest: its behaviour is unchanged. `resolve_dialect` / `resolve_weights` now take
  any request with `dialect` / `weights` (annotation only).

## Vocabulary

| Term | Meaning |
|---|---|
| pattern lines | the range `[line_start, line_end)` of one snippet; "lines with words" are counted for `full` and `default_on` |
| span | words `[word_start, word_end)` of one line (word indexes count words only, rests excluded) |
| tail | syllables from the span's first stressed (1 or 2) syllable to its end (1 when none is stressed); `k_span = min(4, tail)` |
| slot | ordered rhyme position: at most one span per line, every pair of members rhymes (>= min_score at detection) |
| member | one line's span in a slot |
| coverage | number of members |

## API

Pydantic models in `backend/app/schemas/rhyme_pattern.py`. Bearer auth. The canvas must be the caller's,
the snippet the caller's **and placed on that canvas**.

```python
class PatternLines(BaseModel):
    canvas_id: str            # min_length 1
    snippet_id: str           # min_length 1
    line_start: int           # ge 0
    line_end: int             # ge 0, exclusive; > line_start, <= snippet lines, <= 16 lines, >= 2 lines with words (else pattern_range_invalid)
    weights: ScoreWeightsModel | None = None
    dialect: DialectMapModel | None = None

class SpanRef(BaseModel):
    line_index: int           # ge 0; absolute line index, inside [line_start, line_end)
    word_start: int           # ge 0
    word_end: int             # ge 1, exclusive; > word_start and <= the line's word count (else pattern_span_invalid)

class PatternSpanModel(SpanRef):
    text: str                 # the words as written (rest marks removed; case and punctuation kept)
    syllable_count: int       # of the whole span
    stress: str               # of the whole span
    rhyme_key: list[str]      # from its last primary stress
    syllables: list[RhymeSyllable]   # effective (dialect applied)

class PatternSlotModel(BaseModel):
    id: str                   # detect: "s0", "s1", ... in slot order; suggest: echoed, or "u0", "u1"... (skipping given ids)
    members: list[PatternSpanModel]  # line order
    coverage: int
    full: bool                # coverage == lines with words
    default_on: bool          # full, or (4+ lines) coverage >= max(2, ceil(0.75 * lines))
    k: int                    # 1..4, depth candidates are scored at
    cohesion: float           # weakest pairwise member score (1.0 for one member)
    syllable_count: int       # == len(stress)
    stress: str               # rhyme-tail shape candidates are fit against

class PatternWordModel(BaseModel):
    word_index: int; text: str; syllable_count: int; stress: str

class PatternLineModel(BaseModel):
    line_index: int
    text: str                 # display text of the line
    words: list[PatternWordModel]
    grid_line: RhymeGridLineModel    # the snippet's whole grid line (syllabified when the snippet has no grid)

# POST /api/nlp/rhyme-pattern/detect
class PatternDetectRequest(PatternLines):
    min_score: float = 0.75   # ge 0.3, le 1.0
class PatternDetectResponse(BaseModel):
    lines: list[PatternLineModel]    # every line of the range, also lines without words
    slots: list[PatternSlotModel]    # may be empty; at most 8
    elapsed_ms: float

# POST /api/nlp/rhyme-pattern/suggest
class SlotSpec(BaseModel):
    id: str | None = None     # min_length 1; unique among the request's slots
    members: list[SpanRef]    # 1..16; at most one per line; no overlap with other slots' members
    k: int | None = None      # 1..4; None = derived (weakest pairwise match_depth; one member: its k_span)
class PatternSuggestRequest(PatternLines):
    slots: list[SlotSpec]     # 1..8, in slot order (must agree with word order on every line)
    min_score: float = 0.5    # ge 0, le 1
    sources: list[SourceName] = ["notes", "dictionary"]   # min 1
    include_rare: bool = False
    tag_filter: str | None = None    # notes and line matches only
    exclude_ids: list[str] = []
    limit: int = 30           # per slot, 1..100
    offsets: dict[str, int] = {}     # slot id -> offset (each >= 0)
    line_matches: bool = True
    line_match_limit: int = 30       # 1..100
    line_match_offset: int = 0       # ge 0
class PatternCandidateModel(RhymeCandidateModel):
    member_scores: list[float]       # member order
class PatternSlotResult(BaseModel):
    slot: PatternSlotModel           # recomputed from the members sent
    candidates: list[PatternCandidateModel]
    total: int                       # ranked candidates of the slot (before paging)
class LineMatchHit(BaseModel):
    slot_id: str; word_start: int; word_end: int; text: str; score: float
class LineMatchModel(BaseModel):
    unit: RhymeUnitModel             # id note:<sid>:<line>:0, text = the line's display text, grid_line = the line
    hits: list[LineMatchHit]         # slot order == word order, non-overlapping
    slots_hit: int                   # >= 2
    score: float                     # mean hit score
class PatternSuggestResponse(BaseModel):
    slots: list[PatternSlotResult]   # request order
    line_matches: list[LineMatchModel]
    line_match_total: int
    elapsed_ms: float
```

Candidate fields (inherited from `RhymeCandidateModel`): `score = min(member_scores)`; `match_depth` and
`alignment` are those against the weakest member (first on ties); `best_anchor_id =
"pattern:<snippet_id>:<line>:<start>-<end>"` of that member; `fits_slot` against the slot's `stress`;
note candidates have ids `note:<sid>:<line>:<start>-<end>` with `word_offset`/`word_end` set and the
snippet grid line sliced to the span; dictionary candidates `dict:<word>`.

### Errors

| Condition | Status | Body |
|---|---|---|
| canvas not the caller's | 404 | `{"detail": "canvas <id> not found"}` (`CanvasNotFoundError`) |
| snippet not the caller's, or not placed on the canvas, or a malformed id | 404 | `{"detail": "snippet <id> not found on canvas <id>"}` (`SnippetNotFoundError`) |
| `line_end <= line_start`, range past the snippet's lines, more than 16 lines, fewer than 2 lines with words | 422 | `{"detail": {"error": "pattern_range_invalid", "message": ...}}` |
| member line outside the range, `word_end <= word_start`, `word_end` past the line's words, no pronounceable word, two members on one line in a slot, overlapping members across slots | 422 | `{"detail": {"error": "pattern_span_invalid", "message": ...}}` |
| slot order disagrees with word order on some line | 422 | `{"detail": {"error": "pattern_slot_order", "message": ...}}` |
| `tag_filter` does not parse | 422 | `{"detail": {"error": "invalid_tag_expression", "message", "expression_error": {code, position, message}}}` |
| malformed dialect rule, invalid weights | 422 | `{"detail": "<message>"}` |
| field validation (min_score range, empty `slots`, more than 8 slots, empty `members`, `k` outside 1..4, `limit` outside 1..100, duplicate slot ids, negative offsets, empty `sources`) | 422 | pydantic list detail |

Checks run in this order: tag filter (suggest), canvas, snippet + placement, dialect / weights, range,
slots (span checks first, then order).

## Detection (`app/services/phonetics/pattern/`)

Pure engine code over engine types. Input: `LineWords(line_index, words)` per line, built from the
snippet's stored `phonetics` (overrides applied; computed on the fly when NULL), plus dialect, weights,
`min_score`, `max_span_words = 2`, `max_slots = 8`.

1. **Spans** (`spans.line_spans`): every range of 1..2 words with at least one syllable and a
   pronounceable last word, built as a `note:<sid>:<line>:<start>-<end>` unit (same unit as
   `phonetics_service.note_unit`). Function words (single-syllable words that are unstressed or in the
   stop list `a an the and or to of in on at it is i my me you your we be`) never start a span, never end
   one inside the line, and never form a span alone, except a **stressed function word ending its line**
   ("... the pressure on"), which carries the end rhyme. `tail` / `k_span` as in the vocabulary.
2. **Pair scores** (`pair.PairScorer`): `score(eff_a, eff_b, k = min(k_a, k_b))`, kept when `>= min_score`.
   Each span gets an interned `TailSignature` (its `k` and interned ids of its last 1..k syllables with the
   deepest onset stripped); spans with equal signatures score alike, and results are cached per tail pair.
   Pairs whose last `j = min_skeleton_depth(k, min_score)` nuclei are not vowel neighbours are skipped, as
   `RhymeIndex.query` skips them, so detection and suggestion agree on what can rhyme. Cross-line pairs
   are found through last-nucleus buckets. Two spans ending in the same word (`detect.last_word_key`:
   lowercased, punctuation removed, apostrophes kept) never pair.
3. **Pairwise alignment** (`detect._Detector.align_pair`): for every two lines, Needleman-Wunsch over word
   positions with gap cost 0. A match consumes a span on each line; matches are in order and do not overlap.
   Value of a path = `(sum of scores, sum of pair k, -words)`, compared lexicographically. So a higher
   score wins first, then a rhyme that covers more syllables, then fewer words: "strong" / "along" beats
   "stay strong" / "along".
4. **Seed and fold**: the best line pair seeds the profile (one slot per match). Each remaining line, in
   order of its best pairwise value with a line already in the profile (ties: lower line index), is
   **folded** in by a DP over (slot, word position): a span may join slot `s` only if it scores
   `>= min_score` against **every** member (value: mean score, min k, -words), in slot order and word
   order. Then its unused spans may **open new slots** with an unused span of a profile line, best pair
   first, inserted at the first profile position that keeps word order on both lines.
5. **Summaries** (`slots.summarize`): `k` = weakest pairwise `match_depth`, clamped to 1..4 (one member: its
   `k_span`; a client `k` overrides); `cohesion` = weakest pairwise score at `min(k_a, k_b)` (1.0 for one
   member); `stress` = the tail stress of the most common tail length (ties: the longest), taken from the
   last member of that length (`slot_shape`).
6. **Order and cap** (`slots.order_slots`, `slots.cap_slots`): a topological order of "comes first on a shared
   line", picking the smallest mean relative position (member start / line word count, then input order)
   among the slots free to come next; then the 8 strongest (coverage, then cohesion, then order) are kept
   and re-ordered. Every slot has coverage >= 2.

Deterministic: every choice is a total order on values and input positions. Budget: 16 lines x 16 words
of verse-like text in < 50 ms (`test_sixteen_lines_of_sixteen_words_under_50ms`, about 37 ms locally).
The worst case is 16 rotations of 16 words that all rhyme with each other (every span pair matches). It
takes about 200 ms.

### Example (`tests/rhyme_pattern/test_detect.py`)

```
I keep it steady when the pressure on
already heavy and the weather wrong
ready or not it's better long
```

-> `s0 {steady, already, ready}` (k 2, stress 10, cohesion 1.0), `s1 {pressure, weather, better}` (k 2,
stress 10, cohesion 0.953), `s2 {on, wrong, long}` (k 1, stress 1, cohesion 0.869), all full. With the
third line "ready or not it's over long", `s1` is `{pressure, weather}`, coverage 2, not full, off by
default. At `min_score = 1.0` only `{steady, already, ready}` and `{wrong, long}` remain.

## Suggest data flow

```
PatternSuggestRequest
  -> parse(tag_filter)                                  TagExpressionError -> 422
  -> repo.canvas_owned / repo.snippet / repo.snippet_on_canvas   -> 404
  -> resolve_dialect / resolve_weights                  DialectRuleError / RhymeQueryError -> 422
  -> resolve_range(record, line_start, line_end)        PatternRangeInvalidError
  -> resolve_slots(range, specs, dialect)               ids (given or u0..), build_span per member,
                                                        PatternSpanInvalidError / PatternSlotOrderError
  -> summarize each slot (k override, cohesion, shape)
  -> notes (when "notes" in sources or line_matches): repo.user_snippets filtered by tag_filter,
     build_note_spans in a worker thread: one RhymeIndex over every detection span of every line,
     skipping the anchor snippet's pattern lines (its other lines take part)
  -> worker thread: dictionary index (shared process-wide, as rhyme-suggest) when "dictionary" in sources;
     per slot:
       slot_candidates(index, members, slot.k, min_score, exclude_ids, weights): query the index with each
         member (limit 5000 each), keep units every query returned, scores per member
       rank_slot: drop units whose text is or ends with a member's text; notes: one span per
         (snippet, line) (best score, fits slot, longest, earliest); rank score desc, mean member score
         desc, match_depth desc, notes before dictionary, id; page [offsets[id], +limit)
     line matches (from the note candidates of every slot, before the text drop and line dedupe):
       per note line with options for 2+ slots: best_hits DP over (slot, word position), most hits then
       summed score, in slot order and word order, no overlap; keep >= 2 hits; skip a line whose
       note:<sid>:<line>:0 id is excluded; rank slots_hit desc, score desc, id; page
  -> PatternSuggestResponse; info log rhyme_pattern.suggest snippet lines slots candidates line_matches
     sources tag_filter elapsed_ms
```

Detect: load, dialect / weights, range, `detect`, models; logs `rhyme_pattern.detect snippet lines
pattern_lines min_score slots coverage elapsed_ms`.

## Files

| File | Role | Key exports |
|---|---|---|
| `backend/app/schemas/rhyme_pattern.py` | Request/response models, limits | `PatternLines`, `SpanRef`, `PatternSpanModel`, `PatternSlotModel`, `PatternWordModel`, `PatternLineModel`, `PatternDetectRequest/Response`, `SlotSpec`, `PatternSuggestRequest/Response`, `PatternCandidateModel`, `PatternSlotResult`, `LineMatchHit`, `LineMatchModel`, `PatternErrorBody`, `MAX_PATTERN_LINES`, `MAX_PATTERN_SLOTS` |
| `backend/app/services/phonetics/pattern/types.py` | Engine types | `SpanPos`, `LineWords`, `PatternSpan` (`tail`, `k`, `tail_stress`), `PatternSlot` (`coverage`, `full`, `default_on`), `default_on` |
| `backend/app/services/phonetics/pattern/spans.py` | Span rule and span units | `STOP_WORDS`, `MAX_SPAN_WORDS`, `is_function_word`, `stressed_tail`, `build_span`, `line_spans` |
| `backend/app/services/phonetics/pattern/pair.py` | Cached pair scoring with index-equivalent pruning | `PairScorer` (`pair`, `pair_signatures`, `at`, `signature`), `TailSignature` |
| `backend/app/services/phonetics/pattern/detect.py` | Progressive alignment | `detect`, `DETECT_MIN_SCORE` |
| `backend/app/services/phonetics/pattern/slots.py` | Slot summary, order, cap | `summarize`, `slot_shape`, `order_slots`, `cap_slots`, `MAX_SLOTS` |
| `backend/app/services/phonetics/pattern/candidates.py` | Units rhyming with every member | `slot_candidates`, `SlotCandidate` (`weakest`, `score`, `mean`, `match_depth`) |
| `backend/app/services/phonetics/pattern/line_match.py` | Hits DP for one line | `HitOption`, `best_hits` |
| `backend/app/services/rhyme_pattern/errors.py` | Typed errors | `PatternErrorCode`, `PatternError`, `PatternRangeInvalidError`, `PatternSpanInvalidError`, `PatternSlotOrderError` |
| `backend/app/services/rhyme_pattern/resolve.py` | Range loading and slot spec checks | `PatternLine`, `PatternRange`, `pattern_line`, `resolve_range`, `ResolvedSlot`, `slot_ids`, `resolve_slots` |
| `backend/app/services/rhyme_pattern/models.py` | Engine -> response models | `line_model`, `span_model`, `slot_model`, `pattern_candidate_model`, `line_match_model`, `pattern_span_id` |
| `backend/app/services/rhyme_pattern/suggest.py` | Note span index, ranking, line matches | `NoteSpans`, `build_note_spans`, `rank_slot`, `repeats_member`, `SlotRequest`, `SuggestParams`, `compute`, `line_matches` |
| `backend/app/services/rhyme_pattern_service.py` | Entry points and data access | `detect`, `suggest`, `PatternRepository`, `DbPatternRepository` (adds `snippet_on_canvas`), `default_dictionary_index`, `DictionaryIndexFactory` |
| `backend/app/api/nlp.py` | Routes and error mapping | `rhyme_pattern_detect`, `rhyme_pattern_suggest`, `pattern_http_error` |
| `backend/app/services/rhyme_suggest_service.py` | Reused: records, repository, `resolve_dialect/weights` (now typed by `PhoneticsOptions`), `candidate_model`, `unit_model`, `fits_slot`, `matches_filter` | |
| `backend/tests/rhyme_pattern/` | Spans, detection (example, partial, crossing, cap, timing), candidates, line matches, service, route, DB tests; `fakes.py` (`FakePatternRepository`, `fake_dictionary_index`, `pattern_repo`) | |
| `docs/api/openapi.json` | Regenerated snapshot | |

## Invariants

- Every slot has at most one member per line, and its members are in line order.
- On every line, the members of successive slots are in order and do not overlap. This holds for
  detected slots, and the server checks it for client slots.
- Detection: every pair of members in a slot scores `>= min_score` at `min(k_a, k_b)`, and every slot
  has coverage >= 2. There are at most 8 slots. The same input gives the same slots. No detected slot
  has two members ending in the same word.
- A span with a single function word is never a detected member, except a stressed function word ending
  its line.
- Every candidate scores `>= min_score` against every member at the slot's `k`, and
  `score = min(member_scores)`. A unit whose text is or ends with a member's text is never a candidate.
  Note candidates are one per (snippet, line) per slot, and never come from the anchor snippet's
  pattern lines.
- `exclude_ids` removes units from every slot and from line-match hits. An excluded `note:<sid>:<line>:0`
  removes that line from line matches.
- Line matches have at least 2 hits, at most one per slot, in slot order and word order, with no overlap.
- The dictionary source is never tag-filtered. Notes and line matches use only snippets that match
  `tag_filter`.
- rhyme-suggest behaviour is unchanged.

## Decisions (deviations from the shared contract, 2026-11-12)

1. **A stressed function word ending its line can be a span.** The contract's stop list includes "on".
   Read literally, that would remove the end rhyme of its own example ("pressure on" / "wrong" /
   "long"). Unstressed function words stay out everywhere.
2. **Function words also never end a span inside a line.** Without this rule, "keep it" / "it's"
   matched on "it" alone. Spans are still 1..2 words.
3. **Competing spans: score, then rhyme depth, then fewer words, then earlier.** The contract says
   "then the longer span". A span's extra unstressed or non-tail word adds nothing to the rhyme
   ("stay strong" vs "along" scores the same as "strong"). A span only wins by being longer when its
   rhyme covers more syllables.
4. **Slot order is the word order first, then mean relative position.** Mean relative position alone
   can contradict word order on a shared line when slots cover different lines. So slots are ordered
   topologically by word order, and mean relative position breaks ties.
5. **Candidates whose text ends with a member's text are dropped too**, not only exact repeats. "so
   steady" for the member "steady" is the same word. Line-match hits may repeat a member's text.
6. **Some range and span checks are service errors, not schema errors.** `line_end > line_start`,
   `word_end > word_start` and the line count limits are checked in the service. That way they return
   the typed `pattern_range_invalid` / `pattern_span_invalid` codes instead of a pydantic 422 (schema:
   `line_end >= 0`, `word_end >= 1`, `SpanRef.line_index >= 0`). Duplicate slot ids and negative
   offsets are pydantic 422s. A 404 is also returned when the snippet is not placed on the canvas
   (`snippet_on_canvas`). `full` and `default_on` count only the range's lines that have words.
7. **A repeated word is not a rhyme (2026-11-13).** On the production copy, repeated openings ("I'm" /
   "I'm" / "I'm", "don't" / "don't", "like" / "like") scored 1.0 and became slots that only suggest
   rhymes for a word the user is repeating on purpose. Detection never pairs two spans ending in the
   same word ("not" / "it's not" included, since the rhyme lives in the last word), so such slots no
   longer form and repeats no longer take alignment positions from real rhymes. A user can still make one by hand (client slots are not checked for this).

Dictionary candidates come from querying the index once per member and intersecting the results, as
the contract suggests. A unit ranked below the 5000-hit cap for one member is missed. `total` counts
the intersection.
