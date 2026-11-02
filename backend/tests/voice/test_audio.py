"""Audio intake: accepted MIME types and bounded reads."""

import pytest

from app.services.voice.audio import ACCEPTED_AUDIO_TYPES, accepted_mime, read_bounded
from app.services.voice.errors import AudioTooLargeError, UnsupportedAudioTypeError


@pytest.mark.parametrize(
    "content_type, stored",
    [
        ("audio/webm", "audio/webm"),
        ("audio/webm;codecs=opus", "audio/webm;codecs=opus"),
        ("Audio/WebM; codecs=opus", "audio/webm; codecs=opus"),
        ("audio/ogg", "audio/ogg"),
        ("audio/mp4", "audio/mp4"),
        ("audio/aac", "audio/aac"),
        ("audio/mpeg", "audio/mpeg"),
        ("audio/wav", "audio/wav"),
        ("audio/x-wav", "audio/x-wav"),
        ("  audio/ogg;codecs=opus  ", "audio/ogg;codecs=opus"),
    ],
)
def test_accepted_types(content_type, stored):
    assert accepted_mime(content_type) == stored


def test_accepted_set_matches_the_contract():
    assert ACCEPTED_AUDIO_TYPES == {"audio/webm", "audio/ogg", "audio/mp4", "audio/aac", "audio/mpeg", "audio/wav", "audio/x-wav"}


@pytest.mark.parametrize("content_type", [None, "", "video/webm", "audio/flac", "application/octet-stream", "audio/webmx", "text/plain;audio/webm"])
def test_rejected_types(content_type):
    with pytest.raises(UnsupportedAudioTypeError):
        accepted_mime(content_type)


class FakeUpload:
    def __init__(self, data: bytes):
        self.data = data
        self.offset = 0
        self.reads: list[int] = []

    async def read(self, size: int = -1) -> bytes:
        self.reads.append(size)
        chunk = self.data[self.offset : self.offset + size]
        self.offset += len(chunk)
        return chunk


async def test_reads_everything_under_the_limit():
    assert await read_bounded(FakeUpload(b"x" * 2500), limit=3000, chunk_size=1000) == b"x" * 2500


async def test_exactly_the_limit_is_allowed():
    assert await read_bounded(FakeUpload(b"x" * 3000), limit=3000, chunk_size=1000) == b"x" * 3000


async def test_one_byte_over_the_limit_is_rejected_without_reading_everything():
    upload = FakeUpload(b"x" * 100_000)
    with pytest.raises(AudioTooLargeError):
        await read_bounded(upload, limit=3000, chunk_size=1000)
    assert upload.offset <= 4000
    assert all(size > 0 for size in upload.reads)


async def test_empty_upload_reads_as_empty():
    assert await read_bounded(FakeUpload(b""), limit=10) == b""
