"""Voice takes and snippet recordings (docs/features/voice_capture.md, contract section 2).

Errors are ``HTTPException`` with ``detail = {"error": <code>, "message": <text>}`` so the clients
can switch on the code. Audio bytes and transcript text are never logged.
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile
from pydantic import ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.canvases import _snippet_response
from app.api.deps import get_current_user
from app.config import settings
from app.db.database import get_db
from app.models.user import User
from app.schemas.voice import (
    AppendTarget,
    NewSnippetTarget,
    RecordingResponse,
    VoiceErrorResponse,
    VoiceTakeMeta,
    VoiceTakeResponse,
)
from app.services import recording_service
from app.services.voice.audio import accepted_mime, read_bounded
from app.services.voice.engine_oracle import EngineOracle
from app.services.voice.errors import (
    CanvasNotFoundError,
    EmptyTranscriptError,
    InvalidMetaError,
    SnippetNotFoundError,
    VoiceTakeError,
)
from app.services.voice.line_assembly import LineAssemblyError, assemble_lines
from app.services.voice.stt_client import SttClient, Transcriber

logger = logging.getLogger(__name__)

router = APIRouter(tags=["voice"])

AUDIO_CACHE_CONTROL = "private, max-age=0"


def get_transcriber() -> Transcriber:
    """The STT client from settings; tests override this dependency with a fake."""
    return SttClient(settings.stt_url, settings.stt_timeout_seconds)


def _http_error(exc: VoiceTakeError) -> HTTPException:
    return HTTPException(status_code=exc.status, detail={"error": exc.code.value, "message": exc.message})


def _errors(*statuses: int) -> dict[int | str, dict[str, object]]:
    return {status: {"model": VoiceErrorResponse} for status in statuses}


def _owner(user: User) -> uuid.UUID:
    return user.id if isinstance(user.id, uuid.UUID) else uuid.UUID(str(user.id))


def parse_meta(raw: str | None) -> VoiceTakeMeta:
    if raw is None or not raw.strip():
        raise InvalidMetaError("the meta field is required")
    try:
        return VoiceTakeMeta.model_validate_json(raw)
    except ValidationError as exc:
        # loc + message only: never echo the submitted values
        problems = "; ".join(
            f"{'.'.join(str(part) for part in error['loc']) or 'meta'}: {error['msg']}"
            for error in exc.errors(include_input=False, include_url=False)[:5]
        )
        raise InvalidMetaError(f"invalid meta: {problems}") from exc


def _recording_response(row) -> RecordingResponse:
    return RecordingResponse(
        id=str(row.id),
        snippet_id=str(row.snippet_id),
        mime_type=row.mime_type,
        duration_ms=row.duration_ms,
        byte_size=row.byte_size,
        transcript=row.transcript,
        split_mode=row.split_mode,
        created_at=str(row.created_at),
    )


async def _voice_take(
    canvas_id: uuid.UUID, audio: UploadFile, meta: str | None, owner: uuid.UUID, db: AsyncSession, transcriber: Transcriber
) -> VoiceTakeResponse:
    parsed = parse_meta(meta)
    mime_type = accepted_mime(audio.content_type)
    data = await read_bounded(audio, settings.voice_max_upload_bytes)

    # Ownership before the (expensive) transcription.
    if not await recording_service.canvas_owned(db, canvas_id, owner):
        raise CanvasNotFoundError("canvas not found")
    match parsed.target:
        case AppendTarget(snippet_id=snippet_id):
            if not await recording_service.snippet_in_canvas(db, snippet_id, canvas_id, owner):
                raise SnippetNotFoundError("snippet not found in this canvas")
        case NewSnippetTarget():
            pass
    if not data:
        raise EmptyTranscriptError("the recording is empty")

    transcript = await transcriber.transcribe(data, mime_type)
    try:
        assembled = assemble_lines(transcript.words, parsed.breaks_ms, EngineOracle())
    except LineAssemblyError as exc:
        raise EmptyTranscriptError("no speech was recognised in the recording") from exc
    lines = list(assembled.lines)
    recording = recording_service.NewRecording(
        mime_type=mime_type,
        # the service's decoded duration is exact; the client's clock is the fallback
        duration_ms=transcript.duration_ms if transcript.duration_ms and transcript.duration_ms > 0 else parsed.duration_ms,
        audio=data,
        transcript="\n".join(lines),
        split_mode=assembled.split_mode,
    )
    logger.info(
        "voice_take.assembled canvas_id=%s target=%s bytes=%d words=%d breaks=%d lines=%d split_mode=%s",
        canvas_id, parsed.target.kind, len(data), len(transcript.words), len(parsed.breaks_ms), len(lines),
        assembled.split_mode.value,
    )
    match parsed.target:
        case NewSnippetTarget(position_x=x, position_y=y):
            snippet, row = await recording_service.create_snippet_with_recording(
                db, canvas_id, owner, position_x=x, position_y=y, lines=lines, recording=recording
            )
            created = True
        case AppendTarget(snippet_id=snippet_id):
            snippet, row = await recording_service.append_with_recording(
                db, canvas_id, owner, snippet_id, lines=lines, recording=recording
            )
            created = False
    return VoiceTakeResponse(
        snippet=_snippet_response(snippet),
        recording=_recording_response(row),
        lines=lines,
        split_mode=assembled.split_mode,
        created=created,
    )


@router.post(
    "/api/canvases/{canvas_id}/voice-takes",
    response_model=VoiceTakeResponse,
    status_code=201,
    responses=_errors(404, 413, 415, 422, 500, 502, 503),
)
async def create_voice_take(
    canvas_id: uuid.UUID,
    audio: UploadFile = File(..., description="The recording; audio/webm, ogg, mp4, aac, mpeg or wav"),
    meta: str | None = Form(None, description='JSON: {"breaks_ms": [int], "duration_ms": int, "target": {...}}'),
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
    transcriber: Transcriber = Depends(get_transcriber),
):
    """Transcribe a recording into a new snippet or onto the end of one, and keep the audio."""
    try:
        return await _voice_take(canvas_id, audio, meta, _owner(user), db, transcriber)
    except VoiceTakeError as exc:
        logger.info("voice_take.rejected canvas_id=%s error=%s status=%d", canvas_id, exc.code.value, exc.status)
        raise _http_error(exc) from exc


@router.get(
    "/api/snippets/{snippet_id}/recordings",
    response_model=list[RecordingResponse],
    responses=_errors(404),
)
async def list_recordings(
    snippet_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    """The snippet's recordings, newest first (no audio bytes)."""
    try:
        rows = await recording_service.list_recordings(db, snippet_id, _owner(user))
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return [_recording_response(row) for row in rows]


@router.get(
    "/api/recordings/{recording_id}/audio",
    response_class=Response,
    responses={200: {"content": {"audio/*": {}}, "description": "The audio bytes, Content-Type as stored"}, **_errors(404)},
)
async def get_recording_audio(
    recording_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        stored = await recording_service.get_audio(db, recording_id, _owner(user))
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return Response(content=stored.audio, media_type=stored.mime_type, headers={"Cache-Control": AUDIO_CACHE_CONTROL})


@router.delete("/api/recordings/{recording_id}", status_code=204, responses=_errors(404, 500))
async def delete_recording(
    recording_id: uuid.UUID,
    user: User = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
):
    try:
        await recording_service.delete_recording(db, recording_id, _owner(user))
    except VoiceTakeError as exc:
        raise _http_error(exc) from exc
    return Response(status_code=204)
