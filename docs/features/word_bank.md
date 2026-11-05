# Word Bank

A per-user bank of words and short phrases the writer wants to use later. Words are tagged with the
**existing user-wide tags** (same `tags` table, picker, expression language and presets as
snippets). Clients filter the bank by a tag expression, find bank words that rhyme together
(families, and "rhymes with this word"), search related words by meaning and add them, and turn a
selection of words into a draft that becomes a new snippet in the current canvas.

This is the canonical spec (backend). The clients are documented in
[web_word_bank.md](./web_word_bank.md) and [android_word_bank.md](./android_word_bank.md).

## Scope

- Tables `bank_words` / `bank_word_tags` (migration 006) and the `BankWord` model.
- Word text normalization and validation (`invalid_word`).
- `/api/word-bank` routes: list, batch add, rename, batch delete, batch tag link/unlink, rhymes
  against one anchor, rhyme families, related words.
- `TagResponse.word_count`; `DELETE /api/tags/{id}` removing word links by cascade.
- Tag expressions on typeless subjects (shared vectors v2: `type:` terms are false for a word).
- The related-word embedding index (vocabulary, worker-thread build, disk cache, warmup setting,
  helm values).

## Non-scope

- Server-side filtering of `GET /api/word-bank`: clients load the whole bank and filter locally with
  the shared evaluator.
- A "make snippet" endpoint: clients build the draft text and call the existing
  `POST /api/canvases/{id}/snippets`.
- Rhyming against the dictionary or the user's snippets (that is rhyme-suggest); word bank rhymes
  only rank bank words.
- Custom dialects or score weights for word bank rhymes (the defaults are always used).
- Client UI (sidebar, sheets, draft editor, "+ bank" actions).

## Data model (migration 006)

**Files:** `backend/app/models/word_bank.py`, `backend/alembic/versions/006_word_bank.py`.

| Table | Column | Type | Notes |
|-------|--------|------|-------|
| `bank_words` | `id` | uuid | PK |
| | `user_id` | uuid | FK `users` ON DELETE CASCADE, index `ix_bank_words_user_id` |
| | `text` | varchar(64) | NOT NULL, case kept |
| | `created_at` | timestamptz | NOT NULL, default `now()` |
| `bank_word_tags` | `bank_word_id` | uuid | FK `bank_words` ON DELETE CASCADE, PK part |
| | `tag_id` | uuid | FK `tags` ON DELETE CASCADE, PK part |

- Unique index `uq_bank_words_user_lower_text` on `(user_id, lower(text))`.
- Deleting a user removes their words; deleting a word or a tag removes its links. The ORM
  relationship `BankWord.tags` is `passive_deletes`, `lazy="raise"` (always loaded explicitly with
  `selectinload`); `Tag` has no back-reference, so a tag delete relies on the FK cascade.
- Downgrade drops `bank_word_tags`, both indexes and `bank_words`.
- Startup `create_all` creates both tables on a fresh dev database; an existing one needs
  `alembic upgrade head`.

## Word text rules

**File:** `backend/app/services/word_bank/text.py` (pure).

1. Normalize: Unicode NFC, strip the ends, collapse every internal whitespace run (Python
   `str.split()` whitespace, so NBSP and ideographic space count) to one space.
2. Valid after normalizing, checked in this order (the first failure is the `WordProblem`):
   - `empty`: at least 1 code point;
   - `too_long`: at most 64 code points;
   - `too_many_words`: at most 4 space-separated words;
   - `invalid_character`: only letters (Unicode categories `L*`), the apostrophes `'` (U+0027) and
     `’` (U+2019), the hyphen-minus `-` (U+002D) and spaces. Combining marks (`M*`) that NFC cannot
     compose, digits, other dashes, emoji and format characters are invalid;
   - `no_letter`: at least one letter.
3. Case is kept; uniqueness ignores case (Postgres `lower()`, the same function as the index).

`word_text(raw)` returns the normalized `WordText` or raises `InvalidWordError(problem)`;
`word_texts(raws)` raises with `index` = the first bad position. Related-word queries use
`related_query(raw)`: any text, 1-64 code points after a strip (`query_empty`, `query_too_long`).

## HTTP API

All routes need a bearer token; all data is scoped to the current user. Errors use the tag error
body `detail = {"error": code, "message": text}` (codes are members of `TagErrorCode`). An invalid
`tag_filter` is the existing 422 `invalid_tag_expression` with `expression_error {code, position,
message}`.

| code | status | when |
|---|---|---|
| `invalid_word` | 422 | the text breaks the rules; a batch adds `"index": i`, the first bad position. Also an empty / over-64 `q` on `/related` (no index) |
| `word_exists` | 409 | a rename collides with another of the user's words (ignoring case) |
| `word_not_found` | 404 | an id is not the user's (or is not a UUID) |
| `too_many_words` | 422 | families over more than 1000 filtered words, or a batch over its limit (words > 200, tag_ids > 20, ids / word_ids > 500) |
| `related_words_warming` | 503 | the related-word index is still being built; header `Retry-After: 10` |
| `related_words_unavailable` | 503 | the embedding model cannot be loaded (sticky for the process) |
| `tag_not_found` | 404 | an unknown, foreign or malformed tag id |

Empty lists where the contract says `1..` (`words`, `ids`, `word_ids`), out-of-range numbers, an
unknown `op` and a rhymes request without exactly one of `word_id` / `text` are FastAPI's standard
422 validation errors (no `error` code).

### Shapes

```jsonc
// BankWordResponse (tags sorted by lower(name), then id)
{"id": "f506d16d-…", "text": "night", "tags": [{"id": "a5b1df14-…", "name": "dark", "color": "#112233"}],
 "created_at": "2026-11-04T17:25:01.686045Z"}
```

`TagResponse` gains `word_count` (bank words linked), after `snippet_count`:
`{"id", "name", "color", "snippet_count", "word_count", "created_at"}`.

### Routes

| Method | Path | Body / query | Response |
|---|---|---|---|
| GET | `/api/word-bank` | | 200 `BankWordResponse[]`, newest first (`created_at desc, id`) |
| POST | `/api/word-bank` | `{words: str[] (1..200), tag_ids: str[] (0..20) = []}` | 201 `{created: BankWordResponse[], existing: BankWordResponse[]}` |
| PUT | `/api/word-bank/{id}` | `{text}` | 200 `BankWordResponse`; 404 / 409 / 422 |
| POST | `/api/word-bank/delete` | `{ids: str[] (1..500)}` | 204; ids not the user's are ignored |
| POST | `/api/word-bank/tags` | `{word_ids: str[] (1..500), tag_id, op: "link"\|"unlink"}` | 200 `{words: BankWordResponse[]}` (request order, duplicates once) |
| POST | `/api/word-bank/rhymes` | `{word_id? \| text?, tag_filter?, min_score = 0.5 (0..1), k = 2 (1..4), limit = 100 (1..200)}` | 200 `{anchor, matches}` |
| POST | `/api/word-bank/families` | `{tag_filter?, min_score = 0.8 (0..1), k = 2 (1..4)}` | 200 `{families, unmatched}` |
| GET | `/api/word-bank/related` | `?q=<text>&limit=30 (1..100)` | 200 `{query, results}` |

**Add.** Every word is validated first; one bad word rejects the whole batch (422 `invalid_word` +
`index`). Duplicates inside the batch collapse ignoring case (first spelling wins). A word the user
already has goes to `existing` with its stored spelling (not an error). `tag_ids` are linked to every
created and existing word (idempotent). Both lists keep the input order. Words of one batch share
`created_at` (the transaction time), so in `GET` they follow `id`.

**Rename.** A change of case only (`night` → `Night`) is allowed. A collision is checked with a
lookup and, for races, by the unique index (`IntegrityError` → `word_exists`).

**Tags.** Idempotent. The tag must be the user's (`tag_not_found`); any word id that is not the
user's gives 404 `word_not_found` and nothing changes.

**Rhymes.**

- Anchor: `word_id` (one of the user's words, else `word_not_found`) or `text` (validated with the
  word rules, `invalid_word`; the response echoes the normalized text).
- Candidates: the user's words matching `tag_filter`, excluding the anchor word (for `word_id`) or
  any word equal to `text` ignoring case.
- Each candidate gets the directed score rhyme-suggest would give it for this anchor (see
  [Scoring](#scoring)); below `min_score` is dropped; sort score desc, match_depth desc, lower(text)
  asc, then id; `limit` last.
- A text with no pronounceable syllable (e.g. CJK) answers `anchor.rhyme_key = []`,
  `syllable_count = 0` and no matches; candidates with no pronunciation are skipped.

```json
{"anchor": {"text": "night", "rhyme_key": ["AY", "T"], "syllable_count": 1},
 "matches": [{"word": {"id": "3b0d…", "text": "light", "tags": [{"id": "a5b1…", "name": "dark", "color": "#112233"}],
                       "created_at": "2026-11-04T17:25:01.686045Z"},
              "score": 1.0, "match_depth": 1}]}
```

**Families.** See [Families algorithm](#families-algorithm).

```json
{"families": [
   {"label": "honey", "rhyme_key": ["AH", "N", "IY"], "cohesion": 1.0,
    "words": [{"id": "27ea…", "text": "honey", "tags": […], "created_at": "…"},
              {"id": "a16f…", "text": "money", "tags": […], "created_at": "…"}]},
   {"label": "light", "rhyme_key": ["AY", "T"], "cohesion": 1.0,
    "words": [{"text": "light", …}, {"text": "night", …}]}],
 "unmatched": [{"text": "Moon", …}, {"text": "table", …}]}
```

**Related.** `q` is any text (a word, phrase or theme), 1-64 code points after a strip, else 422
`invalid_word`. Results are vocabulary words by cosine desc (ties by word), excluding the query
itself ignoring case; `bank_word_id` is set when the user already has the word (ignoring case).

```json
{"query": "night",
 "results": [{"text": "dark", "similarity": 0.7123, "bank_word_id": null},
             {"text": "moon", "similarity": 0.6488, "bank_word_id": "bf1f7511-1757-4399-bfa3-739beaad5659"}]}
```

## Data and control flow

```
POST /api/word-bank            app/api/word_bank.py add_words
  limits (_at_most) -> text.word_texts (InvalidWordError + index) -> _tag_ids (malformed -> tag_not_found)
  -> service.add_words: _owned_tags -> _lowered (SQL lower via unnest WITH ORDINALITY) -> collapse
     -> INSERT ... ON CONFLICT DO NOTHING RETURNING id -> ids by lower(text) -> _link (ON CONFLICT DO NOTHING)
     -> commit -> _reload (selectinload tags, populate_existing) -> created / existing in input order
  -> word_response (tags sorted) -> BankWordsCreateResponse

POST /api/word-bank/rhymes     rhymes
  _tag_filter (parse; TagExpressionError -> 422 invalid_tag_expression)
  -> anchor: service.get_word | text.word_text
  -> service.filtered_words (list_words + matches(expr, SnippetFacts.of(None, tag names)))
  -> asyncio.to_thread(_matches): unit_from_text(anchor) -> phonetic_words(candidates)
     -> rhymes.rank_rhymes(anchor, words, PairScorer(k), min_score, limit, exclude)
  -> BankRhymesResponse (ids mapped back to the loaded BankWord rows)

POST /api/word-bank/families   families
  _tag_filter -> service.filtered_words -> asyncio.to_thread(_families):
     families.rhyme_families(phonetic_words(words), PairScorer(k), min_score)  (> 1000 -> TooManyWordsError)
  -> BankFamiliesResponse

GET /api/word-bank/related     related
  text.related_query -> RelatedWords.search (Depends(get_related_words))
     Idle -> start(): create the build task -> 503 related_words_warming (Retry-After: 10)
     Building -> 503 related_words_warming; Unavailable -> 503 related_words_unavailable
     Ready -> asyncio.to_thread(VocabularyIndex.search)
  -> service.ids_by_lower_text -> RelatedWordsResponse

app startup (lifespan): RELATED_WORDS_WARMUP=true -> related_words.start()
DELETE /api/tags/{id}: tag_service.delete_tag -> FK cascade removes bank_word_tags rows
GET /api/tags: TagSummary.word_count by a correlated count over bank_word_tags
```

Bank words are typeless subjects of the tag expression language: `SnippetFacts.snippet_type` is
`None` and every `type:` term is false (so `!type:verse` matches every word). Rhyme-suggest still
passes a real `SnippetType`, so its behaviour is unchanged.

## Scoring

**File:** `backend/app/services/word_bank/phonetic.py`.

- Each bank word becomes a `Unit` once per request (`phonetic_words`, `unit_from_text` with the
  default dialect map, id `bank:<id>`); a word with no pronounceable syllable has `unit = None`.
- The **directed score** of candidate `b` for anchor `a` is exactly what rhyme-suggest gives (its
  `RhymeIndex.query` scores at `k_eff = min(k, syllables(a))`):
  `score(a.effective, b.effective, min(k, syllables(a)), DEFAULT_WEIGHTS)`. A one-syllable anchor
  scores `delight` 1.0 against `night`; a two-syllable anchor takes the short-side discount for a
  one-syllable candidate (`1/1.7`).
- `PairScorer` computes it without calling `score()` per pair: the per-syllable `align_syllable`
  totals are memoized per syllable pair (the deepest aligned syllable is keyed on its rhyme part,
  because `score` ignores its onset) and combined in the same order and with the same rules as
  `score` (depth weights, the perfect-match rule, `match_depth` threshold, clamping). Phone
  distances are symmetric, so a syllable pair's total serves both orders, and two tails of equal
  length score the same both ways. Tests assert exact equality with `score()` over random dictionary
  words for every `k`, and equality with `RhymeIndex.query` hits.
- **Similarity** for families is `s(a, b) = max(score(a, b), score(b, a))`.

Cost: 1000 random common words, `k = 2`, take about 2 s in the worker thread (about 117k
`align_syllable` calls for 500k pairs); typical banks of a few hundred words take well under a second.

## Families algorithm

**File:** `backend/app/services/word_bank/families.py` (pure, deterministic).

1. More than `MAX_FAMILY_WORDS = 1000` input words raise `TooManyWordsError` before scoring.
2. Words without a pronunciation go straight to `unmatched`. The rest are sorted by
   `(lower(text), id)`, so the input order never matters.
3. Pairwise similarity matrix (diagonal 1.0).
4. Average-linkage agglomerative clustering with Lance-Williams updates on a numpy matrix: merge the
   most similar pair of clusters while their average similarity is `>= min_score`. Ties (exactly
   equal averages) go to the pair whose two clusters' lowest `(lower(text), id)` keys, sorted, are
   smallest.
5. Clusters of 2 or more are families; singletons go to `unmatched`.
6. Medoid: the highest mean similarity to the rest of the family, ties by `(lower(text), id)`.
   `label` = medoid text, `rhyme_key` = the medoid unit's rhyme key, `words` = medoid, then the rest
   by similarity to the medoid desc, then `(lower(text), id)`. `cohesion` = mean pairwise similarity.
7. Families sort by size desc, cohesion desc, then `lower(label)`, `label`; `unmatched` by
   `(lower(text), id)`.

## Related words

**Files:** `backend/app/services/word_bank/related.py`, `related_cache.py`.

- **Vocabulary:** the sorted, distinct plain words of the rhyme dictionary at the common threshold,
  `dictionary_entries(include_rare=False)` (CMU words with Zipf >= 3.0).
- **Model:** `all-MiniLM-L6-v2` loaded by `embedding_service.get_model()` (`MODEL_NAME`); rows are
  L2-normalized into a float32 matrix (a zero vector stays zero). A query is embedded the same way;
  cosine is a dot product.
- **Build:** once per process, in a worker thread (`asyncio.to_thread`), started by the first search
  or at startup when `RELATED_WORDS_WARMUP=true`. `RelatedWords` keeps a closed state
  (`_Idle | _Building(task) | _Ready(index) | _Unavailable(reason)`, frozen dataclasses) changed only
  on the event loop; `start()` is guarded by an `asyncio.Lock`, the end of the build sets an
  `asyncio.Event` (`wait()`). A build that raises `RelatedWordsUnavailableError` (model missing,
  wrong output shape, encoder `RuntimeError`/`ValueError`) or dies of any other exception (seen via
  the finished task) becomes `_Unavailable` for the rest of the process; restart the pod to retry.
- **Disk cache:** `<cache dir>/related-words-<model>-<sha256 of the newline-joined vocabulary>.npy`.
  The cache dir is `RELATED_WORDS_CACHE_DIR`, else `$HF_HOME/lyrics-canvas`, else
  `~/.cache/lyrics-canvas`. Written atomically (temp file + rename). A missing, unreadable or
  mis-shaped file is a miss; an unwritable directory is logged and the in-memory index is used.
  On a hit the model is still loaded (queries need it) but the vocabulary is not re-encoded.
- **Logs:** `related_words_index_building`, `related_words_index_built words=<n> seconds=<s>
  cache=hit|miss model=<name>`, `related_words_unavailable reason=…`, `related_words_cache_unreadable`,
  `related_words_cache_mismatch`, `related_words_cache_unwritable`.

### Deployment

- Settings (`backend/app/config.py`, non-secret): `related_words_warmup: bool = False`
  (`RELATED_WORDS_WARMUP`), `related_words_cache_dir: str = ""` (`RELATED_WORDS_CACHE_DIR`).
- Helm (`deploy/helm/lyrics-canvas`): `backend.relatedWords.warmup` (default `true`) renders
  `RELATED_WORDS_WARMUP`; `backend.relatedWords.cacheDir` (default empty) renders
  `RELATED_WORDS_CACHE_DIR` only when set. The backend pod sets `HF_HOME=/cache/huggingface` and
  mounts the `model-cache` volume at `/cache`, so the default cache dir is
  `/cache/huggingface/lyrics-canvas`, inside that volume. With
  `backend.modelCache.persistence.enabled=true` it is a PVC and the matrix (and the model download)
  survive pod restarts; with the default `emptyDir` they are rebuilt on every new pod.

## Files

| File | Role | Key exports |
|---|---|---|
| `backend/app/models/word_bank.py` | ORM table and junction | `BankWord`, `bank_word_tags`, `MAX_WORD_LENGTH` |
| `backend/alembic/versions/006_word_bank.py` | Migration 006 | `upgrade`, `downgrade` |
| `backend/app/services/word_bank/text.py` | Text rules (pure) | `normalize_word`, `word_problem`, `word_text`, `word_texts`, `related_query`, `WordProblem`, `WordText`, `RelatedQuery`, `MAX_WORDS`, `MAX_QUERY_LENGTH` |
| `backend/app/services/word_bank/errors.py` | Typed errors (subclasses of `TagServiceError`) | `WordBankError`, `InvalidWordError(problem, index)`, `WordExistsError`, `WordNotFoundError`, `TooManyWordsError`, `RelatedWordsWarmingError` (`retry_after`), `RelatedWordsUnavailableError`, `RETRY_AFTER_SECONDS` |
| `backend/app/services/tag_errors.py` | Shared error-code enum | `TagErrorCode` (+ six word bank codes) |
| `backend/app/services/word_bank/service.py` | Owner-scoped DB operations | `list_words`, `filtered_words`, `word_matches`, `get_word`, `add_words` → `AddResult`, `rename_word`, `delete_words`, `set_tag`, `TagOp`, `ids_by_lower_text` |
| `backend/app/services/word_bank/phonetic.py` | Units and memoized scorer (pure) | `PhoneticWord`, `phonetic_words`, `PairScorer` (`directed`, `similarity`, `computed`), `PairScore` |
| `backend/app/services/word_bank/rhymes.py` | Rank against one anchor (pure) | `rank_rhymes`, `RhymeMatch` |
| `backend/app/services/word_bank/families.py` | Clustering (pure) | `rhyme_families`, `Family`, `FamiliesResult`, `Similarity`, `MAX_FAMILY_WORDS` |
| `backend/app/services/word_bank/related.py` | Related-word index and process holder | `RelatedWords` (`start`, `wait`, `search`, `ready`), `VocabularyIndex`, `RelatedHit`, `build_vocabulary_index`, `BuiltIndex`, `Encoder`, `resolve_cache_dir`, `default_vocabulary`, `default_encoder`, `default_builder` |
| `backend/app/services/word_bank/related_cache.py` | Matrix disk cache | `cache_path`, `vocabulary_digest`, `load_matrix`, `save_matrix` |
| `backend/app/services/nlp/embedding_service.py` | Model loader | `MODEL_NAME`, `get_model` |
| `backend/app/services/tag_expr/evaluate.py` | Typeless subjects | `SnippetFacts(snippet_type: SnippetType \| None, …)`, `matches` |
| `backend/app/services/tag_service.py` | Tag counts | `TagSummary.word_count` |
| `backend/app/schemas/word_bank.py` | Pydantic models | `BankWordResponse`, `BankWordsCreate(Response)`, `BankWordUpdate`, `BankWordsDelete`, `BankWordTagsUpdate`, `BankWordsResponse`, `BankRhymesRequest/Response`, `BankRhymeAnchor`, `BankRhymeMatch`, `BankFamiliesRequest/Response`, `BankFamily`, `RelatedWordResult`, `RelatedWordsResponse`, `WordBankErrorBody/Response`, `MAX_ADD_WORDS`, `MAX_ADD_TAGS`, `MAX_IDS` |
| `backend/app/schemas/tag.py` | Tag shapes | `TagResponse.word_count` |
| `backend/app/api/word_bank.py` | Routes | `router`, `related_words`, `get_related_words`, `word_response` |
| `backend/app/api/word_bank_errors.py` | Error mapping | `word_bank_http_error` (adds `index`, `Retry-After`), `word_bank_errors` |
| `backend/app/api/tags.py` | Tag routes | `word_count` in `_tag_response` |
| `backend/app/config.py` | Settings | `related_words_warmup`, `related_words_cache_dir` |
| `backend/app/main.py` | Router registration, warmup in `lifespan` | |
| `spec/tag_expression_vectors.json` | Shared vectors v2 (typeless eval cases) | |
| `deploy/helm/lyrics-canvas/values.yaml`, `values.schema.json`, `templates/backend/deployment.yaml`, `README.md` | `backend.relatedWords.{warmup,cacheDir}` | |
| `backend/tests/word_bank/` | Tests: text rules, scorer, rhymes, families, related index (fake encoder), routes without DB, service / API / migration against PostgreSQL | |

## Invariants and constraints

- Every word belongs to one user; `(user_id, lower(text))` is unique; stored text is normalized and
  valid (validated before the service, `WordText`).
- A tag and a word are linked only when both belong to the same user (`_owned_tags`, owned word
  checks before linking).
- Batch operations are all-or-nothing: one invalid word, an unknown tag or a foreign word id changes
  nothing.
- Every filtered word appears exactly once in families or `unmatched`; every family has >= 2 words.
- Rhymes and families are deterministic: they do not depend on input or DB order.
- The phonetic CPU work and the index build never run on the event loop (`asyncio.to_thread`); the
  index is built at most once per process.
- `GET /api/word-bank/related` never blocks on the build: while it runs it answers 503 with
  `Retry-After: 10`.
- `spec/tag_expression_vectors.json` is byte-identical across backend, web and Android (sha256
  `4472bcd39999d293236f1fbb24c27928e74e16ddcd16d15223326687f76a80a6`) and never edited by hand.
