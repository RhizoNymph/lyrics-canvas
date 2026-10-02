# Lyrics Canvas - Backend Documentation

FastAPI REST API powering authentication, canvas CRUD, NLP analysis, and export.

**Tech Stack**: FastAPI, SQLAlchemy 2.0 (async), PostgreSQL + pgvector, Alembic, JWT auth, sentence-transformers, scikit-learn, pronouncing (CMU dict), g2p-en, wordfreq, reportlab

## Module Documentation

| Module | Scope | Key Files |
|--------|-------|-----------|
| [Authentication](./backend/auth.md) | JWT tokens, bcrypt hashing, user model, login/register | `auth_service.py`, `deps.py`, `auth.py` |
| [Database & Models](./backend/database.md) | Async PostgreSQL, ORM models, Alembic migrations | `database.py`, `models/`, `alembic/` |
| [Canvas & Snippet CRUD](./backend/canvas-crud.md) | Canvas lifecycle, snippet operations, sharing, forking | `canvas_service.py`, `canvases.py` |
| [Groups & Tags](./backend/groups-tags.md) | Group containers, tag labels, snippet-tag associations | `group_service.py`, `tag_service.py` |
| [NLP: Phonetic Engine](./backend/nlp-phonetics.md) | Pronunciation, dialect map, near-rhyme scoring, rhyme index, snippet phonetics, backfill | `phonetics/`, `phonetics_service.py`, `scripts/backfill_phonetics.py` |
| [NLP: Syllabifier](./backend/nlp-syllabifier.md) | Engine-backed syllabification into the beat grid | `nlp/syllabifier.py` |
| [NLP: Rhymes](./backend/nlp-rhymes.md) | Rhyme suggest endpoint (ranking, slot fit, paging) and the legacy lookup | `rhyme_suggest_service.py`, `nlp/rhyme_service.py` |
| [NLP: Similarity](./backend/nlp-similarity.md) | TF-IDF cosine similarity, sentence embeddings | `nlp/similarity_service.py`, `nlp/embedding_service.py` |
| [NLP: t-SNE Layout](./backend/nlp-tsne.md) | Dimensionality reduction for spatial clustering | `nlp/tsne_service.py` |
| [NLP: Search](./backend/nlp-search.md) | ILIKE full-text search | `nlp/search_service.py` |
| [Export](./backend/export.md) | Markdown, plain text, PDF generation | `export_service.py`, `export.py` |

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
  schemas/
    auth.py / canvas.py / snippet.py / group.py / tag.py / nlp.py
    merge.py                        Merge request/response
    phonetics.py                    SnippetPhonetics (snippets.phonetics column)
    rhyme.py                        Rhyme suggest request/response
  services/
    auth_service.py                 JWT + bcrypt + user CRUD
    canvas_service.py               Canvas CRUD + sharing + forking
    group_service.py                Group CRUD
    tag_service.py                  Tag CRUD + associations
    snippet_service.py              Snippet CRUD + merge transaction (replace_with_merged)
    merge_service.py                Block merge rules (pure)
    export_service.py               Text/Markdown/PDF generation
    phonetics_service.py            Snippet phonetics + grid reconciliation + note units
    rhyme_suggest_service.py        Rhyme suggest: anchors, sources, rank, slot fit, page
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
      search_service.py             ILIKE text search
  api/
    deps.py                         Auth dependency injection
    auth.py / canvases.py / snippets.py / merge.py / groups.py / tags.py / nlp.py / export.py
scripts/
  backfill_phonetics.py             Fill snippets.phonetics for existing rows
  export_openapi.py                 Write docs/api/openapi.json (committed snapshot)
```

## API Surface (33 endpoints)

| Area | Count | Auth |
|------|-------|------|
| Authentication | 4 (register, login, google, me) | Public / Bearer |
| Canvases | 8 | Bearer (shared is public) |
| Snippets | 5 (create, update, delete, batch position, merge) | Bearer |
| Groups | 3 | Bearer |
| Tags | 5 (create, update, delete, link, unlink) | Bearer |
| NLP | 6 | Mixed |
| Export | 1 | Bearer |
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
