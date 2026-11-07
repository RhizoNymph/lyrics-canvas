"""The voice dataset zip (contract section 3, docs/features/voice_dataset.md#export-format).

Layout::

    audio/<recording_id>.<ext>   original bytes (ZIP_STORED: already compressed)
    metadata.jsonl               one object per recording, oldest first
    dataset.json                 format_version 1, counts, notes

The zip is written into a ``SpooledTemporaryFile`` (memory up to ``SPOOL_MAX_BYTES``, then disk).
Every blocking zip write runs in a worker thread (``asyncio.to_thread``) and the audio is loaded one
recording at a time through ``load_audio``, so the event loop never blocks on compression and at
most one recording's bytes are held at once. No transcoding or cutting.
"""

from __future__ import annotations

import asyncio
import json
import logging
import uuid
import zipfile
from collections.abc import Awaitable, Callable, Sequence
from dataclasses import dataclass
from datetime import datetime, timezone
from tempfile import SpooledTemporaryFile
from typing import IO

from app.models.recording import SplitMode
from app.services.voice.stt_client import audio_extension
from app.services.voice.timings import Timings, words_json

logger = logging.getLogger(__name__)

FORMAT_VERSION = 1
SPOOL_MAX_BYTES = 32 * 1024 * 1024
METADATA_PATH = "metadata.jsonl"
MANIFEST_PATH = "dataset.json"
NOTES = (
    "text is the user-checked transcript; whisper_text is the raw STT output. Clips longer than 30 s "
    "must be segmented (use lines[].start_ms/end_ms) before Whisper fine-tuning."
)

LoadAudio = Callable[[uuid.UUID], Awaitable[bytes]]


@dataclass(frozen=True, slots=True)
class ExportRecording:
    """One recording's metadata (no audio) as the export reads it."""

    id: uuid.UUID
    mime_type: str
    duration_ms: int
    byte_size: int
    language: str | None
    split_mode: SplitMode
    created_at: datetime
    verified_at: datetime | None
    verified_text: str | None
    transcript: str
    initial_prompt: str | None
    timings: Timings | None

    @property
    def verified(self) -> bool:
        return self.verified_text is not None

    @property
    def file(self) -> str:
        return f"audio/{self.id}.{audio_extension(self.mime_type)}"


def _iso(value: datetime | None) -> str | None:
    return None if value is None else value.isoformat()


def _lines(recording: ExportRecording) -> list[dict[str, object]] | None:
    if recording.timings is None:
        return None
    texts = recording.transcript.split("\n")
    line_timings = recording.timings.line_timings()
    if len(texts) != len(line_timings):
        logger.warning(
            "voice.export.line_mismatch recording_id=%s lines=%d spans=%d", recording.id, len(texts), len(line_timings)
        )
        return None
    return [
        {
            "whisper_text": text,
            "word_start": timing.word_start,
            "word_end": timing.word_end,
            "start_ms": timing.start_ms,
            "end_ms": timing.end_ms,
        }
        for text, timing in zip(texts, line_timings)
    ]


def metadata_record(recording: ExportRecording) -> dict[str, object]:
    return {
        "id": str(recording.id),
        "file": recording.file,
        "mime_type": recording.mime_type,
        "duration_ms": recording.duration_ms,
        "language": recording.language,
        "split_mode": recording.split_mode.value,
        "created_at": _iso(recording.created_at),
        "verified_at": _iso(recording.verified_at),
        "text": recording.verified_text,
        "whisper_text": recording.transcript,
        "initial_prompt": recording.initial_prompt,
        "words": None if recording.timings is None else words_json(recording.timings.words),
        "lines": _lines(recording),
    }


def manifest(recordings: Sequence[ExportRecording], exported_at: datetime) -> dict[str, object]:
    return {
        "format_version": FORMAT_VERSION,
        "exported_at": exported_at.isoformat(),
        "count": len(recordings),
        "verified_count": sum(1 for r in recordings if r.verified),
        "total_duration_ms": sum(r.duration_ms for r in recordings),
        "notes": NOTES,
    }


def metadata_jsonl(recordings: Sequence[ExportRecording]) -> str:
    """One compact JSON object per line, each line ending in "\\n"; "" for no recordings."""
    return "".join(json.dumps(metadata_record(r), ensure_ascii=False) + "\n" for r in recordings)


def export_filename(exported_at: datetime) -> str:
    stamp = exported_at.astimezone(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"lyrics-canvas-voice-dataset-{stamp}.zip"


class ZipBuilder:
    """Synchronous zip writer over a spooled temp file; call from a worker thread."""

    def __init__(self, spool_max_bytes: int = SPOOL_MAX_BYTES):
        self.file: IO[bytes] = SpooledTemporaryFile(max_size=spool_max_bytes)
        self._zip = zipfile.ZipFile(self.file, mode="w")

    def add_audio(self, path: str, data: bytes) -> None:
        self._zip.writestr(path, data, compress_type=zipfile.ZIP_STORED)

    def add_text(self, path: str, text: str) -> None:
        self._zip.writestr(path, text.encode("utf-8"), compress_type=zipfile.ZIP_DEFLATED)

    def finish(self) -> IO[bytes]:
        """Close the archive and rewind; the caller owns (and closes) the returned file."""
        self._zip.close()
        self.file.seek(0)
        return self.file

    def discard(self) -> None:
        self._zip.close()
        self.file.close()


async def build_export(
    recordings: Sequence[ExportRecording], load_audio: LoadAudio, exported_at: datetime
) -> IO[bytes]:
    """The finished zip, rewound. On any failure the temp file is closed before re-raising."""
    builder = await asyncio.to_thread(ZipBuilder)
    try:
        for recording in recordings:
            data = await load_audio(recording.id)
            await asyncio.to_thread(builder.add_audio, recording.file, data)
        await asyncio.to_thread(builder.add_text, METADATA_PATH, metadata_jsonl(recordings))
        body = json.dumps(manifest(recordings, exported_at), ensure_ascii=False, indent=2) + "\n"
        await asyncio.to_thread(builder.add_text, MANIFEST_PATH, body)
        return await asyncio.to_thread(builder.finish)
    except BaseException:
        await asyncio.to_thread(builder.discard)
        raise
