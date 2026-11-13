# NLP: Rhymes

Four routes, all backed by the [phonetic engine](./nlp-phonetics.md):

- `POST /api/nlp/rhyme-suggest`: ranked, slot-checked candidates for the Rhyme View
  (`app/services/rhyme_suggest_service.py`, `app/schemas/rhyme.py`).
- `POST /api/nlp/rhyme-pattern/detect` and `POST /api/nlp/rhyme-pattern/suggest`: rhyme slots across
  2..16 lines of one snippet and candidates that rhyme with every slot member
  (`app/services/rhyme_pattern_service.py`, `app/services/rhyme_pattern/`,
  `app/services/phonetics/pattern/`, `app/schemas/rhyme_pattern.py`); see below.
- `GET /api/nlp/rhymes?word=`: the older sidebar lookup, kept with its original response shape
  (`app/services/nlp/rhyme_service.py`).

## Rhyme suggest

**Route:** `app/api/nlp.py::rhyme_suggest` (Bearer). Request/response models are the contract
in [docs/features/rhyme_view.md](../features/rhyme_view.md#api-contract).

```
RhymeSuggestRequest
  -> parse(tag_filter) when set                    TagExpressionError -> 422 invalid_tag_expression
  -> repo.canvas_owned(canvas_id, user)            else CanvasNotFoundError -> 404
  -> resolve_dialect / resolve_weights             DialectRuleError -> 422
  -> resolve_anchor per anchor
       text:    unit_from_text(text)               no syllables -> RhymeQueryError -> 422
       snippet: repo.snippet(id, user)             missing -> SnippetNotFoundError -> 404
                snippet.phonetics (computed on the fly when NULL)
                line_index null = last line; unknown line -> RhymeQueryError -> 422
                resolve_word_range(anchor, word count): (0, None) for a whole line; with
                  word_start / word_end a checked [start, end) (end defaults to the word
                  count); outside the line -> RhymeQueryError -> 422
                note_unit(snippet, line, start, dialect, tag ids, word_end=end)
                  whole line: id note:<sid>:<line>:0;  range: note:<sid>:<line>:<start>-<end>
                  with text = the range's words, phonetics of the slice only, word_end set;
                  no pronounceable word in the range -> RhymeQueryError -> 422
  -> context: tag ids of context snippet anchors (missing entries ignored)
  -> sources (run in a worker thread):
       notes:      NotesSource over every line and word-boundary suffix of the user's
                   snippets that match tag_filter (all when unset), skipping the anchor lines
       dictionary: DictionarySource(dialect, weights, include_rare)
       each: suggest(anchors, k, min_score, limit=5000, exclude=exclude_ids + anchor ids)
  -> drop candidates whose text equals an anchor's text
  -> dedupe_note_suffixes: one suffix per (snippet, line): best score, depth, fits slot, longest
  -> rank: score desc, match_depth desc, notes before dictionary, shared context tags desc, id
  -> page [offset, offset + limit); total = ranked length (capped by 5000 per source)
  -> models: RhymeUnitModel (effective syllables, rhyme_key, grid_line) and RhymeCandidateModel
            (score, match_depth, best_anchor_id, fits_slot, alignment, source)
```

`fits_slot(stress, slot)`: the stress string has the slot's length and no stressed (1 or 2)
syllable lands on a slot `0`; `True` when there is no slot. Non-fitting candidates are returned
and flagged, not hidden.

`grid_line`: for note units, the snippet's `grid_data` line sliced to `[word_offset, word_end)`
by `slice_grid_line` (word indexes rebased, leading rests dropped, trailing rests dropped too when
`word_end` is set); otherwise `syllabify_text(unit.text)`. `RhymeUnitModel.word_end` is set only
for range anchors.

`tag_filter` is a [tag expression](../features/tag_expressions.md) evaluated per snippet against
`SnippetRecord.snippet_type` and `SnippetRecord.tag_names` (`matches_filter`, in Python after
`repo.user_snippets`). An empty or whitespace-only filter means no filter. Anchors and the
dictionary source are never filtered; the context-tag tie-break (tag ids) is unchanged.
`SnippetRecord` gained `snippet_type` (default `freestyle`) and `tag_names` (default `()`), filled by
`DbSnippetRepository`.

Data access goes through the `SnippetRepository` protocol (`DbSnippetRepository` in
production; `tests/fakes.py` in tests), so the service is tested without Postgres.

### Errors

| Condition | Exception | Status |
|---|---|---|
| canvas not owned / unknown | `CanvasNotFoundError` | 404 |
| anchor snippet not owned / unknown | `SnippetNotFoundError` | 404 |
| invalid k, limit, stress, empty anchors, `word_start < 0`, `word_end < 1`, `word_start >= word_end` | pydantic | 422 |
| bad line index, empty snippet, word range outside the line or without a pronounceable word, unpronounceable text anchor | `RhymeQueryError` | 422 |
| malformed dialect rule | `DialectRuleError` | 422 |
| `tag_filter` does not parse | `TagExpressionError` | 422, `detail = {"error": "invalid_tag_expression", "message", "expression_error": {code, position, message}}` (other 422s keep a string detail) |

## Rhyme patterns

Canonical doc: [docs/features/rhyme_patterns.md](../features/rhyme_patterns.md) (models, errors,
algorithm, decisions). In short:

```
detect:  canvas / snippet / placement (404) -> dialect, weights -> resolve_range (pattern_range_invalid)
         -> phonetics.pattern.detect: spans (1..2 words, function-word rule) -> cached pair scores at
            min(k_a, k_b) with index-equivalent pruning -> pairwise Needleman-Wunsch -> seed + fold
            (join only when >= min_score against every member) + open new slots -> summarize, order, cap 8
suggest: tag_filter -> load -> resolve_range -> resolve_slots (pattern_span_invalid, pattern_slot_order)
         -> summarize -> note span index (worker thread; anchor pattern lines skipped)
         -> per slot: query each member at slot k, intersect, drop member repeats, one span per note
            line, rank, page by offsets[slot id] -> line matches (best_hits DP, >= 2 slots in order)
```

`resolve_dialect` / `resolve_weights` take any `PhoneticsOptions` (an object with `dialect` and
`weights`), so both services share them; rhyme-suggest is unchanged. `DbPatternRepository` extends
`DbSnippetRepository` with `snippet_on_canvas`. The pattern errors map to
`detail = {"error": "pattern_range_invalid" | "pattern_span_invalid" | "pattern_slot_order", "message"}`
(`app/api/nlp.py::pattern_http_error`).

## Legacy lookup

```python
def find_rhymes(word: str, include_near: bool = True) -> dict
def find_rhymes_in_texts(word: str, texts: dict[str, str]) -> list[dict]
```

`find_rhymes` queries the common-word dictionary index at the query's rhyme-key depth (from its
last stressed syllable, capped at 4). Score 1.0 is "perfect" (sorted alphabetically, max 50);
anything else at or above 0.5 is "near" (best first, max 30). `find_rhymes_in_texts` scores each
distinct word of each snippet the same way ("perfect" at 1.0, "near" at >= 0.7) and keeps the
old output shape:

```json
{
  "dictionary": { "perfect": ["bat", "hat"], "near": ["bad", "cap"] },
  "snippets": [{ "word": "hat", "rhyme_type": "perfect", "source": "snippet", "snippet_id": "...", "snippet_preview": "..." }]
}
```

Route: `GET /api/nlp/rhymes?word=<word>` (Bearer) fetches all non-empty snippets across the
user's canvases and combines both calls.
