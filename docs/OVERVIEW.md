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
A **canvas** is a user's titled workspace (1-255 characters after trimming; `invalid_canvas_title` otherwise; a new canvas without a title is "Untitled Canvas"), created and renamed from the canvas list and from inside the canvas. A **snippet** is a rectangular card holding lyrics text, owned by its user. Its **content** (text, type, grid mode, beats, grid, phonetics, tags, recordings) is shared, and its **layout** lives on a **placement** per canvas (`snippet_placements`: position, size, stacking order, group), so one snippet can be on several canvases with an independent layout on each. Every snippet has at least one placement. A snippet can be **moved** to another canvas or **also shown** on one (`POST /api/canvases/{id}/snippets/transfer`: same position and size, stacked on top, ungrouped). Plain delete removes it from the current canvas only, deleting it for good when that was its last canvas; **delete everywhere** removes it from all of them; deleting a canvas keeps its snippets that are also on other canvases. Each snippet in a canvas response carries its `placement_id` and the `canvases` holding it. Layout saves (PUT, batch-position) name the canvas (`canvas_id`, required). Snippets have a type (verse, chorus, bridge, hook, freestyle) displayed as a color-coded header. Users create snippets by double-clicking the canvas and arrange them freely by dragging. Each snippet records `content_updated_at`, the time its text, type or grid last changed (moves, resizes and placements do not count), for "last edited" ordering. See [snippet_placements.md](./features/snippet_placements.md). On web, a canvas is renamed from the pencil on its list card or by clicking the header title, and the right-click menu moves snippets to another canvas or also shows them there (canvas picker with "New canvas…", result toast with Open), removes them from this canvas or deletes them everywhere (confirmed); a "⧉ N" badge on cards and list rows lists the other canvases holding a snippet (see [web_snippet_placements.md](./features/web_snippet_placements.md)).

### Beat Grid
Each snippet's text is **syllabified** using the CMU Pronouncing Dictionary. The result is a grid where each cell is one syllable with stress markers (0=unstressed, 1=primary, 2=secondary). Users can insert "rests" (silence beats) and choose between auto-expanding or fixed-beat grid modes. Syllable counts are displayed per line. In the inline editor the grid can be corrected by hand: syllable chunks drag along their line, rests are inserted or removed, and lines move up or down (see [Grid Rearrange](./features/grid_rearrange.md)). Line breaks are fixed from the keyboard on web and Android: Enter splits a line at the caret, Backspace at its start joins it onto the line above (see [Editor Line Split / Join](./features/editor_line_split_join.md)).

### Groups & Tags
**Groups** are named containers that visually wrap related snippets with a bounding box. Moving a group moves all its snippets. **Tags** are user-wide color labels (one list per user, shared by all canvases) attached to snippets. On web they are assigned from the right-click context menu's tag picker: a searchable checklist that links and unlinks immediately (on the whole multi-selection when the menu is opened on one of its members, with a mixed state), creates a tag from the search text, and edits (name, color) or deletes tags. Assigned tags appear as colored pill labels in the snippet header. Snippets are filtered with a **tag expression** (`love & !draft | type:chorus`; `!` > `&` > `|`, juxtaposition is AND, `type:` addresses the snippet type) typed into the filter bar under the toolbar, with autocomplete at the caret and tag pills that insert at the caret. An invalid expression keeps the last valid filter applied. The filter text is remembered per canvas per browser. Expressions can be saved as named **presets** (server-side, per user, shared with Android); renaming a tag rewrites the user's presets. The rhyme sidebar's "Only my lines matching" restricts the notes source with the same kind of expression. Android has the same tag picker (from the selection bar and the editor), filter bar, presets and rhyme filter. See [tag_expressions.md](./features/tag_expressions.md), [web_tag_expressions.md](./features/web_tag_expressions.md) and [android_tag_expressions.md](./features/android_tag_expressions.md).

### Word Bank
The **word bank** is a per-user list of words and short phrases (1-64 code points, at most 4 words, letters / apostrophes / hyphens; unique per user ignoring case) to use later. Words carry the same user-wide tags as snippets and are filtered with the same tag expressions and presets; a word has no snippet type, so every `type:` term is false for it (shared vectors v2). The bank is loaded whole and filtered on the client. Words are added in batches (typed, from rhyme results, from a snippet text selection, or from a related-word search), renamed, deleted and tagged in bulk. Two rhyme views work on the filtered bank: **rhymes with** ranks bank words against one word or typed text with the rhyme-suggest scorer, and **families** groups the bank by average-linkage clustering of pairwise rhyme similarity (medoid label, cohesion, `unmatched`). **Related words** finds dictionary words close in meaning to a word or theme with the sentence-embedding model (index built once per process off the event loop and cached on disk; 503 `related_words_warming` with Retry-After while it builds). Selected words become an editable draft, one per line, created as a new snippet through the existing snippet endpoint. See [word_bank.md](./features/word_bank.md). On web it is a right sidebar (toolbar `Words`, Ctrl/Cmd+Shift+W or Alt+Shift+W; exclusive with the rhyme sidebar) with an add box, the filterable multi-select list, Rhymes and Related tabs and a "Make snippet" draft, and words can be added from rhyme candidates ("+ bank") and from an editor text selection ("Add to word bank", Ctrl/Cmd+Shift+B); see [web_word_bank.md](./features/web_word_bank.md). On Android a "Words" action on the canvas top bar opens a full-screen Word bank (add box, filtered multi-select list, rhyme families and rhymes-with, related-word search, and "Make snippet" drafts created on that canvas), with "+ bank" on rhyme candidates and "Add to word bank" on editor text selections ([android_word_bank.md](./features/android_word_bank.md)).

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
- **Tag Assignment**: Right-click a snippet to open the context menu: tag picker (search, toggle, create, edit, delete; applies to the multi-selection), merge, delete
- **Tag filter**: The bar under the toolbar takes a tag expression with autocomplete, tag pills that insert at the caret, presets (apply, save, rename, overwrite, delete) and clear; it filters both the canvas and list mode; see [web_tag_expressions.md](./features/web_tag_expressions.md)
- **Merge**: With 2+ snippets selected, merge them vertically (Ctrl/Cmd+Shift+V) or horizontally (Ctrl/Cmd+Shift+H) from the context menu, toolbar, or keyboard; the server merges and replaces the originals in one transaction; see [merge_blocks.md](./features/merge_blocks.md)
- **List mode**: The toolbar `List` toggle (or Ctrl/Cmd+Shift+L) swaps the canvas for a scrollable list of the snippets, last edited first (`content_updated_at`). It follows the tag expression filter and shows each snippet's type header, tags and beat grid. Clicking a row opens the inline editor in place. Ctrl/Cmd-click and Shift-click multi-select into the same selection the canvas uses, so merge, delete and undo work unchanged. The choice is remembered per viewer. The Android canvas screen has the same toggle in its top bar (tap opens the editor, long-press selects); see [list_mode.md](./features/list_mode.md)
- **Voice capture (web)**: The toolbar `Record` button, the `Rec` button in the open editor, or Alt+Shift+R opens a full-screen recorder. A tap or Space/Enter starts the next line, a double tap stops, and auto-stop after N seconds of silence is optional. The take is uploaded and transcribed server-side. It becomes a new snippet at the viewport centre, or is appended to the snippet open in the editor (whose pending edits are saved first). The audio is kept and can be played or deleted from the editor's Recordings section. Needs HTTPS. See [web_voice_capture.md](./features/web_voice_capture.md) and the cross-cutting [voice_capture.md](./features/voice_capture.md)
- **Word bank (web)**: The toolbar `Words` toggle (Ctrl/Cmd+Shift+W, or Alt+Shift+W where the browser reserves the former) opens the word bank sidebar in place of the rhyme sidebar; see [web_word_bank.md](./features/web_word_bank.md)
- **Undo/Redo**: 50-item snapshot history (Ctrl+Z / Ctrl+Shift+Z)
- **Export**: Markdown, plain text, or PDF

### Voice Capture
A record button (web toolbar and inline editor; Android canvas and editor top bars) opens a recording
modal: a single tap anywhere means "next line", a double tap stops, and an optional silence timeout stops
too. The audio goes to `POST /api/canvases/{id}/voice-takes`; the backend has a self-hosted
faster-whisper STT service (cluster-internal) transcribe it, breaks the words into lines at the taps (or
auto-splits by pause, syllable balance and end rhyme when there were none) and creates a new freestyle
snippet or appends the lines to the snippet open in the editor, keeping its hand-arranged grid. The audio
is stored with the snippet (`snippet_recordings`) for playback and deletion from the editor, follows the
snippet into merges and is deleted with it. `STT_URL` empty turns the feature off. Each take is
transcribed with the user's word bank as vocabulary hints (Whisper `initial_prompt`), and each
recording keeps its STT words and line spans so the user can check its transcript against the audio
and export the checked recordings as a zip dataset (original audio + JSONL) for STT fine-tuning. On web the recorder
has a "Use word bank as hints" switch (per viewer, default on, sent as `meta.vocabulary_hints`); each
editor recording can be opened in a Check transcript modal (player, tappable Whisper words, correction
saved as the recording's `verified_text`), and the canvas list page's "Voice dataset" dialog shows the
stats and downloads the dataset zip ([web_voice_capture.md](./features/web_voice_capture.md)). On Android the
recorder has a "Use word bank as hints" switch (sent as `meta.vocabulary_hints`), each editor recording
opens a full-screen Check transcript screen (player, tappable timed Whisper words, a correction saved on
the recording only), and the canvas list overflow shows the voice dataset stats (export stays on web). See
[voice_capture.md](./features/voice_capture.md) and [voice_dataset.md](./features/voice_dataset.md).

### Health Banner
The web app watches its own backend: it polls `GET /api/health` (every 15 s while visible, with backoff
while down) and listens to the outcome of every API call. After two consecutive failures a banner says the
server is down (nginx answered 502/503/504) or unreachable (offline, network error), and that changes may
not be saving; "Back online" shows briefly on recovery. It also polls `GET /_status/alerts` (nginx in front
of Alertmanager) and lists firing cluster alerts, critical first; a deployment without that endpoint stays
quiet. Cards are dismissible per incident. See [health_banner.md](./features/health_banner.md).

## Module Documentation

| Document | Scope |
|----------|-------|
| [FRONTEND.md](./FRONTEND.md) | Frontend index with links to granular module docs |
| [BACKEND.md](./BACKEND.md) | Backend index with links to granular module docs |
| [INFRASTRUCTURE.md](./INFRASTRUCTURE.md) | Docker Compose, PostgreSQL/pgvector setup, networking, dev workflow |
| [Android Release](./features/android_release.md) | Tagged GitHub Release with a verified, optionally keystore-signed APK |
| [Android Voice Capture](./features/android_voice_capture.md) | Record a take on Android (tap = next line, double-tap = stop, silence auto-stop, word-bank hints switch), transcribed by the server into a new snippet or appended lines; recordings played back, deleted and checked (Check transcript screen) in the editor; dataset stats in the canvas list |
| [Android Health Banner](./features/android_health_banner.md) | App-wide banner for backend outages (`/api/health` + passive call signals) and firing cluster alerts (`/_status/alerts`) |
| [Android Tag Expressions](./features/android_tag_expressions.md) | Android tag picker (link / unlink / create / edit user-wide tags), expression filter bar with autocomplete and presets, rhyme "Only my lines matching" |
| [Android Word Bank](./features/android_word_bank.md) | Android word bank screen (add, filter, select, rename, delete, tag), rhyme families / rhymes-with, related words, snippet drafts; "+ bank" on rhyme candidates and editor selections |
| [Helm Deployment](./features/helm_deployment.md) | Kubernetes chart (`deploy/helm/lyrics-canvas`), production frontend image, migrations, secrets, ingress |
| [PostgreSQL Backup](./features/postgres_backup.md) | Nightly `pg_dump` CronJob (`backup.*`) to a hostPath or PVC, homelab dumps on node1, laptop rsync pull timer (`deploy/homelab/backup`), restore procedure |
| [Alerting](./features/alerting.md) | Optional PrometheusRule in the chart: pod readiness, restart loops, unavailable workloads, backup freshness |
| [STT Service](./features/stt_service.md) | `stt/`: cluster-internal faster-whisper service (`POST /v1/transcribe` with word timestamps), its CUDA image and `stt.*` chart component on node1's GPU |
| [Homelab Deploy](./features/homelab_deploy.md) | `deploy/homelab/deploy.sh`: build SHA-tagged images on node0, push to the microk8s registry, helm upgrade + test |
| [Homelab TLS](./features/homelab_tls.md) | HTTPS for the web app on the home cluster (microphone needs a secure context): local CA + server cert script (`deploy/homelab/tls`), host-less Ingress on the microk8s addon, ingress default certificate for IP URLs, 26 MB upload limits, CA install on Linux/Android |
| [Cluster Alerts Endpoint](./features/cluster_alerts_endpoint.md) | `GET /_status/alerts` on the frontend nginx: this namespace's firing Alertmanager alerts, served without the backend |
| [Snippet Placements](./features/snippet_placements.md) | Snippet content vs per-canvas layout: `snippet_placements` and `snippets.owner_id` (migration 008, both directions), the at-least-one-placement invariant, PUT / batch-position with `canvas_id`, remove from canvas, delete everywhere, transfer (move / show), merge, voice, groups, fork, readers, canvas title validation, typed errors |
| [Voice Capture](./features/voice_capture.md) | Voice takes end to end: clients, `POST /api/canvases/{id}/voice-takes`, line assembly (taps / auto split), STT client, `snippet_recordings` storage |
| [Voice Dataset](./features/voice_dataset.md) | Word-bank vocabulary hints (Whisper `initial_prompt`), stored STT words / line spans (migration 007), transcript verification, recording stats and the zip dataset export format, fine-tuning outline |
| [Tag Expressions](./features/tag_expressions.md) | User-wide tags, the tag expression language (spec + shared vectors), saved presets, rhyme `tag_filter`, migration 005 |
| [Web Tag Expressions](./features/web_tag_expressions.md) | Web client of user-wide tags, tag expressions and presets: typed TS expression language (vectors), tag picker, filter bar, presets, rhyme notes filter |
| [Web Snippet Placements](./features/web_snippet_placements.md) | Web canvas rename (list card pencil, header title) and snippet placements: typed placement API with `canvas_id` on every layout save, Move to / Also show on canvas picker, Remove from this canvas vs Delete everywhere, "⧉ N" badge and canvases popover, editor "Also on", undo/redo rule for non-undoable cross-canvas operations |
| [Web Word Bank](./features/web_word_bank.md) | Web word bank sidebar: add box, local tag-expression filter (typeless evaluator, vectors v2), multi-select list, rename/delete/tagging, rhyme families and rhymes-with, related words with warming retry, draft to snippet, "+ bank" and editor "Add to word bank" |
| [Web Voice Capture](./features/web_voice_capture.md) | Web recorder modal (taps = line breaks, double tap = stop, silence auto-stop, word bank hints switch), upload to voice-takes, editor recordings list with Check transcript, Voice dataset stats and zip export |
| [Word Bank](./features/word_bank.md) | Per-user tagged words and phrases: migration 006, text rules, `/api/word-bank` (batch add, rename, delete, tag, rhymes, families, related words), `TagResponse.word_count`, related-word index and its cache/warmup settings |
| [DESIGN.md](./DESIGN.md) | Original feature requirements and design intent |

### Frontend Modules

| Module | Scope |
|--------|-------|
| [Canvas Rendering](./frontend/canvas-rendering.md) | Pixi.js viewport, snippet cards, grid background, group containers |
| [Beat Grid](./frontend/beat-grid.md) | Syllable grid display and editing |
| [Sidebars](./frontend/sidebars.md) | Rhyme sidebar, word bank panel and similarity analysis panel |
| [Toolbar & Tags](./frontend/toolbar.md) | Zoom, undo/redo, tool toggles, tag expression filter bar |
| [Modals](./frontend/modals.md) | Search and export dialogs |
| [Pages & Routing](./frontend/pages-routing.md) | SPA routes, auth pages, canvas list, canvas editor |
| [State Management](./frontend/state-management.md) | Zustand stores (auth, canvas, UI, tags, word bank) |
| [API Client & Types](./frontend/api-client.md) | Axios HTTP client, TypeScript interfaces |

### Backend Modules

| Module | Scope |
|--------|-------|
| [Authentication](./backend/auth.md) | JWT, bcrypt, user model, login/register |
| [Database & Models](./backend/database.md) | PostgreSQL, ORM models, Alembic migrations |
| [Canvas & Snippet CRUD](./backend/canvas-crud.md) | Canvas lifecycle, snippets, sharing, forking |
| [Groups & Tags](./backend/groups-tags.md) | Group containers, user-wide tag labels, associations, tag presets |
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
| State management | Zustand | Stores: auth, canvas, UI, rhyme, status |
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
| Deployment | Helm chart (`deploy/helm/lyrics-canvas`) | Kubernetes: backend + nginx frontend + pgvector StatefulSet or external DB + optional STT service |
| Speech-to-text | faster-whisper (CTranslate2, large-v3-turbo) in `stt/` | Word-timestamped transcripts for voice capture; CUDA on the homelab GPU, CPU int8 for dev |
| Alerting | PrometheusRule (prometheus-operator) + kube-state-metrics | Availability and backup alerts for a release; routing in Alertmanager |
| Homelab deploy | `deploy/homelab/deploy.sh` (bash over ssh) | One-command build, push and helm deploy of HEAD to the home microk8s cluster |
| Homelab TLS | `deploy/homelab/tls/issue-cert.sh` (bash + openssl), ingress-nginx (microk8s addon) | Local CA, server cert as a `kubernetes.io/tls` Secret, HTTPS at the node IPs |

## Data Model (Simplified)

```
User 1──N Canvas 1──N SnippetPlacement N──1 Snippet N──M Tag N──1 User (user-wide)
               │            │                │
               1──N Group ──┘ (group_id)     ├── owner_id N──1 User
                                             ├── SnippetEmbedding (1:1, Vector(384))
                                             └── SnippetRecording (N:1, audio bytea; ON DELETE CASCADE, moved on merge)

User 1──N TagPreset (named, normalized tag expressions)
User 1──N BankWord N──M Tag (bank_word_tags; same user-wide tags)
```

## API Surface

| Area | Endpoints | Auth |
|------|-----------|------|
| Authentication | 4 (register, login, google, me) | Public / Bearer |
| Canvases | 8 (CRUD + share + fork) | Bearer (shared is public) |
| Snippets | 7 (create, update, delete everywhere, remove from canvas, batch position, transfer, merge) | Bearer |
| Tags | 6 (list, create, update, delete, link/unlink) | Bearer |
| Tag presets | 4 (list, create, update, delete) | Bearer |
| Groups | 3 (CRUD) | Bearer |
| NLP | 6 (syllabify, search, similar, tsne, rhymes, rhyme-suggest) | Mixed |
| Export | 1 (text/md/pdf) | Bearer |
| Voice | 8 (voice-takes, list recordings, recording audio, delete recording, put/delete verification, stats, export) | Bearer |
| Word bank | 8 (list, add, rename, delete, tags, rhymes, families, related) | Bearer |
| Health | 1 | Public |
| **Total** | **56 endpoints** | |

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
  snippet_placements:
    description: Snippet content separated from per-canvas layout so one snippet can be on several canvases. Migration 008 adds snippet_placements (snippet_id, canvas_id, position_x/y, width/height, z_index, group_id ON DELETE SET NULL, created_at; unique (snippet_id, canvas_id)) and snippets.owner_id, copying one placement per snippet and dropping the layout columns; the downgrade restores them from the oldest placement and splits every other placement into a duplicated snippet (content and tag links copied, recordings not). Invariant - every snippet has at least one placement - enforced (row locks + delete of snippets left with none, same transaction) on canvas delete, remove from canvas, move and merge. SnippetResponse gains placement_id and canvases [{id, title}] (lower(title), id; only the shared canvas in the shared view); layout fields come from the placement on the canvas in context. PUT /api/snippets/{id}?canvas_id= and PATCH batch-position {canvas_id, updates} require canvas_id; DELETE /api/canvases/{cid}/snippets/{sid} -> {snippet_deleted}; DELETE /api/snippets/{id} deletes everywhere; POST /api/canvases/{cid}/snippets/transfer {snippet_ids 1..500, target_canvas_id, mode move|show} -> {target_canvas_id, placed, already_on_target, removed_from_source}, all or nothing, same layout, z = target max + 1 + source z rank, ungrouped. Merge removes originals from this canvas only (shared ones keep their recordings). Voice append needs a placement on the take's canvas. Groups hold placements of their canvas. Fork copies content into new snippets of the forker. Search, similarity (optional canvas_id), t-SNE (read only), export and the shared view read the canvas's placements; rhyme notes read owner_id once per snippet. Canvas titles stripped, 1-255 code points (invalid_canvas_title). Typed errors invalid_canvas_title, placement_not_found, canvas_not_found, same_canvas, group_not_found.
    entry_points:
      - backend/alembic/versions/008_snippet_placements.py
      - backend/app/models/placement.py
      - backend/app/services/placements/ (errors, view, queries, removal, transfer)
      - backend/app/services/canvas_title.py
      - backend/app/services/snippet_service.py
      - backend/app/services/canvas_service.py
      - backend/app/api/placements.py (DELETE /api/canvases/{cid}/snippets/{sid}, POST /api/canvases/{cid}/snippets/transfer)
      - backend/app/api/snippets.py (PUT ?canvas_id=, batch-position canvas_id)
      - backend/app/api/canvases.py (title validation, _snippet_response)
      - backend/app/api/placement_errors.py
      - backend/app/schemas/placement.py
    depends_on: [snippet_crud, merge_blocks, voice_capture, tag_expressions, typed_api]
    doc: docs/features/snippet_placements.md
  web_word_bank:
    description: Web client of the word bank. Typed wire shapes and API (api/wordBank.ts) rejecting only with WordBankApiError (invalid_word with batch index, word_exists, word_not_found, too_many_words, related_words_warming with Retry-After, related_words_unavailable, plus tag codes). Client mirror of the word text rules (NFC, strip, collapse whitespace, 1-64 code points, at most 4 words, letters/apostrophes/hyphens/spaces, a letter) and the add box splitter (commas, newlines). wordBankStore loads the whole bank, filters it locally with the shared evaluator on a typeless subject (spec vectors v2), persists the filter at localStorage lyrics-canvas.wordBankFilter, keeps the selection within the shown words, adds batches with tags, renames, deletes, links/unlinks via POST /api/word-bank/tags, keeps word pills and Tag.word_count in step with tagStore, and creates a draft snippet at the viewport centre. wordBankQueryStore runs rhyme families (min score 0.8), rhymes with (one selected word or typed text, min score 0.5) and the related search (at most 10 tries after Retry-After). Right sidebar via the Words toggle / Ctrl/Cmd+Shift+W / Alt+Shift+W, exclusive with the rhyme sidebar; "+ bank" on rhyme candidates; editor context menu "Add to word bank" and Ctrl/Cmd+Shift+B. The canvas filter bar and the snippet tag picker are generalized into TagFilterBar and TagChecklist, shared with the panel.
    entry_points:
      - frontend/src/components/wordBank/
      - frontend/src/stores/wordBankStore.ts
      - frontend/src/stores/wordBankQueryStore.ts
      - frontend/src/lib/wordBank/
      - frontend/src/api/wordBank.ts
      - frontend/src/types/wordBank.ts
      - frontend/src/components/tags/TagFilterBar.tsx
      - frontend/src/components/tags/TagChecklist.tsx
      - frontend/src/components/rhyme/CandidateRow.tsx (+ bank)
      - frontend/src/components/canvas/InlineGridEditor.tsx (Add to word bank)
      - frontend/src/stores/uiStore.ts (rightPanel, toggleWordBank)
      - spec/tag_expression_vectors.json
    depends_on: [word_bank, web_tag_expressions, rhyme_view, web_voice_capture, typed_api]
    doc: docs/features/web_word_bank.md
  web_tag_expressions:
    description: Web client of user-wide tags and tag filter expressions. A typed TypeScript implementation of the expression language (lexer, parser, discriminated-union AST, normalized format, evaluate, rename; code point positions) passing spec/tag_expression_vectors.json read from the repo root, plus caret context, autocomplete (tag names, type values, and/or/not where they fit) and tag insertion at the caret. Tags load from GET /api/tags (tagStore), are created/renamed/recolored/deleted via /api/tags, and kept in step with the tags embedded in loaded snippets (live and undo snapshots); a rename refetches presets and renames the tag in the open filters. The context-menu tag picker searches, links/unlinks immediately (whole multi-selection, mixed state), creates and edits/deletes tags. The canvas filter bar (canvas and list view) validates live with a marker at the error position, keeps the last valid filter applied while the text is invalid, inserts tags from pills at the caret, applies/saves/renames/overwrites/deletes presets (/api/tag-presets) and persists the text per canvas in localStorage. The rhyme sidebar's "Only my lines matching" sends tag_filter (normalized) and blocks the request locally when invalid. Typed TagApiError for every new server code.
    entry_points:
      - frontend/src/lib/tagExpr/
      - frontend/src/lib/tags/
      - frontend/src/lib/tagFilter/
      - frontend/src/stores/tagStore.ts
      - frontend/src/stores/tagFilterStore.ts
      - frontend/src/components/tags/ (TagExpressionInput, PresetPicker, TagPicker)
      - frontend/src/components/toolbar/TagExpressionBar.tsx
      - frontend/src/components/rhyme/NotesTagFilter.tsx
      - frontend/src/components/canvas/SnippetContextMenu.tsx
      - frontend/src/lib/canvas/visibility.ts
      - frontend/src/api/client.ts (tagApi, tagPresetApi)
      - spec/tag_expression_vectors.json
    depends_on: [tag_expressions, list_mode, rhyme_view, canvas_rendering, typed_api]
    doc: docs/features/web_tag_expressions.md
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
    description: Select two or more snippets and merge them vertically (stacked) or horizontally (joined line by line) into one snippet at the first block's position, in canvas-position order, keeping rests and stress; the server builds the merged snippet and its placement and takes the originals off the canvas in one transaction (an original also on other canvases stays there with its recordings; one only here is deleted and its recordings move to the merged snippet) (POST /api/canvases/{id}/merge), shared by the web and Android clients.
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
      - backend/app/schemas/ (SnippetResponse, CanvasDetailResponse, GroupResponse, TagResponse, SnippetTagResponse, TagPresetResponse, SyllabifyResponse, BatchPositionResult, MergeResponse)
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
  editor_line_split_join:
    description: In the web and Android grid editors, Enter splits the active row at the caret (text after the caret becomes a new row below), Backspace at caret 0 joins the row onto the previous one (one space at the seam), and on web Delete at the end pulls the next row up; split/joined rows lose arranged and are re-syllabified like typed text. Android detects Backspace at the start through a zero-width sentinel in the field.
    entry_points:
      - frontend/src/components/canvas/editorState.ts (splitRow, joinWithPrevious, joinWithNext, CaretRequest)
      - frontend/src/components/canvas/editorKeys.ts
      - frontend/src/components/canvas/InlineGridEditor.tsx (applyRowKey)
      - android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/editor/EditorRowsState.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/editor/LineFieldEdit.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/editor/ui/ActiveLineField.kt
    depends_on: [grid_rearrange, android_app]
    doc: docs/features/editor_line_split_join.md
  snippet_content_timestamp:
    description: snippets.content_updated_at records when a snippet's content (text, type, grid) last changed, separately from updated_at which also moves on drags and resizes; returned on every SnippetResponse so list views can sort by last edited.
    entry_points:
      - backend/app/models/snippet.py (Snippet.content_updated_at)
      - backend/app/services/snippet_service.py (CONTENT_FIELDS, content_edits_differ, update_snippet)
      - backend/app/api/snippets.py (PUT /api/snippets/{id} content_edits)
      - backend/app/api/canvases.py (_snippet_response)
      - backend/alembic/versions/003_snippet_content_updated_at.py
    depends_on: [snippet_crud, typed_api]
    doc: docs/features/snippet_content_timestamp.md
  list_mode:
    description: Toggle on the canvas page/screen that swaps the canvas for a vertically scrollable list of the canvas's snippets, ordered by last content edit (content_updated_at desc, created_at desc, id). Web - follows the canvas tag filter and shows the type header, tags and a DOM beat grid per row. Clicking a row opens the inline editor in place. Multi-select shares the canvas selection, so merge, delete, context menu and undo are reused. Row order freezes while an editor is open. The mode is persisted per viewer in localStorage. Android - top-bar List / Canvas toggle, LazyColumn of the canvas cards, tap opens the editor, long-press selects, mode kept in the canvas ViewModel's SavedStateHandle; rows follow the filter bar's tag expression (android_tag_expressions).
    entry_points:
      - frontend/src/components/list/ (ListView, ListRow, GridPreview, useFrozenOrder)
      - frontend/src/lib/listMode/ (sortForList, applyFrozenOrder, listClick, view-mode persistence)
      - frontend/src/lib/canvas/visibility.ts (isSnippetVisible, shared with CanvasViewport)
      - frontend/src/stores/uiStore.ts (viewMode, setViewMode, toggleViewMode, closeEditor)
      - frontend/src/stores/canvasStore.ts (setSelection)
      - frontend/src/components/canvas/InlineGridEditor.tsx (EditorPlacement 'inline')
      - frontend/src/components/toolbar/Toolbar.tsx (List / Canvas toggle)
      - frontend/src/components/pages/CanvasPage.tsx (view switch, Ctrl/Cmd+Shift+L)
      - android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/list/ListOrder.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/canvas/list/
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/canvas/CanvasViewModel.kt (setMode, onListLongPress)
    depends_on: [snippet_content_timestamp, merge_blocks, grid_rearrange, canvas_rendering, snippet_crud, android_app]
    doc: docs/features/list_mode.md
  android_app:
    description: Native Android client (Kotlin, Jetpack Compose) for the same features as the web client, redesigned for touch; uses the FastAPI backend as its engine. Pure-Kotlin core modules hold the API DTOs, the ported grid/rhyme/editor logic plus canvas geometry, and a typed OkHttp client. The app module has login/register, the canvas list, a Compose-drawn canvas (pan, pinch zoom, long-press selection and moves, merge, delete, list mode) and the rhyme sheet with materialize (docs/android/canvas.md).
    entry_points:
      - android/app/
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/MainActivity.kt
      - android/core/model/
      - android/core/logic/
      - android/core/data/
    depends_on: [rhyme_view, merge_blocks, grid_rearrange, snippet_crud]
    doc: docs/features/android_app.md
  helm_deployment:
    description: Helm chart that deploys the web stack to Kubernetes - backend Deployment (Alembic migrations in a retrying init container, /api/health probes, HF model cache volume), nginx frontend Deployment (SPA + /api reverse proxy), bundled PostgreSQL + pgvector StatefulSet or externalDatabase, optional Ingress with per-host TLS or a host-less catch-all rule (IP access), derived CORS origins, routes only frontend/backend, JWT key and DB password generated once and kept across upgrades, JSON-schema validated values, committed per-cluster values files without credentials (deploy/helm/environments, *.secret.yaml gitignored), render-and-assert test suite; plus the production frontend image (frontend/Dockerfile.prod).
    entry_points:
      - deploy/helm/lyrics-canvas/Chart.yaml
      - deploy/helm/lyrics-canvas/values.yaml
      - deploy/helm/lyrics-canvas/values.schema.json
      - deploy/helm/lyrics-canvas/templates/_helpers.tpl
      - deploy/helm/lyrics-canvas/templates/backend/deployment.yaml
      - deploy/helm/lyrics-canvas/templates/postgres/statefulset.yaml
      - deploy/helm/lyrics-canvas/templates/ingress.yaml
      - deploy/helm/environments/homelab.yaml
      - deploy/helm/tests/test_chart.py
      - frontend/Dockerfile.prod
      - frontend/nginx/default.conf.template
    depends_on: []
    doc: docs/features/helm_deployment.md
  postgres_backup:
    description: Optional nightly CronJob <fullname>-backup (backup.*, default disabled) that pg_dumps the bundled PostgreSQL in custom format to a pre-existing hostPath directory on a pinned node or a PVC, publishing lyrics_canvas-<UTC ts>.dump atomically (hidden temp name, pg_restore --list check, rename) and pruning to the newest backup.retain; password via secretKeyRef only; refuses to render for an external database. Homelab writes to node1:/home/nymph/backups/lyrics-canvas as 1000:1000, and a systemd user timer on the laptop rsyncs those dumps to ~/Backups/lyrics-canvas without --delete.
    entry_points:
      - deploy/helm/lyrics-canvas/templates/backup/cronjob.yaml
      - deploy/helm/lyrics-canvas/templates/backup/_script.tpl
      - deploy/helm/lyrics-canvas/templates/backup/pvc.yaml
      - deploy/helm/environments/homelab.yaml (backup)
      - deploy/homelab/backup/install.sh
      - deploy/homelab/backup/pull.sh
    depends_on: [helm_deployment]
    doc: docs/features/postgres_backup.md
  health_banner:
    description: Web banner for backend health and cluster alerts. A pure state machine (unknown/ok/suspect/failing/recovered) fed by GET /api/health probes (15 s, 3 s to confirm, 5-60 s backoff while down, paused while hidden) and by a pass-through axios interceptor on api/client.ts (502/503/504 down, no response unreachable); two consecutive failures before showing, 5 s "Back online". Polls GET /_status/alerts (Alertmanager v2 list via nginx, sibling feat/status-alerts-endpoint), validated at runtime into typed alerts; 404 or HTML fallback = absent and silent, 5xx = stale. Ordered cards (backend first, then critical, warning, other), role alert/status, dismissible per incident.
    entry_points:
      - frontend/src/hooks/useStatusMonitoring.ts
      - frontend/src/components/status/StatusBanner.tsx
      - frontend/src/stores/statusStore.ts
      - frontend/src/lib/health/
      - frontend/src/lib/alerts/
      - frontend/src/lib/statusBanner/
      - frontend/src/api/status.ts (probeHealth, fetchClusterAlerts)
      - frontend/src/api/client.ts (installHealthInterceptors)
    depends_on: [helm_deployment]
    doc: docs/features/health_banner.md
  alerting:
    description: Optional PrometheusRule rendered by the Helm chart (monitoring.prometheusRule.enabled, on in homelab) for kube-prometheus-stack. Alerts scoped to the release namespace and the release's name prefix, using kube-state-metrics series - pod not Ready / stuck in init, container and init container restart loops and CrashLoopBackOff-style waiting reasons, Deployment/StatefulSet unavailable replicas, KSM series absent (monitoring blind), and for CronJobs named *-backup a stale last success and failed Jobs. Tunable for-durations, thresholds and severities, schema-validated; kubectl commands in descriptions. Routing and receivers live in the Alertmanager config, not here. Tested with render assertions and promtool unit tests.
    entry_points:
      - deploy/helm/lyrics-canvas/templates/monitoring/prometheusrule.yaml
      - deploy/helm/lyrics-canvas/values.yaml (monitoring.prometheusRule)
      - deploy/helm/environments/homelab.yaml
      - deploy/helm/tests/alerts/rules_test.yaml
    depends_on: [helm_deployment]
    doc: docs/features/alerting.md
  homelab_deploy:
    description: One-command deploy of the committed tree to the home microk8s cluster - refuses a dirty tree (unless --allow-dirty, which still ships HEAD only), tags images with the 12-char commit SHA, ships git archive HEAD over ssh to a per-run dir under ~ on the build host (removed by an EXIT trap), builds backend, frontend and stt with docker build --network host and pushes to localhost:32000 unless the tag is already in the registry (--rebuild / --skip-build), then helm upgrade --install with -f homelab.yaml and the backend, frontend and stt --set image tags (--atomic for an existing release, never --reuse-values), helm test (without --logs; the test pod's logs are fetched with kubectl only when it fails), and prints the URL (http://10.1.1.69:32443) and revision; --dry-run prints every remote command without ssh; key=value logs and one exit code per failure class.
    entry_points:
      - deploy/homelab/deploy.sh
      - deploy/homelab/tests/test_deploy_script.py
    depends_on: [helm_deployment]
    doc: docs/features/homelab_deploy.md
  homelab_tls:
    description: HTTPS for the web app on the home microk8s cluster so browsers allow microphone access. deploy/homelab/tls/issue-cert.sh creates a local CA once in ~/.config/lyrics-canvas/tls (dir 700, ca.key 600, never overwritten without --force, which keeps backups), issues/renews an EC P-256 server cert (SANs IP:10.1.1.69, IP:10.1.1.68, IP:100.64.0.4, DNS:lyrics.s8.gay; 397 days; serverAuth; reissued when missing, expiring within 30 days, SANs changed, key mismatch or not signed by the CA) and server-side-applies the lyrics-canvas/lyrics-canvas-tls kubernetes.io/tls Secret over ssh node0 stdin; --dry-run, --renew, --no-apply, key=value logs, typed exit codes. homelab.yaml enables the chart Ingress on class public with a host-less rule (Host = IP), that Secret, proxy-body-size 26m, 180 s timeouts and force-ssl-redirect; IP URLs carry no SNI, so the ingress-nginx default certificate (microk8s enable ingress:default-ssl-certificate=lyrics-canvas/lyrics-canvas-tls, a documented one-time user command) is what serves it. Frontend nginx /api/ gets client_max_body_size 26m. NodePort 32443 (plain HTTP, Android) unchanged; no STT routing. Docs cover CA install on Linux Chrome/Firefox and Android, and a future Android HTTPS move.
    entry_points:
      - deploy/homelab/tls/issue-cert.sh
      - deploy/homelab/tls/tests/test_issue_cert.py
      - deploy/helm/environments/homelab.yaml (ingress)
      - deploy/helm/lyrics-canvas/templates/ingress.yaml
      - frontend/nginx/default.conf.template (location /api/)
      - frontend/nginx/tests/test_api_body_limit.py
    depends_on: [helm_deployment, homelab_deploy]
    doc: docs/features/homelab_tls.md
  cluster_alerts_endpoint:
    description: Read-only GET /_status/alerts on the frontend origin, answered by the production nginx (not the backend) by proxying to the cluster's Alertmanager with one fixed, server-built URL (active, unsilenced, uninhibited alerts with namespace=<release namespace>), so web and Android clients can show an outage banner while the backend is down. 200 [] when unconfigured, 503 {"error":"alertmanager_unavailable"} on any upstream failure, 405 for non GET/HEAD. Variable proxy_pass with a resolver derived from /etc/resolv.conf at container start, so an unresolvable Alertmanager never stops nginx from starting. Chart value frontend.clusterAlerts.alertmanagerUrl.
    entry_points:
      - frontend/nginx/default.conf.template (location = /_status/alerts)
      - frontend/nginx/entrypoint/16-cluster-alerts.envsh
      - frontend/Dockerfile.prod
      - deploy/helm/lyrics-canvas/values.yaml (frontend.clusterAlerts)
      - deploy/helm/lyrics-canvas/templates/frontend/deployment.yaml
      - frontend/nginx/tests/test_alerts_endpoint.py
    depends_on: [helm_deployment]
    doc: docs/features/cluster_alerts_endpoint.md
  android_release:
    description: Tag-triggered GitHub Actions workflow that tests, builds, verifies and attaches an Android APK to a GitHub Release; Gradle takes versionName/versionCode from properties and signs release builds with a keystore from the environment (all-or-nothing, typed as a sealed ReleaseSigning) or falls back to the debug key; a script asserts the APK's version, signer and baked-in backend URL.
    entry_points:
      - .github/workflows/android-release.yml
      - android/app/build.gradle.kts (ReleaseSigning, releaseSigningFromEnv)
      - android/scripts/verify_release_apk.sh
    depends_on: [android_app]
    doc: docs/features/android_release.md
  voice_capture:
    description: Record lyrics by voice on web and Android (tap = next line, double tap = stop, optional silence auto-stop). POST /api/canvases/{id}/voice-takes (multipart audio + meta JSON with breaks_ms, duration_ms and a target discriminated on kind new|append) validates MIME and size (VOICE_MAX_UPLOAD_BYTES), checks ownership, sends the audio to the cluster-internal STT service (POST {STT_URL}/v1/transcribe, faster-whisper word timestamps; typed errors stt_disabled/stt_unavailable/stt_failed/empty_transcript), assembles lines in a pure module (a tap in a pause splits there, a tap in a word's first half splits before it and in its second half after it; otherwise a DP auto split scoring pause length over the median gap, syllable balance against a common target and engine end-rhyme bonus, or a single line), then in one transaction creates a freestyle snippet through the same derivation as POST /snippets or appends after the last line keeping existing grid lines verbatim (blank trailing line reused, phonetics and size recomputed, content_updated_at bumped) and stores the recording in snippet_recordings (bytea, transcript, split_mode taps|auto|single). Recordings are listed per snippet newest first, streamed with the stored Content-Type, deleted by id; they cascade with their snippet and move onto the merged snippet on merge. STT_URL empty disables the feature (503).
    entry_points:
      - backend/app/api/voice.py (POST /api/canvases/{canvas_id}/voice-takes, GET /api/snippets/{snippet_id}/recordings, GET /api/recordings/{recording_id}/audio, DELETE /api/recordings/{recording_id})
      - backend/app/services/voice/ (line_assembly, engine_oracle, append, audio, stt_client, errors)
      - backend/app/services/recording_service.py
      - backend/app/services/snippet_content.py (derive_new_content, content_for, compute_size)
      - backend/app/models/recording.py (SnippetRecording, SplitMode)
      - backend/alembic/versions/004_snippet_recordings.py
      - backend/app/schemas/voice.py
      - frontend/src/components/voice/ and frontend/src/lib/voice/ (web_voice_capture)
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/voice/ (android_voice_capture)
    depends_on: [snippet_crud, merge_blocks, snippet_content_timestamp, rhyme_view, typed_api, grid_rearrange, stt_service]
    doc: docs/features/voice_capture.md
  stt_service:
    description: Cluster-internal speech-to-text service (stt/, FastAPI + faster-whisper) for voice capture. POST /v1/transcribe (multipart audio, optional language and initial_prompt) returns language, duration_ms and ordered word timestamps in ms (word_timestamps + VAD); typed errors undecodable_audio 400, audio_too_large 413, invalid_request 422, model_not_ready 503, transcription_failed 500. /healthz is up at once, /readyz flips when the background model load (cache-first, downloads once, retried with backoff) finishes. Own CUDA 12/cuDNN 9 image; chart component stt.* (default off) with a ClusterIP Service never routed by ingress/nginx, GPU resources, runtime class, model cache emptyDir/hostPath/PVC, Recreate strategy, STT_URL on the backend; homelab runs it on node1's RTX 3090 with the weights in a node1 hostPath.
    entry_points:
      - stt/src/stt_service/app.py (create_app, POST /v1/transcribe, /healthz, /readyz)
      - stt/src/stt_service/main.py (stt-service)
      - stt/src/stt_service/whisper.py
      - stt/Dockerfile
      - deploy/helm/lyrics-canvas/templates/stt/
      - deploy/helm/environments/homelab.yaml (stt)
    depends_on: [helm_deployment, homelab_deploy]
    doc: docs/features/stt_service.md
  android_voice_capture:
    description: Android voice capture - top-bar Record on the canvas screen (canvas + list) and the editor opens a full-screen modal (elapsed, level meter, Line N, Stop, Cancel; tap = next line, double-tap within 300 ms = stop; silence auto-stop off/2/3/5/8/10 s per device in DataStore with an adaptive-noise-floor energy VAD). RECORD_AUDIO runtime permission with rationale and app-settings path; MediaRecorder OGG/Opus on API 29+ else AAC/MPEG-4 into the cache. Multipart POST /api/canvases/{id}/voice-takes with breaks_ms since recorder start; new snippet at the viewport centre (selected) or, from the editor, edits saved first then appended and the rows reloaded. Typed errors from the server codes, Retry keeps the file. Editor Recordings section lists, plays (audio fetched with the bearer token into the cache) and deletes takes, with a Checked / Not checked badge. "Use word bank as hints" switch (DataStore voice_vocab_hints, default on) sent as meta.vocabulary_hints. Check transcript screen (CheckTranscriptRoute) - player with scrubber, read-only Whisper reference with tappable words that seek and a highlighted current word when has_timings (pure wordIndexAt / transcriptReference), correction prefilled with verified_text or the lines; Save as checked PUT /api/recordings/{id}/verification, Mark unchecked DELETE, Cancel with dirty confirm, typed inline errors (invalid_verification, recording_not_found); changes reach the editor list through RecordingUpdates. Canvas list overflow "Voice dataset" dialog shows GET /api/recordings/stats; no export on Android. Pure state machine, gesture reducer, VAD, transcript reference and verification text rules in :core:logic.
    entry_points:
      - android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/voice/
      - android/core/model/src/main/kotlin/dev/nymph/lyrics/model/voice/
      - android/core/data/src/main/kotlin/dev/nymph/lyrics/data/repository/VoiceRepository.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/voice/
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/canvas/CanvasViewModel.kt (voiceTarget, onVoiceTakeSaved)
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/editor/GridEditorRoute.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/voice/check/CheckTranscriptScreen.kt (CheckTranscriptRoute)
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/canvaslist/CanvasListScreen.kt (Voice dataset overflow item)
    depends_on: [android_app, voice_capture, voice_dataset]
    doc: docs/features/android_voice_capture.md
  android_health_banner:
    description: App-wide status banner in the Android client while it is in the foreground - polls GET /api/health (15 s, quick 3 s confirm, 5-60 s backoff while down) and counts 502/503/504 and IO errors of every regular ApiClient call through an observer; a pure debounced state machine (2 failures with one from a probe, offline at once, brief back online) shared in behaviour with the web client; polls GET /_status/alerts (frontend nginx origin, configured by -PstatusAlertsUrl when the API base is the backend directly) and shows firing alerts critical first; per-incident dismiss, expandable descriptions, live-region announcements.
    entry_points:
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/status/
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/nav/AppRoot.kt
      - android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/status/
      - android/core/data/src/main/kotlin/dev/nymph/lyrics/data/status/
      - android/core/data/src/main/kotlin/dev/nymph/lyrics/data/ApiCallObserver.kt
      - android/core/model/src/main/kotlin/dev/nymph/lyrics/model/status/
    depends_on: [android_app, android_release]
    doc: docs/features/android_health_banner.md
  web_voice_capture:
    description: Web client for voice takes (contract in docs/features/voice_capture.md). The toolbar Record button (canvas and list view), the Rec button in the inline editor, or Alt+Shift+R opens a full-screen recorder modal. A pointerdown or Space/Enter is a line break, timed at the first tap and committed after 300 ms. A second tap within 300 ms stops and drops that break. An optional energy-VAD silence auto-stop (minimum-statistics noise floor, arms after speech; off/2/3/5/8/10 s per viewer in localStorage) can also end the take. Built from pure tested reducers (recorder machine, gestures, VAD, playback) driven by a DOM-free VoiceSession, plus a thin MediaRecorder/AnalyserNode adapter (webm/opus, else mp4). Uploads the take as multipart to POST /api/canvases/{id}/voice-takes. With the editor open it saves the editor first, appends, and reloads the editor rows from the response; otherwise it creates a snippet at the viewport centre (list view - the last canvas viewport or 0,0), selects it and scrolls to it. Contract error codes map to typed errors with Retry (same blob), Save audio and Discard. The editor's Recordings section lists, plays (bearer-token blob URLs) and deletes takes, badges each Checked / Not checked and opens a Check transcript modal (audio player with scrubber, read-only Whisper reference whose words seek and highlight while playing when has_timings, correction field prefilled with verified_text or the lines; Save as checked = PUT /api/recordings/{id}/verification, Mark unchecked = DELETE, dirty Cancel confirms; Ctrl/Cmd+Enter, Ctrl+Space, Alt+Left/Right). The recorder's "Use word bank as hints" switch (localStorage lyrics-canvas.voice.vocabHints, default on) is sent as meta.vocabulary_hints. The canvas list page header's "Voice dataset" dialog shows GET /api/recordings/stats and downloads GET /api/recordings/export (blob, server file name, "Include unchecked takes"). Disabled with "Recording needs HTTPS" outside secure contexts.
    entry_points:
      - frontend/src/lib/voice/
      - frontend/src/api/voice.ts (createVoiceApi; client.ts voiceApi)
      - frontend/src/stores/voiceStore.ts
      - frontend/src/components/voice/
      - frontend/src/components/canvas/editorBridge.ts
      - frontend/src/components/canvas/InlineGridEditor.tsx (Rec button, RecordingsSection, bridge)
      - frontend/src/components/toolbar/Toolbar.tsx (Record button)
      - frontend/src/components/pages/CanvasPage.tsx (Alt+Shift+R, VoiceCaptureModal)
      - frontend/src/components/list/ListView.tsx (reveal new snippet)
      - frontend/src/components/voice/check/ (CheckTranscriptModal)
      - frontend/src/components/voice/dataset/ (VoiceDatasetDialog)
      - frontend/src/components/pages/CanvasListPage.tsx (Voice dataset button)
    depends_on: [voice_capture, voice_dataset, list_mode, grid_rearrange, snippet_crud, health_banner]
    doc: docs/features/web_voice_capture.md
  tag_expressions:
    description: User-wide tags (one list per user shared by all canvases, names unique per user ignoring case; migration 005 backfills user_id from the canvas owner and merges same-named tags into the smallest id, repointing snippet links) and a boolean tag expression language shared by backend, web and Android - bare or quoted tag names, type:<verse|chorus|bridge|hook|freestyle>, ! > & > | with juxtaposition as AND, and/or/not keywords, code point error positions with typed codes (too_long, unexpected_character, unterminated_string, invalid_escape, empty_name, unknown_type, unexpected_token, unexpected_end, unclosed_paren, empty_group, too_deep), a normalizer, case-insensitive evaluation and rename, conformance vectors in spec/tag_expression_vectors.json read in place by every implementation. Server-stored named presets (normalized, non-empty) are rewritten in the same transaction when a tag is renamed. API - GET/POST /api/tags, PUT/DELETE /api/tags/{id}, snippet link/unlink (both owned), /api/tag-presets CRUD; TagResponse {id, name, color, snippet_count, word_count, created_at} (word_count added by word_bank), snippets embed SnippetTagResponse {id, name, color}; errors {"error", "message"} plus expression_error {code, position, message}. POST /api/canvases/{id}/tags and the canvas-detail tags list are removed. RhymeSuggestRequest.tag_filter restricts the notes source to matching snippets (dictionary never filtered).
    entry_points:
      - spec/tag_expression_vectors.json
      - backend/app/services/tag_expr/ (parse, normalize, format_expression, matches, rename_tag)
      - backend/app/api/tags.py (GET/POST /api/tags, PUT/DELETE /api/tags/{tag_id}, POST/DELETE /api/snippets/{snippet_id}/tags)
      - backend/app/api/tag_presets.py (GET/POST /api/tag-presets, PUT/DELETE /api/tag-presets/{preset_id})
      - backend/app/api/tag_errors.py
      - backend/app/services/tag_service.py
      - backend/app/services/tag_preset_service.py
      - backend/app/services/tag_values.py
      - backend/app/services/tag_errors.py
      - backend/app/models/tag.py
      - backend/app/models/tag_preset.py
      - backend/alembic/versions/005_user_wide_tags.py
      - backend/app/services/rhyme_suggest_service.py (tag_filter, matches_filter)
    depends_on: [typed_api, rhyme_view, merge_blocks]
    doc: docs/features/tag_expressions.md
  android_tag_expressions:
    description: Android client of tag expressions. :core:logic engine (lexer, parser, sealed AST, normalizing format, evaluate, rename, completion and tag insertion at the caret; code point positions; Python isspace whitespace) passing every vector of spec/tag_expression_vectors.json read in place by the test task. User-wide tags (GET/POST /api/tags, PUT/DELETE /api/tags/{id}, link POST /api/snippets/{id}/tags?tag_id=, unlink), presets CRUD (/api/tag-presets), tag_filter on the rhyme request, canvas detail without tags, typed errors for the ten tag codes (including preset_expression_too_long) plus detail.expression_error. Tag picker bottom sheet from the selection bar (canvas and list) and the editor - searchable tri-state checklist applied to every picked snippet at once, Create '<query>', rename / recolour / delete per tag. Filter bar above the canvas and list - expression field with live validation (marker + message), suggestion chips at the caret, tag pills inserting at the caret, clear, presets menu (apply, save as with inline 409, rename / overwrite / delete); an invalid text keeps the last valid filter; non-matching snippets are not drawn, hit, listed or selected; the applied text is remembered per canvas in DataStore. Rhyme sheet "Only my lines matching" (same field, presets, Use canvas filter) sent normalized as tag_filter, blocking the query locally when invalid and notes is on.
    entry_points:
      - android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/tags/
      - android/core/model/src/main/kotlin/dev/nymph/lyrics/model/Tags.kt
      - android/core/data/src/main/kotlin/dev/nymph/lyrics/data/repository/TagRepository.kt
      - android/core/data/src/main/kotlin/dev/nymph/lyrics/data/repository/TagPresetRepository.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/tags/
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/canvas/CanvasViewModel.kt (TagCanvasPort)
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/rhyme/RhymeViewModel.kt (tag filter)
      - spec/tag_expression_vectors.json
    depends_on: [tag_expressions, android_app, list_mode, rhyme_view]
    doc: docs/features/android_tag_expressions.md
  word_bank:
    description: Per-user bank of words and short phrases tagged with the user-wide tags. Migration 006 adds bank_words (user_id FK cascade + index, text varchar(64), created_at, unique (user_id, lower(text))) and bank_word_tags (both FKs cascade, composite PK); downgrade drops both. Text rules - NFC, strip, collapse whitespace; 1-64 code points, at most 4 words, only letters (L*), ' and U+2019 apostrophes, hyphen-minus and spaces, at least one letter; case kept, uniqueness ignores case (invalid_word 422, batch adds index). Tag expressions evaluate words as typeless subjects (type: terms false; shared vectors v2). API - GET /api/word-bank (newest first, no server filtering), POST /api/word-bank (batch 1..200 words + 0..20 tag_ids -> created / existing in input order, batch duplicates collapse, tags linked to all), PUT /api/word-bank/{id} (rename, case-only allowed, word_exists 409), POST /api/word-bank/delete (1..500, foreign ids ignored), POST /api/word-bank/tags (link/unlink one tag on 1..500 words, all-or-nothing word_not_found), POST /api/word-bank/rhymes (word_id or text anchor, tag_filter, rhyme-suggest directed score at min(k, anchor syllables), score/depth/text order), POST /api/word-bank/families (average-linkage clustering of max(score(a,b), score(b,a)) >= min_score, lowest-text tie break, medoid label/rhyme_key, cohesion; > 1000 words too_many_words), GET /api/word-bank/related (all-MiniLM-L6-v2 cosine over the common dictionary words, bank_word_id when already banked; index built once per process via asyncio.to_thread, disk cache keyed by model + vocabulary sha256, 503 related_words_warming with Retry-After 10 / related_words_unavailable). TagResponse gains word_count; deleting a tag removes word links by cascade. Settings RELATED_WORDS_WARMUP / RELATED_WORDS_CACHE_DIR; helm backend.relatedWords.{warmup (true), cacheDir}.
    entry_points:
      - backend/app/api/word_bank.py (GET/POST /api/word-bank, PUT /api/word-bank/{word_id}, POST /api/word-bank/delete, /tags, /rhymes, /families, GET /api/word-bank/related)
      - backend/app/api/word_bank_errors.py
      - backend/app/schemas/word_bank.py
      - backend/app/services/word_bank/ (text, errors, service, phonetic, rhymes, families, related, related_cache)
      - backend/app/models/word_bank.py
      - backend/alembic/versions/006_word_bank.py
      - backend/app/services/tag_expr/evaluate.py (typeless SnippetFacts)
      - backend/app/services/tag_service.py (TagSummary.word_count)
      - spec/tag_expression_vectors.json (version 2)
      - deploy/helm/lyrics-canvas/values.yaml (backend.relatedWords)
    depends_on: [tag_expressions, rhyme_view, typed_api, helm_deployment]
    doc: docs/features/word_bank.md
  voice_dataset:
    description: Word-bank vocabulary hints and the recording dataset. Each voice take (meta.vocabulary_hints, default true, and VOICE_VOCAB_HINTS) sends a pure-built Whisper initial_prompt "Vocabulary: w1, ..., wn." of the user's bank words with a letter, ranked rarest first (wordfreq Zipf, unknown 0), then newest, then lower(text), kept while within VOICE_VOCAB_HINT_MAX_CHARS (600) code points and emitted most important last; none fits or empty bank -> no form field. Migration 007 adds nullable words / line_spans (jsonb), language, initial_prompt, verified_text, verified_at to snippet_recordings (check constraint keeps the verified pair together); line assembly exposes the [start, end) word span of every line in all split modes. RecordingResponse gains lines, has_timings, words, line_spans, language, initial_prompt, verified_text, verified_at. PUT /api/recordings/{id}/verification normalizes (CRLF/CR -> LF, rstrip lines, trim blank edges, collapse blank runs; 1..10000 code points else 422 invalid_verification) and sets verified_at = now(); DELETE clears both, idempotent. GET /api/recordings/stats (total, verified, durations, with_timings across canvases). GET /api/recordings/export?include_unverified=false streams a zip (audio/<id>.<ext> original bytes, metadata.jsonl oldest first, dataset.json format_version 1) built off the event loop into a SpooledTemporaryFile; more audio than VOICE_EXPORT_MAX_BYTES (1 GiB) -> 413 export_too_large. Helm backend.voice.{vocabHints, vocabHintMaxChars, exportMaxBytes}.
    entry_points:
      - backend/app/api/voice.py (PUT/DELETE /api/recordings/{recording_id}/verification, GET /api/recordings/stats, GET /api/recordings/export, _vocabulary_prompt)
      - backend/app/services/voice/hints.py
      - backend/app/services/voice/timings.py
      - backend/app/services/voice/verification.py
      - backend/app/services/voice/dataset_export.py
      - backend/app/services/voice/line_assembly.py (AssembledLines.words / spans)
      - backend/app/services/recording_service.py (bank_hint_words, set_verification, clear_verification, recording_stats, export_recordings)
      - backend/alembic/versions/007_recording_dataset.py
      - deploy/helm/lyrics-canvas/values.yaml (backend.voice)
    depends_on: [voice_capture, word_bank, stt_service, helm_deployment, typed_api]
    doc: docs/features/voice_dataset.md
  android_word_bank:
    description: Android client of the word bank. :core:logic word text rules (NFC, strip, whitespace collapse, 1-64 code points, <= 4 words, letters / apostrophes / hyphens / spaces, >= 1 letter) and the add splitter (commas / newlines, case-insensitive de-dup, 200 per batch); typeless tag evaluation (null type, vectors v2). DTOs and HttpWordBankRepository for every /api/word-bank route, UserTag.word_count, typed WordBankError for invalid_word (index), word_exists, word_not_found, too_many_words, related_words_warming (Retry-After), related_words_unavailable plus tag codes. Full-screen Word bank from the canvas top bar - filter bar and presets shared with the canvas (generalized TagControlsViewModel / TagTarget; own DataStore key word_bank_filter), add box with tag chips, "N words (M shown)", checkbox / long-press selection, inline rename, delete (confirm > 5), tag picker on the selection, Families (min score 0.8, select all per family, unmatched last) and Rhymes with (one selected word or typed text, min score 0.5), Related search (warming retry <= 10, unavailable message, "+" / "in bank"), Make snippet draft sheet created at the canvas viewport centre. "+ bank" on rhyme candidates (disabled with tooltip when invalid) and "Add to word bank" in the editor selection toolbar.
    entry_points:
      - android/core/logic/src/main/kotlin/dev/nymph/lyrics/logic/wordbank/
      - android/core/model/src/main/kotlin/dev/nymph/lyrics/model/WordBank.kt
      - android/core/data/src/main/kotlin/dev/nymph/lyrics/data/repository/WordBankRepository.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/wordbank/
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/tags/TagControlsViewModel.kt
      - android/app/src/main/kotlin/dev/nymph/lyrics/app/nav/AppNavHost.kt (WordBankDestination)
    depends_on: [word_bank, android_tag_expressions, android_app, rhyme_view]
    doc: docs/features/android_word_bank.md
  web_snippet_placements:
    description: Web client of canvas rename and snippet placements (one snippet on several canvases, layout per canvas). Snippet gains placement_id and canvases; typed placementApi (createPlacementApi over the app axios instance, PlacementError only) for list / create / rename canvases, PUT /api/snippets/{id}?canvas_id=, batch-position with canvas_id, DELETE /api/canvases/{cid}/snippets/{sid}, DELETE /api/snippets/{id} and POST /api/canvases/{cid}/snippets/transfer; typed errors invalid_canvas_title, placement_not_found, canvas_not_found, same_canvas. Rename inline on the canvas list (pencil) and in the header (click-to-edit), local trim + 1..255 code point check, server error inline. Context menu (multi-selection aware) Move to canvas… / Also show on canvas… with a searchable picker (newest updated first, New canvas… creates then acts) and a result toast with Open; plain delete removes from this canvas (labelled Remove from this canvas when shared), Delete everywhere confirms naming the other canvases. "⧉ N" badge on Pixi cards and list rows opening a popover of canvases that navigate; editor "Also on" links. Undo/redo - move, remove and delete everywhere drop every snapshot holding an affected snippet; also-show and rename patch the snapshots.
    entry_points:
      - frontend/src/api/placements.ts
      - frontend/src/lib/placements/
      - frontend/src/stores/canvasStore.ts (removeFromCanvas, deleteEverywhere, transferSnippets, renameCanvas, updateSnippet, commitPositions)
      - frontend/src/stores/placementNoticeStore.ts
      - frontend/src/stores/uiStore.ts (canvasesPopover, leaveCanvas)
      - frontend/src/components/placements/ (TitleEditor, CanvasPicker, PlacementToast, CanvasesPopover, CanvasBadge)
      - frontend/src/components/canvas/SnippetContextMenu.tsx
      - frontend/src/components/toolbar/CanvasTitle.tsx
      - frontend/src/components/pages/CanvasListPage.tsx
    depends_on: [snippet_placements, web_tag_expressions, list_mode, merge_blocks, typed_api]
    doc: docs/features/web_snippet_placements.md
```
