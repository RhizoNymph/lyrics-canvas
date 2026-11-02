"""Audio intake for voice takes: MIME allow-list and a size-bounded read of the uploaded part."""

from __future__ import annotations

from typing import Protocol

from app.services.voice.errors import AudioTooLargeError, UnsupportedAudioTypeError

ACCEPTED_AUDIO_TYPES: frozenset[str] = frozenset(
    {"audio/webm", "audio/ogg", "audio/mp4", "audio/aac", "audio/mpeg", "audio/wav", "audio/x-wav"}
)
READ_CHUNK_BYTES = 1024 * 1024


class AsyncReadable(Protocol):
    async def read(self, size: int = -1) -> bytes: ...


def accepted_mime(content_type: str | None) -> str:
    """The content type as stored (trimmed, type/subtype lower-cased, parameters kept) or
    ``UnsupportedAudioTypeError``."""
    if content_type is None:
        raise UnsupportedAudioTypeError("the audio part has no content type")
    base, sep, params = content_type.strip().partition(";")
    base = base.strip().lower()
    if base not in ACCEPTED_AUDIO_TYPES:
        raise UnsupportedAudioTypeError(
            f"unsupported audio type {base or '(empty)'!r}; accepted: {', '.join(sorted(ACCEPTED_AUDIO_TYPES))}"
        )
    return f"{base}{sep}{params}" if sep else base


async def read_bounded(upload: AsyncReadable, limit: int, chunk_size: int = READ_CHUNK_BYTES) -> bytes:
    """Read the whole upload in chunks, failing as soon as more than ``limit`` bytes arrive."""
    chunks: list[bytes] = []
    total = 0
    while True:
        chunk = await upload.read(chunk_size)
        if not chunk:
            return b"".join(chunks)
        total += len(chunk)
        if total > limit:
            raise AudioTooLargeError(f"the recording is larger than {limit} bytes")
        chunks.append(chunk)
