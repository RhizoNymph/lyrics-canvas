# Speech-to-text service (faster-whisper / CTranslate2).
#
# Build:  docker build -t <registry>/lyrics-canvas-stt:<tag> stt
#
# The runtime stage is NVIDIA's CUDA 12 + cuDNN 9 runtime image, which provides
# the libcublas.so.12 / libcudnn*.so.9 that CTranslate2 loads for device=cuda.
# The same image runs on CPU with STT_DEVICE=cpu (compute type int8).

# ---------------------------------------------------------------- build stage
# Same Ubuntu release as the runtime base, so the venv's native wheels match.
FROM ubuntu:24.04 AS build

COPY --from=ghcr.io/astral-sh/uv:0.13.5 /uv /bin/uv

ENV UV_PYTHON_INSTALL_DIR=/opt/python \
    UV_PYTHON=3.12 \
    UV_LINK_MODE=copy \
    UV_COMPILE_BYTECODE=1 \
    UV_PROJECT_ENVIRONMENT=/app/.venv

WORKDIR /app

RUN uv python install 3.12

# Dependencies first: this layer is reused until the lockfile changes.
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project

COPY src ./src
RUN uv sync --frozen --no-dev --no-editable

# -------------------------------------------------------------- runtime stage
FROM nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04

# Interpreter and venv only; uv itself is not shipped, so nothing at runtime
# can try to resolve or install packages (pods must start offline).
COPY --from=build /opt/python /opt/python
COPY --from=build /app/.venv /app/.venv

RUN groupadd --gid 10001 stt \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp --shell /usr/sbin/nologin stt \
    && mkdir -p /models \
    && chown 10001:10001 /models

# HF_HOME holds the Hub cache (weights under /models/hub) plus Hub/xet state;
# mount a persistent volume at /models so weights download once.
ENV PATH=/app/.venv/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models \
    HF_HUB_DISABLE_TELEMETRY=1 \
    STT_MODEL_CACHE_DIR=/models/hub \
    STT_HOST=0.0.0.0 \
    STT_PORT=8000

USER 10001:10001
WORKDIR /app
EXPOSE 8000

CMD ["stt-service"]
