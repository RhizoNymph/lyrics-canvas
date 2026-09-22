# Database & Models

Async PostgreSQL with pgvector, SQLAlchemy 2.0 ORM, and Alembic migrations.

## Connection

**File:** `app/db/database.py`

```python
engine = create_async_engine(settings.database_url, echo=False)
async_session = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)

async def get_db():
    async with async_session() as session:
        yield session
```

- Driver: `asyncpg` (PostgreSQL async)
- `expire_on_commit=False`: Objects remain usable after commit
- Injected via `Depends(get_db)` in route handlers

## Base Classes

**File:** `app/models/base.py`

```python
class Base(DeclarativeBase):
    pass

class TimestampMixin:
    created_at: DateTime(tz), server_default=now()
    updated_at: DateTime(tz), server_default=now(), onupdate=now()

def new_uuid():
    return uuid.uuid4()
```

## Model Overview

All models use UUID primary keys and server-side timestamp defaults.

```
User 1──N Canvas 1──N Snippet N──M Tag
                   │           │
                   1──N Group ─┘ (via group_id)
                               │
                         SnippetEmbedding (1:1, Vector(384))
```

### Canvas

**File:** `app/models/canvas.py`, **Table:** `canvases`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `owner_id` | UUID | - | FK -> users.id, NOT NULL |
| `title` | String(255) | "Untitled Canvas" | |
| `is_public` | Boolean | False | |
| `share_token` | String(64) | null | UNIQUE |
| `viewport_state` | JSONB | `{x:0, y:0, zoom:1}` | |
| `created_at` | DateTime(tz) | now() | |
| `updated_at` | DateTime(tz) | now() | Auto-updates |

Relationships: `owner`, `snippets`, `groups`, `tags` (all cascade: all, delete-orphan)

### Snippet

**File:** `app/models/snippet.py`, **Table:** `snippets`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `canvas_id` | UUID | - | FK -> canvases.id |
| `snippet_type` | Enum | freestyle | verse/chorus/bridge/hook/freestyle |
| `grid_mode` | Enum | auto | auto/fixed |
| `beats_per_line` | Integer | null | For fixed mode |
| `grid_data` | JSONB | `{lines:[]}` | Syllabified beat grid |
| `raw_text` | Text | '' | |
| `position_x` | Float | 0.0 | |
| `position_y` | Float | 0.0 | |
| `width` | Float | 300.0 | |
| `height` | Float | 200.0 | |
| `z_index` | Integer | 0 | |
| `group_id` | UUID | null | FK -> groups.id |
| `phonetics` | JSONB | null | `SnippetPhonetics` (`app/schemas/phonetics.py`): per-line words/syllables, stress, rhyme keys, overrides, dialect digest |
| `derived_from` | JSONB | null | `DerivedFromModel`: provenance of a snippet promoted from a rhyme candidate |
| `created_at` | DateTime(tz) | now() | |
| `updated_at` | DateTime(tz) | now() | Auto-updates |

Relationships: `canvas`, `group`, `tags` (N:M via snippet_tags), `embedding` (1:1, cascade)

`phonetics` is computed with `grid_data` on create/update (`app/api/snippets.py`) and backfilled
for older rows by `scripts/backfill_phonetics.py`; see [nlp-phonetics](./nlp-phonetics.md).

### Group

**File:** `app/models/group.py`, **Table:** `groups`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `canvas_id` | UUID | - | FK -> canvases.id |
| `name` | String(255) | - | NOT NULL |
| `position_x` | Float | 0.0 | |
| `position_y` | Float | 0.0 | |
| `created_at` | DateTime(tz) | now() | |

Relationships: `canvas`, `snippets` (1:N via group_id)

### Tag

**File:** `app/models/tag.py`, **Table:** `tags`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `canvas_id` | UUID | - | FK -> canvases.id |
| `name` | String(100) | - | UNIQUE per canvas |
| `color` | String(7) | #6366f1 | Hex color |

Constraint: `UniqueConstraint("canvas_id", "name")`

**Junction table:** `snippet_tags(snippet_id, tag_id)` with CASCADE deletes on both FKs.

Relationships: `canvas`, `snippets` (N:M via snippet_tags)

### SnippetEmbedding

**File:** `app/models/embedding.py`, **Table:** `snippet_embeddings`

| Column | Type | Notes |
|--------|------|-------|
| `snippet_id` | UUID | PK, FK -> snippets.id (CASCADE) |
| `embedding` | Vector(384) | pgvector, NOT NULL |
| `updated_at` | DateTime(tz) | Auto-updates |

Relationship: `snippet` (back_populates)

## Alembic Migrations

**File:** `alembic/versions/001_initial.py`

Creates:
1. `CREATE EXTENSION IF NOT EXISTS vector` (pgvector)
2. `users` table with email index
3. `canvases` table
4. `groups` table
5. `snippets` table with SnippetType/GridMode enums
6. `tags` table with unique(canvas_id, name)
7. `snippet_tags` junction table
8. `snippet_embeddings` table

Downgrade drops all tables and enum types in reverse order.

**File:** `alembic/versions/002_snippet_phonetics.py`

Adds nullable JSONB `snippets.phonetics` and `snippets.derived_from`. Downgrade drops both.
After upgrading, run the backfill from `backend/`:

```bash
uv run python -m scripts.backfill_phonetics --report phonetics_report.csv [--dialect rules.json] [--force]
```

It fills `phonetics` for every snippet where it is NULL (idempotent), keeps existing grid
stress as overrides, and writes one CSV row per disagreement
(`snippet_id, line, word, grid_stress, engine_stress`).

### Running Migrations

```bash
# Apply
docker-compose exec backend alembic upgrade head

# Reset
docker-compose down -v && docker-compose up
```

## Startup Behavior

**File:** `app/main.py`

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)  # Dev auto-create
    yield
    await engine.dispose()
```

Tables are auto-created on startup in development. Use Alembic migrations in production.
