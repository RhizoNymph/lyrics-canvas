FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends gcc libpq-dev && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

COPY . .
RUN uv sync --frozen --no-dev 2>/dev/null || uv sync --no-dev

# NLTK data for g2p_en (out-of-vocabulary pronunciation); lands in /app/.nltk_data
RUN uv run python -c "from app.services.phonetics import ensure_resources; ensure_resources()"

EXPOSE 8000
CMD ["uv", "run", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
