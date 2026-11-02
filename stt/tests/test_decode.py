"""Audio decoding through PyAV (no model needed)."""

import io
import math
import struct
import wave

import pytest

from stt_service.errors import UndecodableAudio
from stt_service.whisper import SAMPLE_RATE, decode


def wav_bytes(seconds: float, rate: int = 44100) -> bytes:
    frames = int(seconds * rate)
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(
            b"".join(
                struct.pack("<hh", s, s)
                for s in (int(8000 * math.sin(2 * math.pi * 440 * i / rate)) for i in range(frames))
            )
        )
    return buf.getvalue()


def test_decodes_wav_to_16k_mono() -> None:
    samples = decode(io.BytesIO(wav_bytes(0.5)))
    assert samples.ndim == 1
    assert abs(len(samples) - SAMPLE_RATE // 2) < SAMPLE_RATE * 0.01
    assert samples.dtype.name == "float32"


@pytest.mark.parametrize(
    "data",
    [b"not audio at all" * 10, b"\x00" * 1024, b"RIFF\x00\x00\x00\x00WAVE"],
    ids=["text", "zeros", "truncated-wav"],
)
def test_garbage_is_undecodable(data: bytes) -> None:
    with pytest.raises(UndecodableAudio):
        decode(io.BytesIO(data))
