# NLP: Rhymes

Two routes, both backed by the [phonetic engine](./nlp-phonetics.md):

- `POST /api/nlp/rhyme-suggest`: ranked, slot-checked candidates for the Rhyme View
  (`app/services/rhyme_suggest_service.py`, `app/schemas/rhyme.py`).
- `GET /api/nlp/rhymes?word=`: the older sidebar lookup, kept with its original response shape
  (`app/services/nlp/rhyme_service.py`).

## Rhyme suggest

**Route:** `app/api/nlp.py::rhyme_suggest` (Bearer). Request/response models are the contract
in [docs/features/rhyme_view.md](../features/rhyme_view.md#api-contract).

```
RhymeSuggestRequest
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
                   snippets, skipping the anchor lines
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
