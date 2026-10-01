# Lyrics Canvas - Infrastructure Documentation

## 1. Overview

The project uses Docker Compose to orchestrate three services: PostgreSQL (with pgvector), a FastAPI backend, and a React/Vite frontend. All services are configured for local development with hot-reloading.

## 2. Services

### PostgreSQL (`postgres`)

- **Image**: `pgvector/pgvector:pg16` (PostgreSQL 16 with pgvector extension)
- **Port**: 5435 (host) -> 5432 (container)
- **Database**: `lyrics_canvas`
- **Credentials**: `lyrics` / `lyrics_dev`
- **Volumes**:
  - `pgdata` (named volume): Persistent data storage
  - `./initdb` -> `/docker-entrypoint-initdb.d`: Init scripts
- **Health Check**: `pg_isready` every 5s, 5 retries

### Init Script (`initdb/01-init.sql`)

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

Ensures pgvector extension is available for 384-dimensional embedding storage.

### Backend (`backend`)

- **Build**: `./backend` (Python 3.11-slim + uv)
- **Port**: 8000 (host) -> 8000 (container)
- **Command**: `uv run uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload`
- **Environment**:
  - `DATABASE_URL`: `postgresql+asyncpg://lyrics:lyrics_dev@postgres:5432/lyrics_canvas`
  - `SECRET_KEY`: `dev-secret-key-change-in-production`
  - `CORS_ORIGINS`: `["http://localhost:5173"]`
- **Volumes**: `./backend:/app` (live code reload)
- **Depends on**: postgres (healthy)

### Frontend (`frontend`)

- **Build**: `./frontend` (Node 20-slim)
- **Port**: 5173 (host) -> 5173 (container)
- **Command**: `npm run dev -- --host 0.0.0.0`
- **Environment**:
  - `VITE_API_URL`: `http://localhost:8000`
- **Volumes**:
  - `./frontend:/app` (live code reload)
  - `/app/node_modules` (anonymous volume, prevents host override)
- **Depends on**: backend

## 3. Network Topology

```
Browser (localhost:5173)
    |
    v
Frontend (Vite dev server, port 5173)
    |  proxies /api/* in dev
    v
Backend (FastAPI/Uvicorn, port 8000)
    |
    v
PostgreSQL + pgvector (port 5435 external / 5432 internal)
```

In Docker Compose, services communicate via internal hostnames (`postgres`, `backend`, `frontend`).

## 4. Volumes

| Volume | Type | Purpose |
|--------|------|---------|
| `pgdata` | Named | Persistent PostgreSQL data |
| `./backend:/app` | Bind mount | Live backend code reload |
| `./frontend:/app` | Bind mount | Live frontend code reload |
| `/app/node_modules` | Anonymous | Isolate container node_modules from host |

## 5. Development Workflow

### Start all services
```bash
docker-compose up
```

### Start with rebuild
```bash
docker-compose up --build
```

### Access points
- Frontend: http://localhost:5173
- Backend API: http://localhost:8000
- API docs (Swagger): http://localhost:8000/docs
- PostgreSQL: `psql -h localhost -p 5435 -U lyrics -d lyrics_canvas`

### Database migrations (production)
```bash
docker-compose exec backend alembic upgrade head
```

### Reset database
```bash
docker-compose down -v  # Removes pgdata volume
docker-compose up
```

## 6. Production Considerations

- Replace `SECRET_KEY` with a strong random value
- Use proper PostgreSQL credentials
- Set `CORS_ORIGINS` to production domain
- Remove `--reload` from uvicorn command
- Build frontend for production (`npm run build`) and serve via nginx or CDN
- Add SSL/TLS termination (reverse proxy)
- Consider connection pooling (PgBouncer) for high load
