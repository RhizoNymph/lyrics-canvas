"""Shared plumbing for the tag DB tests (a real, throwaway PostgreSQL database).

Skipped unless TAG_TEST_DATABASE_URL points at a disposable database whose name contains "test":
the schema is migrated down to base and up again and rows are written. Never point it at a
database holding real data. Example (a throwaway container, not the Compose database):

    docker run -d --rm --name tagexpr-test-pg -e POSTGRES_PASSWORD=t -e POSTGRES_DB=lyrics_tag_test \\
        -p 55439:5432 pgvector/pgvector:pg16
    TAG_TEST_DATABASE_URL=postgresql+asyncpg://postgres:t@localhost:55439/lyrics_tag_test \\
        uv run pytest tests/tags
    docker stop tagexpr-test-pg
"""

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import pytest
from sqlalchemy.engine import make_url

DB_URL = os.environ.get("TAG_TEST_DATABASE_URL", "")
BACKEND = Path(__file__).resolve().parents[2]

requires_db = pytest.mark.skipif(
    not DB_URL or "test" not in (make_url(DB_URL).database or ""),
    reason="set TAG_TEST_DATABASE_URL to a throwaway database whose name contains 'test'",
)


def alembic(*args: str) -> None:
    env = {**os.environ, "DATABASE_URL": DB_URL}
    result = subprocess.run([sys.executable, "-m", "alembic", *args], cwd=BACKEND, env=env, capture_output=True, text=True)
    assert result.returncode == 0, f"alembic {' '.join(args)} failed:\n{result.stderr}"
