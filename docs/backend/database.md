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
                               │
                         SnippetRecording (N:1, ON DELETE CASCADE)
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
| `updated_at` | DateTime(tz) | now() | Auto-updates on every write, moves and resizes included |
| `content_updated_at` | DateTime(tz), NOT NULL | now() | Last content edit (text, type, grid); bumped by `snippet_service.update_snippet` (content edits) and `recording_service.append_with_recording` (voice-take append), never by layout or tags. See [snippet_content_timestamp](../features/snippet_content_timestamp.md) |

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

### SnippetRecording

**File:** `app/models/recording.py`, **Table:** `snippet_recordings`

One row per voice take ([voice_capture.md](../features/voice_capture.md)).

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `snippet_id` | UUID | - | FK -> snippets.id **ON DELETE CASCADE**, NOT NULL |
| `mime_type` | String(127) | - | As uploaded (type/subtype lower-cased, parameters kept) |
| `duration_ms` | Integer | - | STT duration, else the client's |
| `byte_size` | Integer | - | `len(audio)` |
| `audio` | LargeBinary (bytea) | - | **Deferred**: only `GET /api/recordings/{id}/audio` loads it |
| `transcript` | Text | - | Assembled lines joined by `\n` |
| `split_mode` | Enum `recording_split_mode` | - | `taps` / `auto` / `single` (`SplitMode`) |
| `created_at` | DateTime(tz), NOT NULL | now() | Returned on insert (`eager_defaults`) |

Index: `ix_snippet_recordings_snippet_created (snippet_id, created_at)`.

No ORM relationship on `Snippet` (snippet loads never touch audio); deletes rely on the FK cascade,
including canvas deletion (ORM cascade to snippets, then the FK). A merge moves the originals'
recordings to the merged snippet before deleting them
(`snippet_service.replace_with_merged(..., reparent=recording_service.reassign_recordings)`).

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

**File:** `alembic/versions/003_snippet_content_updated_at.py`

Adds `snippets.content_updated_at` (timestamptz, server default `now()`), backfills it with
`COALESCE(updated_at, created_at, now())`, then makes it NOT NULL. Downgrade drops it. Startup
`create_all` does not add columns to an existing table, so an existing database needs
`alembic upgrade head` before the backend runs this code.

**File:** `alembic/versions/004_snippet_recordings.py`

Creates the `recording_split_mode` enum type, the `snippet_recordings` table (FK to `snippets` with
`ON DELETE CASCADE`) and its `(snippet_id, created_at)` index. Downgrade drops the index, the table and
the type. Startup `create_all` creates the table on a fresh dev database; an existing database needs
`alembic upgrade head`.

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
