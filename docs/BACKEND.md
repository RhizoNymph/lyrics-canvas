# Lyrics Canvas - Backend Documentation

FastAPI REST API powering authentication, canvas CRUD, NLP analysis, and export.

**Tech Stack**: FastAPI, SQLAlchemy 2.0 (async), PostgreSQL + pgvector, Alembic, JWT auth, sentence-transformers, scikit-learn, pronouncing (CMU dict), reportlab

## Module Documentation

| Module | Scope | Key Files |
|--------|-------|-----------|
| [Authentication](./backend/auth.md) | JWT tokens, bcrypt hashing, user model, login/register | `auth_service.py`, `deps.py`, `auth.py` |
| [Database & Models](./backend/database.md) | Async PostgreSQL, ORM models, Alembic migrations | `database.py`, `models/`, `alembic/` |
| [Canvas & Snippet CRUD](./backend/canvas-crud.md) | Canvas lifecycle, snippet operations, sharing, forking | `canvas_service.py`, `canvases.py` |
| [Groups & Tags](./backend/groups-tags.md) | Group containers, tag labels, snippet-tag associations | `group_service.py`, `tag_service.py` |
| [NLP: Syllabifier](./backend/nlp-syllabifier.md) | CMU dict syllabification, stress markers, heuristic fallback | `nlp/syllabifier.py` |
| [NLP: Rhyme Finder](./backend/nlp-rhymes.md) | Perfect/near rhymes, in-corpus matching | `nlp/rhyme_service.py` |
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
  services/
    auth_service.py                 JWT + bcrypt + user CRUD
    canvas_service.py               Canvas CRUD + sharing + forking
    group_service.py                Group CRUD
    tag_service.py                  Tag CRUD + associations
    export_service.py               Text/Markdown/PDF generation
    nlp/
      syllabifier.py                CMU dict syllabification
      rhyme_service.py              Rhyme detection
      similarity_service.py         TF-IDF cosine similarity
      embedding_service.py          Sentence-transformer embeddings
      tsne_service.py               t-SNE dimensionality reduction
      search_service.py             ILIKE text search
  api/
    deps.py                         Auth dependency injection
    auth.py / canvases.py / groups.py / tags.py / nlp.py / export.py
```

## API Surface (29 endpoints)

| Area | Count | Auth |
|------|-------|------|
| Authentication | 3 | Public / Bearer |
| Canvases | 8 | Bearer (shared is public) |
| Snippets | 4 | Bearer |
| Groups | 3 | Bearer |
| Tags | 4 | Bearer |
| NLP | 5 | Mixed |
| Export | 1 | Bearer |
| Health | 1 | Public |

## Key Patterns

- **Async-first**: All DB operations via asyncpg
- **Lazy model loading**: sentence-transformers loaded on first NLP request
- **Cascade deletes**: Canvas deletion removes all children
- **Batch positioning**: Single PATCH for multi-snippet moves
- **Ownership verification**: All CRUD checks Canvas.owner_id
