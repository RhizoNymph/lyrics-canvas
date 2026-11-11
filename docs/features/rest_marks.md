# Rest Marks (`_`)

`_` in snippet text is an intentional empty beat: a rest cell in the beat grid. Rests are two-way:
typing `_` makes a rest, and rests made from the grid (`+·`, the nudge-right at a line end, drags) are
written back into the text as `_`, so the text always matches the grid. Everything that reads text for
meaning (export, rhyme, phonetics, similarity, embeddings, t-SNE, search) ignores the marks.

This document is canonical for the rule. The machine-checked definition is
`backend/tests/fixtures/rest_mark_vectors.json`; the web and Android clients copy that file byte for
byte and test every vector, as the backend does (`backend/tests/rest_marks/test_vectors.py`).

## Scope

- The text rule: tokenizing a line, word tokens and `word_index`, display text, canonical text from a
  grid line, search matching.
- The backend helpers (`app/services/rest_marks.py`) and every backend reader of snippet text.
- `syllabify_text` emitting `{"rest": true}` cells for `_`.

## Non-scope

- Client editor behaviour (typing, the nudge-right rest, the arranged check): see
  `docs/features/web_rests_and_search.md` and `docs/features/android_rests_and_search.md`.
- Search endpoints and UI: [global_search.md](./global_search.md).
- Data migration: none. Legacy rows (grid rests but no `_` in `raw_text`) stay as they are; the
  clients compare display text in their arranged check, so they open arranged, and the next save writes
  the canonical text.

## The rule

### Tokenize a line

Replace each `_` with ` _ `, split on any whitespace (Python `str.split()`, dropping empty pieces), and
map each `_` piece to a rest token and every other piece to a word token. Punctuation stays part of its
word. So `go_now` is `go`, rest, `now`; `__` is two rests; `don't_stop,` is `don't`, rest, `stop,`.

`word_index` counts **word tokens only**, from 0. Rests never shift word indexes, so phonetics
overrides keyed by `(line_index, word_index)` (`"<line>:<word>"`) stay valid when rests are typed.

### Syllabify

`syllabify_text` keeps its line handling (text trimmed, split on `\n`, each line trimmed, blank lines
skipped). In each line a rest token becomes the cell `{"rest": true}` (no other keys) in token order;
word tokens are syllabified as before (`syllabify_word`; a word with no pronounceable letters yields no
cells but still takes a `word_index`). `syllable_count` excludes rests. A line of only rests is a
non-blank line whose grid line has only rest cells. `raw_text` of the grid line is the trimmed line text,
marks included.

### Canonical text from a grid line

Walk the cells in order: a rest emits `_`; a syllable whose word (by `word_index`; a cell without one is
anonymous and always emits its syllable) has not been emitted yet emits the word, spelled by its `word`
key when known, else by the concatenated syllables of that word index; later syllables of an emitted word
emit nothing. Join with single spaces. `I _ go`, `_ _ I`, and a rest between the two syllables of
"hello" gives `hello _`. Backend: `rebuild_line_text` (tested on the vectors; the backend itself stores
the text the client sends).

### Display text

For each line (split on `\n`), the word tokens joined by single spaces. Lines that had at least one rest
and no words are dropped; other empty lines (stanza breaks) are kept as empty lines. `a _\n_\nb` gives
`a\nb`; `line\n_ _\n\nnext` gives `line\n\nnext`.

### Search matching

The query is NFC-normalised, trimmed, split on whitespace into terms and lowercased. A term matches when
it is a substring of the display text, NFC-normalised and lowercased. A snippet matches when all its
terms match; no terms match everything. `_` as a term matches nothing (display text has no `_`).

## Data and control flow

```
typed text "I _ go" ──► PUT/POST snippet ──► syllabify_text ──► cells [i(w0), {rest}, go(w1)]
                                       └──► compute_snippet_phonetics: split_lines (same lines as the grid,
                                            rest-only lines included with no words) → word_tokens per line
                                            → words [i, go]; overrides keyed by word index
grid edit (+·) on client ──► rebuildRawText → "I _ _ go" ──► PUT raw_text + grid_data (stored as sent)

readers of meaning ──► display_text(raw_text) = "I go"
  export (text, Markdown, PDF), similarity (TF-IDF), t-SNE embeddings, legacy snippet rhymes,
  rhyme-suggest text anchors, search (both endpoints)
readers of words ──► word_tokens(line)
  phonetics words, rhyme-suggest grid slices (raw_text of a word range), merge word count
```

## Backend consumers

| Consumer | File | Reads |
|----------|------|-------|
| Syllabifier | `app/services/nlp/syllabifier.py` (`syllabify_text`) | `tokenize_line`: rest cells, word indexes over words |
| Snippet phonetics | `app/services/phonetics_service.py` (`compute_snippet_phonetics_with_report`) | `word_tokens` per line; a rest-only line is a `LinePhonetics` with no words, so line indexes match the grid |
| Rhyme notes source | `phonetics_service.note_unit(s)_from_phonetics` | the phonetics words above (no marks) |
| Rhyme-suggest grid slice | `app/services/rhyme_suggest_service.py` (`slice_grid_line`) | `word_tokens(raw_text)[offset:end]` for the slice's `raw_text` (cells keep interior rests) |
| Rhyme-suggest text anchor | `rhyme_suggest_service.resolve_anchor` | `display_text(text)` |
| Rhyme-suggest snippet anchor without `line_index` | `rhyme_suggest_service.resolve_anchor` | the last phonetics line with words, so a trailing rest-only line (`_ _`) is skipped; the clients' `lastNonEmptyLineIndex` skips rest-only grid lines to match |
| Legacy snippet rhymes | `app/services/nlp/rhyme_service.py` (`find_rhymes_in_texts`) | `display_text`; the preview is display text |
| Merge | `app/services/merge_service.py` (`word_count`) | `word_tokens`: horizontal merge re-bases `word_index` by words only; merged `raw_text` keeps the marks |
| Export | `app/services/export_service.py` | `display_text` / `display_lines` |
| Similarity | `app/api/nlp.py` (`similar_snippets`) | `display_text`; snippets whose display text is empty take no part; the response `raw_text` is the stored text |
| t-SNE | `app/api/nlp.py` (`tsne_layout`) | `display_text`; empty display text gets no embedding |
| Per-canvas search | `app/services/nlp/search_service.py` | search matching (above) |
| Search across canvases | `app/services/search/` | search matching (above) |

Not text consumers: the voice-take append (`app/services/voice/append.py`) copies existing grid lines
verbatim and syllabifies only the new lines (whose `_`, if any, become rests); it never matches lines
by text. The word bank's "add from snippet" takes the client's text selection; the backend never reads
snippet text for it.

## Files

| File | Role | Key exports |
|------|------|-------------|
| `backend/app/services/rest_marks.py` | The rule | `REST_MARK`, `Word`, `Rest`, `LineToken`, `tokenize_line`, `word_tokens`, `word_index_per_token`, `has_rest`, `display_lines`, `display_text`, `rebuild_line_text` |
| `backend/app/services/search/matching.py` | Matching rule | `normalize_terms`, `normalize_text`, `matches_terms`, `matches_query`, `title_matches` |
| `backend/tests/fixtures/rest_mark_vectors.json` | Canonical vectors (v1): `tokenize`, `word_indexes`, `rebuild`, `display`, `match` | |
| `backend/tests/rest_marks/test_vectors.py` | Every vector | |
| `backend/tests/rest_marks/test_rest_marks.py` | Helper edge cases | |
| `backend/tests/rest_marks/test_consumers.py` | Syllabifier, phonetics, rhyme, merge, export | |

## Invariants

- A `Word` token is never empty and never contains whitespace or `_`.
- `word_index` of a word is its position among the line's word tokens; rests carry no `word_index`.
- Grid lines and phonetics lines come from the same line split, so `phonetics.lines[i]` describes
  `grid_data.lines[i]` even when a line is only rests.
- Display text never contains `_`; `display_text` is idempotent.
- A rest cell produced by the syllabifier is exactly `{"rest": true}`.
