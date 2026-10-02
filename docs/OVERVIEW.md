# Lyrics Canvas - Project Overview

Lyrics Canvas is a web application for songwriters and lyricists to compose, organize, and analyze lyrics on an interactive infinite canvas. It combines a visual spatial workspace with NLP-powered tools for syllabification, rhyme finding, semantic similarity, and search.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     Browser (SPA)                       │
│                                                         │
│  React 18 + TypeScript + Pixi.js v7 + Zustand          │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌───────────┐  │
│  │ Canvas   │ │ BeatGrid │ │ Sidebars │ │  Modals   │  │
│  │ Viewport │ │ Editor/  │ │ (Rhyme,  │ │ (Search,  │  │
│  │ (Pixi)   │ │ Display  │ │ Similar) │ │  Export)  │  │
│  └──────────┘ └──────────┘ └──────────┘ └───────────┘  │
│                        │                                │
│                   Axios + JWT                           │
└────────────────────────┼────────────────────────────────┘
                         │
                    REST API (/api/*)
                         │
┌────────────────────────┼────────────────────────────────┐
│                   FastAPI Backend                        │
│                                                         │
│  ┌─────────┐ ┌──────────┐ ┌────────────────────────┐   │
│  │ Auth    │ │ Canvas/  │ │     NLP Services       │   │
│  │ (JWT,   │ │ Snippet/ │ │ ┌────────┐ ┌────────┐ │   │
│  │ bcrypt) │ │ Group/   │ │ │Syllabi-│ │ Rhyme  │ │   │
│  │         │ │ Tag CRUD │ │ │fier    │ │ Finder │ │   │
│  └─────────┘ └──────────┘ │ ├────────┤ ├────────┤ │   │
│                            │ │TF-IDF  │ │ t-SNE  │ │   │
│  SQLAlchemy 2.0 (async)    │ │Similar.│ │ Layout │ │   │
│  ┌──────────────────────┐  │ ├────────┤ ├────────┤ │   │
│  │ PostgreSQL + pgvector│  │ │Sentence│ │ Search │ │   │
│  │ (asyncpg)            │  │ │Transf. │ │ (ILIKE)│ │   │
│  └──────────────────────┘  │ └────────┘ └────────┘ │   │
│                            └────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
```

## Core Concepts

### Canvas & Snippets
A **canvas** is a user's workspace. Each canvas contains **snippets** — rectangular cards holding lyrics text. Snippets have a type (verse, chorus, bridge, hook, freestyle) displayed as a color-coded header. Users create snippets by double-clicking the canvas and arrange them freely by dragging.

### Beat Grid
Each snippet's text is **syllabified** using the CMU Pronouncing Dictionary. The result is a grid where each cell is one syllable with stress markers (0=unstressed, 1=primary, 2=secondary). Users can insert "rests" (silence beats) and choose between auto-expanding or fixed-beat grid modes. Syllable counts are displayed per line. In the inline editor the grid can be corrected by hand: syllable chunks drag along their line, rests are inserted or removed, and lines move up or down (see [Grid Rearrange](./features/grid_rearrange.md)).

### Groups & Tags
**Groups** are named containers that visually wrap related snippets with a bounding box. Moving a group moves all its snippets. **Tags** are canvas-scoped color labels that can be attached to snippets and used as visibility filters — when a tag filter is active, only snippets with that tag are shown. Tags are assigned via a right-click context menu on snippets, which also supports inline tag creation (auto-assigned to the snippet), editing (name and color), and snippet deletion. Assigned tags appear as colored pill labels in the snippet header.

### NLP Features
- **Syllabification**: CMU dict phoneme-based syllable splitting with heuristic fallback
- **Rhyme sidebar**: a phonetic engine (CMU dict + g2p_en + heuristic pronunciation, user-editable dialect map, articulatory near-rhyme scoring, hash-bucket rhyme index) powers `POST /api/nlp/rhyme-suggest` (ranked, slot-checked candidates from the dictionary and the user's own lines) and the legacy `GET /api/nlp/rhymes` lookup; every snippet stores `phonetics` reconciled with its beat grid
- **Semantic Similarity**: TF-IDF vectorization + cosine similarity to find related snippets
- **t-SNE Layout**: Sentence embeddings (all-MiniLM-L6-v2, 384-dim) reduced to 2D for spatial clustering
- **Full-Text Search**: ILIKE-based search across snippet text within a canvas

### Canvas Interactions
- **Pan**: Middle-mouse drag or Alt+Left-drag
- **Zoom**: Ctrl/Cmd+Wheel (1.1x steps), with per-snippet scaling (0.3x-4x)
- **Select**: Click (Shift for multi-select)
- **Edit**: Double-click a snippet to open the text/type editor
- **Tag Assignment**: Right-click a snippet to open context menu for assigning/editing tags and deleting snippets
- **Merge**: With 2+ snippets selected, merge them vertically (Ctrl/Cmd+Shift+V) or horizontally (Ctrl/Cmd+Shift+H) from the context menu, toolbar, or keyboard; the server merges and replaces the originals in one transaction; see [merge_blocks.md](./features/merge_blocks.md)
- **Undo/Redo**: 50-item snapshot history (Ctrl+Z / Ctrl+Shift+Z)
- **Export**: Markdown, plain text, or PDF

## Module Documentation

| Document | Scope |
|----------|-------|
| [FRONTEND.md](./FRONTEND.md) | Frontend index with links to granular module docs |
| [BACKEND.md](./BACKEND.md) | Backend index with links to granular module docs |
| [INFRASTRUCTURE.md](./INFRASTRUCTURE.md) | Docker Compose, PostgreSQL/pgvector setup, networking, dev workflow |
| [DESIGN.md](./DESIGN.md) | Original feature requirements and design intent |

### Frontend Modules

| Module | Scope |
|--------|-------|
| [Canvas Rendering](./frontend/canvas-rendering.md) | Pixi.js viewport, snippet cards, grid background, group containers |
| [Beat Grid](./frontend/beat-grid.md) | Syllable grid display and editing |
| [Sidebars](./frontend/sidebars.md) | Rhyme sidebar and similarity analysis panel |
| [Toolbar & Tags](./frontend/toolbar.md) | Zoom, undo/redo, tool toggles, tag filter pills |
| [Modals](./frontend/modals.md) | Search and export dialogs |
| [Pages & Routing](./frontend/pages-routing.md) | SPA routes, auth pages, canvas list, canvas editor |
| [State Management](./frontend/state-management.md) | Zustand stores (auth, canvas, UI) |
| [API Client & Types](./frontend/api-client.md) | Axios HTTP client, TypeScript interfaces |

### Backend Modules

| Module | Scope |
|--------|-------|
| [Authentication](./backend/auth.md) | JWT, bcrypt, user model, login/register |
| [Database & Models](./backend/database.md) | PostgreSQL, ORM models, Alembic migrations |
| [Canvas & Snippet CRUD](./backend/canvas-crud.md) | Canvas lifecycle, snippets, sharing, forking |
| [Groups & Tags](./backend/groups-tags.md) | Group containers, tag labels, associations |
| [NLP: Phonetic Engine](./backend/nlp-phonetics.md) | Pronunciation, dialect map, rhyme scoring and index, snippet phonetics, backfill |
| [NLP: Syllabifier](./backend/nlp-syllabifier.md) | Engine-backed syllabification, stress markers |
| [NLP: Rhymes](./backend/nlp-rhymes.md) | Rhyme suggest endpoint and legacy lookup |
| [NLP: Similarity](./backend/nlp-similarity.md) | TF-IDF cosine similarity, sentence embeddings |
| [NLP: t-SNE Layout](./backend/nlp-tsne.md) | Dimensionality reduction for spatial clustering |
| [NLP: Search](./backend/nlp-search.md) | Full-text search |
| [Export](./backend/export.md) | Markdown, plain text, PDF generation |

## Tech Stack Summary

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Frontend rendering | Pixi.js v7 + `@pixi/react` | GPU-accelerated 2D canvas |
| Frontend framework | React 18 + TypeScript | Component UI |
| State management | Zustand | Three stores: auth, canvas, UI |
| Routing | react-router-dom v6 | SPA navigation with protected routes |
| HTTP client | Axios | JWT-intercepted API calls |
| Build tool | Vite 5 | HMR dev server, production bundler |
| Backend framework | FastAPI | Async REST API |
| ORM | SQLAlchemy 2.0 (async) | Database access with asyncpg |
| Database | PostgreSQL 16 + pgvector | Relational data + vector embeddings |
| Migrations | Alembic | Schema versioning |
| Auth | JWT (python-jose) + bcrypt | Stateless authentication |
| Embeddings | sentence-transformers (all-MiniLM-L6-v2) | 384-dim semantic vectors |
| Similarity | scikit-learn TF-IDF + cosine | Snippet comparison |
| Visualization | scikit-learn t-SNE | Dimensionality reduction |
| Rhymes | pronouncing (CMU dict), g2p-en, wordfreq | Phonetic engine: pronunciation, near-rhyme scoring, rhyme index |
| Export | reportlab, markdown | PDF/MD/TXT generation |
| Containerization | Docker Compose | 3-service dev environment |

## Data Model (Simplified)

```
User 1──N Canvas 1──N Snippet N──M Tag
                   │           │
                   1──N Group ─┘ (via group_id)
                               │
                         SnippetEmbedding (1:1, Vector(384))
```

## API Surface

| Area | Endpoints | Auth |
|------|-----------|------|
| Authentication | 4 (register, login, google, me) | Public / Bearer |
| Canvases | 8 (CRUD + share + fork) | Bearer (shared is public) |
| Snippets | 5 (CRUD + batch position + merge) | Bearer |
| Tags | 5 (CRUD + update + link/unlink) | Bearer |
| Groups | 3 (CRUD) | Bearer |
| NLP | 6 (syllabify, search, similar, tsne, rhymes, rhyme-suggest) | Mixed |
| Export | 1 (text/md/pdf) | Bearer |
| Health | 1 | Public |
| **Total** | **33 endpoints** | |

The routes the clients use declare typed pydantic response models; the generated OpenAPI document is
committed at `docs/api/openapi.json` (see [api/README.md](./api/README.md)) and is the contract the
Android client is checked against.

## Development Quick Start

```bash
# Start everything
docker-compose up

# Frontend:  http://localhost:5173
# Backend:   http://localhost:8000
# Swagger:   http://localhost:8000/docs
# Postgres:  localhost:5435 (lyrics/lyrics_dev)
```

All services hot-reload via bind-mounted source volumes.

## Features Index

```yaml
Features Index:
  rhyme_view:
    description: Select a snippet (whole last line, or a word range of a line picked on its sidebar chip) or type a phrase; a sidebar lists engine-scored rhyme and near-rhyme candidates that fit the slot, each materializable into a snippet on the canvas.
    entry_points:
      - backend/app/services/phonetics/
      - backend/app/api/nlp.py (POST /api/nlp/rhyme-suggest)
      - backend/scripts/backfill_phonetics.py
      - frontend/src/stores/rhymeStore.ts
      - frontend/src/components/sidebar/RhymeSidebar.tsx
      - frontend/src/components/rhyme/
      - frontend/src/lib/rhyme/
      - frontend/src/hooks/useRhymeQuery.ts
    depends_on: [beat_grid, canvas_rendering, snippet_crud]
    doc: docs/features/rhyme_view.md
  merge_blocks:
    description: Select two or more snippets and merge them vertically (stacked) or horizontally (joined line by line) into one snippet at the first block's position, in canvas-position order, keeping rests and stress; the server builds the merged snippet and deletes the originals in one transaction (POST /api/canvases/{id}/merge), shared by the web and Android clients.
    entry_points:
      - backend/app/api/merge.py (POST /api/canvases/{canvas_id}/merge)
      - backend/app/services/merge_service.py
      - backend/app/services/snippet_service.py (get_canvas_snippets, replace_with_merged)
      - frontend/src/stores/canvasStore.ts (mergeSelected)
      - frontend/src/api/client.ts (canvasApi.merge)
      - frontend/src/lib/merge/ (MergeError, MergeResult)
      - frontend/src/components/canvas/SnippetContextMenu.tsx
      - frontend/src/components/toolbar/Toolbar.tsx
      - frontend/src/components/pages/CanvasPage.tsx (Ctrl/Cmd+Shift+V / H)
    depends_on: [beat_grid, canvas_rendering, snippet_crud, typed_api]
    doc: docs/features/merge_blocks.md
  typed_api:
    description: Typed pydantic response models on every client-facing route (wire shape unchanged, grids emitted exactly as stored) and a committed OpenAPI snapshot regenerated by a script and guarded by a drift test.
    entry_points:
      - backend/app/schemas/ (SnippetResponse, CanvasDetailResponse, GroupResponse, TagResponse, SyllabifyResponse, BatchPositionResult, MergeResponse)
      - backend/app/api/canvases.py (response builders)
      - backend/scripts/export_openapi.py
      - docs/api/openapi.json
    depends_on: [snippet_crud]
    doc: docs/backend/canvas-crud.md
  grid_rearrange:
    description: In the inline beat-grid editor, select and drag syllable chunks along a line (free reorder over rests and other syllables), insert or remove rests, and move lines up or down; hand-arranged rows survive the debounced syllabify and the text is rebuilt from the grid on save.
    entry_points:
      - frontend/src/components/canvas/InlineGridEditor.tsx
      - frontend/src/components/canvas/GridRow.tsx
      - frontend/src/components/canvas/useChunkDrag.ts
      - frontend/src/components/canvas/editorState.ts
      - frontend/src/lib/grid/
    depends_on: [beat_grid, canvas_rendering, snippet_crud]
    doc: docs/features/grid_rearrange.md
  android_app:
    description: Native Android client (Kotlin, Jetpack Compose) for the same features as the web client, redesigned for touch; uses the FastAPI backend as its engine. Pure-Kotlin core modules hold the API DTOs, the ported grid/rhyme/editor logic plus canvas geometry, and a typed OkHttp client. The app module has login/register, the canvas list, a Compose-drawn canvas (pan, pinch zoom, long-press selection and moves, merge, delete) and the rhyme sheet with materialize (docs/android/canvas.md).
    entry_points:
      - android/app/
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/MainActivity.kt
      - android/core/model/
      - android/core/logic/
      - android/core/data/
    depends_on: [rhyme_view, merge_blocks, grid_rearrange, snippet_crud]
    doc: docs/features/android_app.md
```
