"""HTTP layer: POST /v1/transcribe, GET /healthz, GET /readyz."""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from typing import Annotated, BinaryIO

from fastapi import FastAPI, File, Form, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from .config import Settings
from .domain import TranscribeOptions
from .errors import AudioTooLarge, InvalidRequest, SttError, TranscriptionFailed, UndecodableAudio
from .logs import log_event
from .mapping import to_transcript
from .readiness import LoadFailed, Loading, ModelHolder, Ready
from .schemas import ErrorBody, StatusBody, TranscriptionResponse
from .transcriber import ModelLoader, Transcriber

logger = logging.getLogger("stt.http")

# Allowance for multipart framing and the small text fields on top of the audio
# itself when rejecting by Content-Length before the body is parsed.
FORM_OVERHEAD_BYTES = 1024 * 1024

_HTTP_CODES = {400: "invalid_request", 404: "not_found", 405: "method_not_allowed"}


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content=ErrorBody(error=code, message=message).model_dump())


def _size(upload: UploadFile) -> int:
    if upload.size is not None:
        return upload.size
    f = upload.file
    f.seek(0, 2)
    size = f.tell()
    f.seek(0)
    return size


def _clean(value: str | None) -> str | None:
    if value is None:
        return None
    return value.strip() or None


def _options(
    settings: Settings, transcriber: Transcriber, language: str | None, prompt: str | None
) -> TranscribeOptions:
    lang = _clean(language)
    lang = lang.lower() if lang is not None else settings.default_language
    if lang is not None and lang not in transcriber.languages:
        raise InvalidRequest(f"unsupported language code {lang!r}")
    return TranscribeOptions(language=lang, initial_prompt=_clean(prompt))


def create_app(settings: Settings, loader: ModelLoader) -> FastAPI:
    holder = ModelHolder(loader, settings.retry)
    # One recognition at a time: a single model instance on a single GPU.
    lock = asyncio.Lock()

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        task = asyncio.create_task(holder.run(), name="model-loader")
        log_event(
            logger,
            logging.INFO,
            "service.start",
            model=settings.model,
            device=settings.device.value,
            compute_type=settings.compute_type,
            offline=settings.offline,
            max_upload_bytes=settings.max_upload_bytes,
        )
        yield
        if not task.done():
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                log_event(logger, logging.INFO, "model.load_cancelled")

    app = FastAPI(title="lyrics-canvas-stt", version="0.1.0", lifespan=lifespan)
    app.state.holder = holder

    @app.exception_handler(SttError)
    async def stt_error(_request: Request, exc: SttError) -> JSONResponse:
        return _error(exc.status, exc.code, exc.message)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, exc: RequestValidationError) -> JSONResponse:
        problems = "; ".join(
            f"{'.'.join(str(p) for p in e.get('loc', ()))}: {e.get('msg', 'invalid')}" for e in exc.errors()
        )
        return _error(422, InvalidRequest.code, problems or "invalid request")

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_request: Request, exc: StarletteHTTPException) -> JSONResponse:
        response = _error(exc.status_code, _HTTP_CODES.get(exc.status_code, "http_error"), str(exc.detail))
        if exc.headers:
            response.headers.update(exc.headers)
        return response

    @app.middleware("http")
    async def reject_oversized(
        request: Request, call_next: Callable[[Request], Awaitable[Response]]
    ) -> Response:
        declared = request.headers.get("content-length", "")
        limit = settings.max_upload_bytes
        if (
            request.url.path == "/v1/transcribe"
            and declared.isdigit()
            and int(declared) > limit + FORM_OVERHEAD_BYTES
        ):
            err = AudioTooLarge(f"request body is {declared} bytes; audio limit is {limit}")
            log_event(logger, logging.WARNING, "transcribe.rejected", error=err.code, content_length=declared)
            return _error(err.status, err.code, err.message)
        return await call_next(request)

    @app.get("/healthz", response_model=StatusBody, response_model_exclude_none=True)
    async def healthz() -> StatusBody:
        return StatusBody(status="ok")

    @app.get("/readyz", response_model=StatusBody, responses={503: {"model": StatusBody}})
    async def readyz() -> Response:
        match holder.state:
            case Ready():
                body, status = StatusBody(status="ok"), 200
            case Loading(attempt=attempt):
                body, status = StatusBody(status="loading", attempt=attempt), 503
            case LoadFailed(attempt=attempt, error=error):
                body, status = StatusBody(status="failed", attempt=attempt, message=error), 503
        return JSONResponse(status_code=status, content=body.model_dump(exclude_none=True))

    @app.post(
        "/v1/transcribe",
        response_model=TranscriptionResponse,
        responses={s: {"model": ErrorBody} for s in (400, 413, 422, 500, 503)},
    )
    async def transcribe(
        audio: Annotated[UploadFile, File()],
        language: Annotated[str | None, Form()] = None,
        initial_prompt: Annotated[str | None, Form()] = None,
    ) -> TranscriptionResponse:
        size = _size(audio)
        if size > settings.max_upload_bytes:
            raise AudioTooLarge(f"audio is {size} bytes; limit is {settings.max_upload_bytes}")
        if size == 0:
            raise UndecodableAudio("audio is empty")
        transcriber = holder.require()
        options = _options(settings, transcriber, language, initial_prompt)
        started = time.monotonic()
        try:
            raw = await _run(lock, transcriber, audio.file, options)
        except SttError as exc:
            log_event(
                logger, logging.WARNING, "transcribe.failed", error=exc.code, message=exc.message, bytes=size
            )
            raise
        transcript = to_transcript(raw)
        log_event(
            logger,
            logging.INFO,
            "transcribe.done",
            bytes=size,
            language=transcript.language,
            requested_language=options.language,
            duration_ms=transcript.duration_ms,
            words=len(transcript.words),
            elapsed_ms=round((time.monotonic() - started) * 1000),
        )
        return TranscriptionResponse.from_transcript(transcript)

    return app


async def _run(lock: asyncio.Lock, transcriber: Transcriber, audio: BinaryIO, options: TranscribeOptions):
    audio.seek(0)
    async with lock:
        try:
            return await asyncio.to_thread(transcriber.transcribe, audio, options)
        except SttError:
            raise
        except Exception as exc:  # recogniser internals (CUDA, ctranslate2) are untyped
            raise TranscriptionFailed(f"{type(exc).__name__}: {exc}") from exc
