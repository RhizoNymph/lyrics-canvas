import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.config import settings
from app.db.database import engine
from app.models import Base
from app.api.auth import router as auth_router
from app.api.canvases import router as canvases_router
from app.api.snippets import router as snippets_router
from app.api.groups import router as groups_router
from app.api.tags import router as tags_router
from app.api.nlp import router as nlp_router
from app.api.export import router as export_router
from app.services.phonetics import ResourceError, ensure_resources
from app.services.phonetics.resources import configure as configure_nltk_data

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Create tables on startup (for dev; use alembic in production)
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    # NLTK data for g2p_en; the heuristic pronunciation path covers a failure
    configure_nltk_data(Path(settings.nltk_data_dir))
    try:
        ensure_resources()
    except ResourceError as exc:
        logger.warning("phonetics.resources.unavailable dir=%s error=%s", settings.nltk_data_dir, exc)
    yield
    await engine.dispose()


app = FastAPI(title="Lyrics Canvas", version="0.1.0", lifespan=lifespan)

app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(auth_router)
app.include_router(canvases_router)
app.include_router(snippets_router)
app.include_router(groups_router)
app.include_router(tags_router)
app.include_router(nlp_router)
app.include_router(export_router)


@app.get("/api/health")
async def health():
    return {"status": "ok"}
