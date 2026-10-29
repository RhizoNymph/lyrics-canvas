FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.13.5 /uv /uvx /bin/

COPY . .
RUN uv sync --frozen --no-dev

# The environment is complete after the build; `uv run` must not re-sync at
# runtime, which would need network access to rebuild the project.
ENV UV_NO_SYNC=1

# NLTK data for g2p_en (out-of-vocabulary pronunciation); lands in /app/.nltk_data
RUN uv run python -c "from app.services.phonetics import ensure_resources; ensure_resources()"

EXPOSE 8000
CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
