# Lyrics Canvas

An infinite canvas for writing lyrics. Snippets of verse live as cards you can drag, group, tag and
merge, and every line is broken into a **beat grid** of syllables with stress markers so you can see
and tune the rhythm. A phonetic engine suggests rhymes and near-rhymes that fit the slot you are
filling, and semantic tools find related snippets or lay the whole canvas out by meaning.

Web client (React + Pixi.js), native Android client (Kotlin + Compose), FastAPI backend, PostgreSQL
with pgvector.

## Features

- **Canvas**: pan, zoom, multi-select, drag; snippets typed as verse / chorus / bridge / hook /
  freestyle; groups that move together; canvas-scoped colour tags that double as visibility filters.
- **Beat grid**: CMU-dictionary syllabification with stress, rests, auto or fixed beats per line, and
  a hand-editable grid (drag syllable chunks, insert rests, reorder lines).
- **Rhyme sidebar**: pick a line, a word range or type a phrase; get ranked rhyme and near-rhyme
  candidates from the dictionary and your own lines, and drop them onto the canvas.
- **Merge**: join selected snippets vertically or horizontally in one server transaction.
- **List mode**: the same canvas as a scrollable list ordered by last edit, with the inline editor.
- **Analysis**: TF-IDF similarity between snippets, t-SNE layout from sentence embeddings, full-text
  search.
- **Export**: Markdown, plain text or PDF. Canvases can be shared read-only and forked.
- **Auth**: email/password with JWT, optional Google sign-in.

## Repository layout

| Path | What it is |
|------|------------|
| `frontend/` | React 18 + TypeScript + Vite SPA, Pixi.js canvas, Zustand stores |
| `backend/` | FastAPI app, SQLAlchemy 2 (async), Alembic migrations, NLP services, pytest suite |
| `android/` | Kotlin / Jetpack Compose client with pure-Kotlin core modules |
| `initdb/` | PostgreSQL init script (`CREATE EXTENSION vector`) used by Docker Compose |
| `deploy/helm/` | Helm chart for Kubernetes and its test suite |
| `docs/` | Architecture and feature documentation (start at `docs/OVERVIEW.md`) |
| `docker-compose.yaml` | Three-service development environment with hot reload |

## Quick start (development)

Requires Docker and Docker Compose.

```bash
docker compose up --build
```

| Service | URL |
|---------|-----|
| Frontend | http://localhost:5173 |
| API | http://localhost:8000 |
| Swagger UI | http://localhost:8000/docs |
| PostgreSQL | `psql -h localhost -p 5435 -U lyrics -d lyrics_canvas` (password `lyrics_dev`) |

The backend creates the schema on startup in development. For a migration-managed database run
`docker compose exec backend uv run alembic upgrade head`.

### Running the pieces directly

Backend (Python 3.11, [uv](https://docs.astral.sh/uv/)):

```bash
cd backend
uv sync
uv run uvicorn app.main:app --reload      # needs a PostgreSQL with pgvector, see DATABASE_URL below
uv run pytest                              # -m 'not timing' to skip performance budgets
```

Frontend (Node 20):

```bash
cd frontend
npm install
npm run dev
npm test
```

Android: open `android/` in Android Studio, or `./gradlew :app:installDebug`. Dev builds talk to the
emulator host (`10.0.2.2:8000`); pass `-PapiBaseUrl=http://<lan-ip>:8000` for a device. See
[docs/features/android_app.md](docs/features/android_app.md).

### Backend configuration

Settings are read from the environment or `backend/.env`:

| Variable | Default | Purpose |
|----------|---------|---------|
| `DATABASE_URL` | `postgresql+asyncpg://lyrics:lyrics_dev@localhost:5435/lyrics_canvas` | asyncpg connection URL |
| `SECRET_KEY` | dev value | JWT signing key; set a strong random value outside development |
| `ACCESS_TOKEN_EXPIRE_MINUTES` | `10080` | Token lifetime (7 days) |
| `CORS_ORIGINS` | `["http://localhost:5173"]` | JSON list of allowed browser origins |
| `GOOGLE_CLIENT_ID` | empty | Enables Google sign-in verification |
| `NLTK_DATA_DIR` | `backend/.nltk_data` | Where g2p_en's NLTK data is kept (baked into the image) |

The frontend reads `VITE_API_URL` (empty means same origin, `/api`) and `VITE_GOOGLE_CLIENT_ID` at
build time.

## Deploying

A Helm chart in `deploy/helm/lyrics-canvas` runs the backend, an nginx-served frontend and either a
bundled PostgreSQL + pgvector StatefulSet or an external database, with Alembic migrations applied
automatically and secrets generated once and kept across upgrades. Build the two images first:

```bash
docker build -t <registry>/lyrics-canvas-backend:0.1.0 backend
docker build -f frontend/Dockerfile.prod -t <registry>/lyrics-canvas-frontend:0.1.0 frontend
helm install lyrics deploy/helm/lyrics-canvas --set ingress.enabled=true --set 'ingress.hosts[0]=lyrics.example.com'
```

Details, every value and the test suite are in
[deploy/helm/lyrics-canvas/README.md](deploy/helm/lyrics-canvas/README.md) and
[docs/features/helm_deployment.md](docs/features/helm_deployment.md).

## Documentation

- [docs/OVERVIEW.md](docs/OVERVIEW.md): architecture, data model, API surface and the features index
- [docs/FRONTEND.md](docs/FRONTEND.md) and [docs/BACKEND.md](docs/BACKEND.md): module-level docs
- [docs/INFRASTRUCTURE.md](docs/INFRASTRUCTURE.md): Compose services, networking, Kubernetes summary
- [docs/api/openapi.json](docs/api/openapi.json): committed OpenAPI snapshot, regenerated by
  `uv run python scripts/export_openapi.py` and guarded by a drift test
- [docs/features/](docs/features/): one document per feature (rhyme view, merge, grid rearrange,
  list mode, Android app, Helm deployment)
