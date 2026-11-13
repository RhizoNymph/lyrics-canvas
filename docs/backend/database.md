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
User 1──N Canvas 1──N SnippetPlacement N──1 Snippet N──M Tag
               │            │                │
               1──N Group ──┘ (group_id)     ├── owner_id → User (ON DELETE CASCADE)
                                             ├── SnippetEmbedding (1:1, Vector(384))
                                             └── SnippetRecording (N:1, ON DELETE CASCADE)

User 1──N Tag        (user-wide tags, ON DELETE CASCADE)
User 1──N TagPreset  (saved tag expressions, ON DELETE CASCADE)
```

### Canvas

**File:** `app/models/canvas.py`, **Table:** `canvases`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `owner_id` | UUID | - | FK -> users.id, NOT NULL |
| `title` | String(255) | "Untitled Canvas" | |
| `version` | Integer, NOT NULL, ≥ 1 | 1 | Content version: +1 per title change ([sync_versions](../features/sync_versions.md)) |
| `is_public` | Boolean | False | |
| `share_token` | String(64) | null | UNIQUE |
| `viewport_state` | JSONB | `{x:0, y:0, zoom:1}` | |
| `created_at` | DateTime(tz) | now() | |
| `updated_at` | DateTime(tz) | now() | Auto-updates |

Relationships: `owner`, `groups` (cascade: all, delete-orphan), `placements` (passive deletes, the FK
cascades; `lazy="raise"`). `canvas_service.delete_canvas` deletes placements, groups and the canvas
explicitly, then the snippets left with no placement.

### Snippet

**File:** `app/models/snippet.py`, **Table:** `snippets`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `owner_id` | UUID | - | FK -> users.id **ON DELETE CASCADE**, NOT NULL, indexed (`ix_snippets_owner_id`) |
| `snippet_type` | Enum | freestyle | verse/chorus/bridge/hook/freestyle |
| `grid_mode` | Enum | auto | auto/fixed |
| `beats_per_line` | Integer | null | For fixed mode |
| `grid_data` | JSONB | `{lines:[]}` | Syllabified beat grid |
| `raw_text` | Text | '' | |
| `phonetics` | JSONB | null | `SnippetPhonetics` (`app/schemas/phonetics.py`): per-line words/syllables, stress, rhyme keys, overrides, dialect digest |
| `derived_from` | JSONB | null | `DerivedFromModel`: provenance of a snippet promoted from a rhyme candidate |
| `created_at` | DateTime(tz) | now() | |
| `updated_at` | DateTime(tz) | now() | Auto-updates on every write to the row (layout is on the placement and does not move it) |
| `content_updated_at` | DateTime(tz), NOT NULL | now() | Last content edit (text, type, grid); bumped by `snippet_service.update_snippet` (content edits) and `recording_service.append_with_recording` (voice-take append), never by layout or tags. See [snippet_content_timestamp](../features/snippet_content_timestamp.md) |
| `version` | Integer, NOT NULL, ≥ 1 | 1 | Content version: +1 per change of text, type, grid mode, beats, grid or tag links; never layout or phonetics alone ([sync_versions](../features/sync_versions.md)) |

Relationships: `placements` (1:N, passive deletes, `lazy="raise"`), `tags` (N:M via snippet_tags),
`embedding` (1:1, cascade)

Layout (`canvas_id`, position, size, `z_index`, `group_id`) moved to `snippet_placements` in migration
008: one snippet can be on several canvases. See
[snippet_placements.md](../features/snippet_placements.md).

### SnippetPlacement

**File:** `app/models/placement.py`, **Table:** `snippet_placements`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK (`placement_id` on the wire) |
| `snippet_id` | UUID | - | FK -> snippets.id **ON DELETE CASCADE**, NOT NULL, indexed |
| `canvas_id` | UUID | - | FK -> canvases.id **ON DELETE CASCADE**, NOT NULL, indexed |
| `position_x`, `position_y` | Float, NOT NULL | 0 | |
| `width`, `height` | Float, NOT NULL | 300 / 200 | |
| `z_index` | Integer, NOT NULL | 0 | |
| `group_id` | UUID | null | FK -> groups.id **ON DELETE SET NULL**; a group of `canvas_id` (service-enforced) |
| `created_at` | DateTime(tz), NOT NULL | now() | |

Unique `(snippet_id, canvas_id)` (`uq_snippet_placements_snippet_canvas`). Invariant: every snippet
has at least one placement (`app/services/placements/removal.py`).

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

Relationships: `canvas`, `placements` (1:N via `snippet_placements.group_id`; the group's members)

### Tag

**File:** `app/models/tag.py`, **Table:** `tags`. User-wide since migration 005 (see
[tag_expressions.md](../features/tag_expressions.md#2-data-model-migration-005)).

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `user_id` | UUID | - | FK -> users.id, ON DELETE CASCADE |
| `name` | String(100) | - | unique per user ignoring case |
| `color` | String(7) | #6366f1 | `#rrggbb` |
| `created_at` | DateTime(tz) | now() | |
| `version` | Integer, NOT NULL, ≥ 1 | 1 | +1 per name or color change ([sync_versions](../features/sync_versions.md)) |

Index: `uq_tags_user_lower_name` UNIQUE `(user_id, lower(name))`

**Junction table:** `snippet_tags(snippet_id, tag_id)` with CASCADE deletes on both FKs.

Relationships: `snippets` (N:M via snippet_tags)

### TagPreset

**File:** `app/models/tag_preset.py`, **Table:** `tag_presets`

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `user_id` | UUID | - | FK -> users.id, ON DELETE CASCADE |
| `name` | String(100) | - | unique per user ignoring case (`uq_tag_presets_user_lower_name`) |
| `expression` | Text | - | normalized tag expression, non-empty, at most 500 code points |
| `created_at` / `updated_at` | DateTime(tz) | now() | `updated_at` bumped on update |

### BankWord

**File:** `app/models/word_bank.py`, **Table:** `bank_words` (migration 006, see
[word_bank.md](../features/word_bank.md#data-model-migration-006))

| Column | Type | Default | Notes |
|--------|------|---------|-------|
| `id` | UUID | uuid4 | PK |
| `user_id` | UUID | - | FK -> users.id, ON DELETE CASCADE; index `ix_bank_words_user_id` |
| `text` | String(64) | - | normalized word or phrase, case kept; unique per user ignoring case (`uq_bank_words_user_lower_text`) |
| `created_at` | DateTime(tz) | now() | |
| `version` | Integer, NOT NULL, ≥ 1 | 1 | +1 per text change and per tag link or unlink ([sync_versions](../features/sync_versions.md)) |

**Junction table:** `bank_word_tags(bank_word_id, tag_id)` with CASCADE deletes on both FKs.

Relationships: `tags` (N:M via bank_word_tags, `lazy="raise"`, `passive_deletes`)

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
| `words` | JSONB, null | - | Migration 007: STT words `[{text, start_ms, end_ms, probability}]` |
| `line_spans` | JSONB, null | - | `[start, end)` word indices per transcript line |
| `language` | String(8), null | - | STT language |
| `initial_prompt` | Text, null | - | Exact prompt sent to STT (word-bank vocabulary hints) |
| `verified_text` | Text, null | - | The user's checked transcript |
| `verified_at` | DateTime(tz), null | - | Set iff `verified_text` is (`ck_snippet_recordings_verified_pair`) |

Index: `ix_snippet_recordings_snippet_created (snippet_id, created_at)`. The dataset columns are
described in [voice_dataset.md](../features/voice_dataset.md).

No ORM relationship on `Snippet` (snippet loads never touch audio); deletes rely on the FK cascade,
including canvas deletion (a snippet left with no placement is deleted, then the FK). A merge moves
the recordings of the originals it deletes to the merged snippet first
(`snippet_service.replace_with_merged(..., reparent=recording_service.reassign_recordings)`); an
original also placed on another canvas keeps its recordings.

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

**File:** `alembic/versions/005_user_wide_tags.py`

Makes tags user-wide: adds `tags.user_id` (backfilled from the canvas owner, then NOT NULL with a
cascading FK) and `tags.created_at`, merges each user's tags whose names are equal ignoring case into
the one with the smallest `id::text` (links repointed, duplicates dropped), drops `canvas_id` and
`uq_tag_canvas_name`, and adds the unique `(user_id, lower(name))` index. Creates `tag_presets`.
The downgrade drops the presets and splits tags back into one copy per canvas they are used on
(unused ones go to the user's oldest canvas). An existing database needs `alembic upgrade head`
before the backend runs this code (startup `create_all` would otherwise create `tag_presets` but
not alter `tags`).

**File:** `alembic/versions/006_word_bank.py`

Creates `bank_words` (with `ix_bank_words_user_id` and the unique `(user_id, lower(text))` index)
and `bank_word_tags`. The downgrade drops both tables.

**File:** `alembic/versions/007_recording_dataset.py`

Adds the nullable `words`, `line_spans` (jsonb), `language` (varchar(8)), `initial_prompt`,
`verified_text` (text) and `verified_at` (timestamptz) columns to `snippet_recordings`, plus the check
constraint `ck_snippet_recordings_verified_pair` (`verified_text` and `verified_at` both null or both
set). Existing rows keep nulls. The downgrade drops the constraint and all six columns.

**File:** `alembic/versions/008_snippet_placements.py`

Creates `snippet_placements` and copies one placement per snippet from its layout columns (a group of
another canvas is dropped), adds `snippets.owner_id` from the canvas owner, then drops
`snippets.canvas_id, position_x, position_y, width, height, z_index, group_id`. The downgrade restores
the columns from each snippet's oldest placement and turns every other placement into a duplicated
snippet row (content and tag links copied, recordings not), then drops `owner_id` and the table. Details:
[snippet_placements.md](../features/snippet_placements.md#migration-008).

**File:** `alembic/versions/009_content_versions.py`

Adds `version INTEGER NOT NULL DEFAULT 1` with `ck_<table>_version_positive CHECK (version >= 1)` to
`snippets`, `canvases`, `tags` and `bank_words` (existing rows get 1). The downgrade drops the
constraints and columns. Details: [sync_versions.md](../features/sync_versions.md#migration-009).

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

Tables are auto-created on startup in development. Use Alembic migrations in production. On a dev
database created before migration 008, run `alembic upgrade head` before starting the backend:
`create_all` would otherwise create an empty `snippet_placements` table and the migration's
`CREATE TABLE` would then fail.
