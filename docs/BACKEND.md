# Lyrics Canvas - Backend Documentation

FastAPI REST API powering authentication, canvas CRUD, NLP analysis, and export.

**Tech Stack**: FastAPI, SQLAlchemy 2.0 (async), PostgreSQL + pgvector, Alembic, JWT auth, sentence-transformers, scikit-learn, pronouncing (CMU dict), g2p-en, wordfreq, reportlab

## Module Documentation

| Module | Scope | Key Files |
|--------|-------|-----------|
| [Authentication](./backend/auth.md) | JWT tokens, bcrypt hashing, user model, login/register | `auth_service.py`, `deps.py`, `auth.py` |
| [Database & Models](./backend/database.md) | Async PostgreSQL, ORM models, Alembic migrations | `database.py`, `models/`, `alembic/` |
| [Canvas & Snippet CRUD](./backend/canvas-crud.md) | Canvas lifecycle, snippet operations, sharing, forking | `canvas_service.py`, `canvases.py` |
| [Groups & Tags](./backend/groups-tags.md) | Group containers, user-wide tag labels, snippet-tag associations, tag presets ([tag_expressions](./features/tag_expressions.md)) | `group_service.py`, `tag_service.py`, `tag_preset_service.py`, `tag_expr/` |
| [NLP: Phonetic Engine](./backend/nlp-phonetics.md) | Pronunciation, dialect map, near-rhyme scoring, rhyme index, snippet phonetics, backfill | `phonetics/`, `phonetics_service.py`, `scripts/backfill_phonetics.py` |
| [NLP: Syllabifier](./backend/nlp-syllabifier.md) | Engine-backed syllabification into the beat grid | `nlp/syllabifier.py` |
| [NLP: Rhymes](./backend/nlp-rhymes.md) | Rhyme suggest endpoint (ranking, slot fit, paging) and the legacy lookup | `rhyme_suggest_service.py`, `nlp/rhyme_service.py` |
| [NLP: Similarity](./backend/nlp-similarity.md) | TF-IDF cosine similarity, sentence embeddings | `nlp/similarity_service.py`, `nlp/embedding_service.py` |
| [NLP: t-SNE Layout](./backend/nlp-tsne.md) | Dimensionality reduction for spatial clustering | `nlp/tsne_service.py` |
| [NLP: Search](./backend/nlp-search.md) | Per-canvas search (rest-aware matching) | `nlp/search_service.py` |
| [Search across canvases](./features/global_search.md) | `GET /api/search`: canvases by title, snippets by display text | `search/`, `api/search.py` |
| [Rest marks](./features/rest_marks.md) | `_` rest rule: tokens, word indexes, display text, every text consumer | `rest_marks.py` |
| [Export](./backend/export.md) | Markdown, plain text, PDF generation | `export_service.py`, `export.py` |
| [Snippet Placements](./features/snippet_placements.md) | Snippet content vs per-canvas layout: placements, migration 008, transfer, remove / delete everywhere, canvas titles, typed placement errors | `services/placements/`, `models/placement.py`, `api/placements.py`, `canvas_title.py` |
| [Voice Capture](./features/voice_capture.md) | Voice takes: upload, STT client, line assembly, append, recordings storage | `api/voice.py`, `services/voice/`, `recording_service.py` |
| [Word Bank](./features/word_bank.md) | Per-user tagged words: CRUD, bulk tagging, rhymes / families over the bank, related words by embedding | `api/word_bank.py`, `services/word_bank/`, `models/word_bank.py` |
| [Sync Versions](./features/sync_versions.md) | Offline sync: content `version` (migration 009), `base_version` checks (409 `version_conflict` + `current`), client ids on creates (409 `id_conflict`), `GET /api/sync/snapshot` | `services/sync/`, `api/sync.py`, `api/sync_errors.py`, `models/versioned.py` |

## Architecture Summary

```
app/
  main.py                           FastAPI app, lifespan, CORS, routers
  config.py                         Pydantic settings (env/dotenv)
  db/
    database.py                     Async engine + session factory
  models/
    base.py                         DeclarativeBase, TimestampMixin
    user.py / canvas.py / snippet.py / group.py / tag.py / embedding.py
    recording.py                    SnippetRecording (voice-take audio), SplitMode
    tag_preset.py                   TagPreset (saved tag expressions)
  schemas/
    auth.py / canvas.py / snippet.py / group.py / tag.py / nlp.py
    merge.py                        Merge request/response
    phonetics.py                    SnippetPhonetics (snippets.phonetics column)
    rhyme.py                        Rhyme suggest request/response
    voice.py                        Voice-take meta (discriminated target), recording/take responses
  services/
    auth_service.py                 JWT + bcrypt + user CRUD
    canvas_service.py               Canvas CRUD + sharing + forking
    group_service.py                Group CRUD
    tag_service.py                  User-wide tag CRUD + associations; rename rewrites presets
    tag_preset_service.py           Tag expression preset CRUD
    tag_values.py / tag_errors.py   Validated names/colors/expressions; typed tag error codes
    tag_expr/                       Tag expression language (pure): lexer, parser, ast, format, evaluate, rename
    snippet_service.py              Snippet CRUD + merge transaction (replace_with_merged, reparent hook)
    snippet_content.py              Shared derivation: grid, phonetics, size (create + voice takes)
    recording_service.py            Voice takes (create/append + recording, one transaction), recordings
    merge_service.py                Block merge rules (pure)
    export_service.py               Text/Markdown/PDF generation
    phonetics_service.py            Snippet phonetics + grid reconciliation + note units
    rhyme_suggest_service.py        Rhyme suggest: anchors, sources, rank, slot fit, page
    voice/                          Voice takes
      line_assembly.py              Pure: taps / auto split (pause, balance, rhyme) -> lines
      engine_oracle.py              Syllables + end-rhyme scores from the phonetic engine
      append.py                     Pure: append lines keeping the existing grid verbatim
      audio.py / stt_client.py / errors.py   MIME + bounded read, STT HTTP client, typed errors
    phonetics/                      Phonetic engine (pure)
      types.py / errors.py / distance.py / syllables.py / dialect.py
      score.py / index.py / pronounce.py / resources.py / units.py
      sources/                      base.py / dictionary.py / notes.py
    nlp/
      syllabifier.py                Engine-backed syllabification
      rhyme_service.py              Legacy rhyme lookup (engine-backed)
      similarity_service.py         TF-IDF cosine similarity
      embedding_service.py          Sentence-transformer embeddings
      tsne_service.py               t-SNE dimensionality reduction
      search_service.py             Per-canvas search (shared matching rule)
    rest_marks.py                   `_` rest rule: tokenize, word tokens, display text, rebuild
    search/                         Search across canvases: query, matching, ILIKE prefilter, excerpt, service
  api/
    deps.py                         Auth dependency injection
    auth.py / canvases.py / snippets.py / merge.py / groups.py / tags.py / nlp.py / export.py / voice.py / search.py
    tag_presets.py / tag_errors.py  Preset routes; tag error envelope
scripts/
  backfill_phonetics.py             Fill snippets.phonetics for existing rows
  export_openapi.py                 Write docs/api/openapi.json (committed snapshot)
```

## API Surface (42 endpoints)

| Area | Count | Auth |
|------|-------|------|
| Authentication | 4 (register, login, google, me) | Public / Bearer |
| Canvases | 8 | Bearer (shared is public) |
| Snippets | 5 (create, update, delete, batch position, merge) | Bearer |
| Groups | 3 | Bearer |
| Tags | 6 (list, create, update, delete, link, unlink) | Bearer |
| Tag presets | 4 (list, create, update, delete) | Bearer |
| NLP | 6 | Mixed |
| Search | 1 (across canvases) | Bearer |
| Export | 1 | Bearer |
| Voice | 4 (voice-takes, list recordings, recording audio, delete recording) | Bearer |
| Sync | 1 (snapshot) | Bearer |
| Health | 1 | Public |

Every route the clients call declares a pydantic `response_model`; the generated OpenAPI document is
committed at [docs/api/openapi.json](./api/README.md) and a test fails when it drifts.

## Key Patterns

- **Async-first**: All DB operations via asyncpg
- **Lazy model loading**: sentence-transformers loaded on first NLP request; the dictionary rhyme index and g2p_en on first rhyme request
- **Cascade deletes**: Canvas deletion removes all children
- **Batch positioning**: Single PATCH for multi-snippet moves
- **Ownership verification**: All CRUD checks Canvas.owner_id
- **Typed responses**: response models keep the historical wire shape (`tests/test_response_shapes.py`); grids are emitted exactly as stored
- **Server-side merge**: `POST /api/canvases/{id}/merge` replaces 2+ snippets in one transaction ([merge_blocks.md](./features/merge_blocks.md))
- **Content versions**: snippets, canvases, tags and words carry `version` (+1 per content change, never layout); mutations take an optional `base_version` (409 `version_conflict` with `current`), creates an optional client `id` (idempotent, 409 `id_conflict`); `GET /api/sync/snapshot` returns all of a user's data ([sync_versions.md](./features/sync_versions.md))
- **Snippet placements**: a snippet's layout is per canvas; one snippet can be on several canvases, moved or also shown via `POST /api/canvases/{id}/snippets/transfer`; PUT and batch-position require `canvas_id` ([snippet_placements.md](./features/snippet_placements.md))
- **Voice takes**: `POST /api/canvases/{id}/voice-takes` transcribes via the STT service (`STT_URL`) and creates or appends in one transaction; contract error codes in `detail.error` ([voice_capture.md](./features/voice_capture.md)); word-bank vocabulary hints as Whisper `initial_prompt`, stored STT words / line spans, transcript verification, stats and zip dataset export ([voice_dataset.md](./features/voice_dataset.md))
