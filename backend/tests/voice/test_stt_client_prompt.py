"""STT client: the optional initial_prompt form field, word probabilities and file extensions."""

from __future__ import annotations

import httpx
import pytest

from app.services.voice.stt_client import SttClient, audio_extension, audio_filename

AUDIO = b"OggS-audio"
BODY = {
    "language": "en",
    "duration_ms": 900,
    "words": [
        {"text": "hello", "start_ms": 0, "end_ms": 400, "probability": 0.75},
        {"text": "there", "start_ms": 500, "end_ms": 900},
    ],
}


def recording_client(seen: list[httpx.Request]) -> SttClient:
    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=BODY)

    return SttClient("http://stt:8000", 5, transport=httpx.MockTransport(handler))


async def test_prompt_is_sent_as_a_form_field():
    seen: list[httpx.Request] = []
    prompt = "Vocabulary: zephyr, Ñandú."
    await recording_client(seen).transcribe(AUDIO, "audio/ogg", prompt)
    body = seen[0].read()
    assert b'name="initial_prompt"' in body
    assert prompt.encode() in body
    assert b'name="audio"' in body and AUDIO in body


@pytest.mark.parametrize("call", ["omitted", "none"])
async def test_no_prompt_means_no_form_field(call):
    seen: list[httpx.Request] = []
    client = recording_client(seen)
    if call == "omitted":
        await client.transcribe(AUDIO, "audio/ogg")
    else:
        await client.transcribe(AUDIO, "audio/ogg", None)
    body = seen[0].read()
    assert b"initial_prompt" not in body
    assert b'name="audio"' in body


async def test_word_probabilities_are_kept():
    transcript = await recording_client([]).transcribe(AUDIO, "audio/ogg")
    assert [w.probability for w in transcript.words] == [0.75, None]


@pytest.mark.parametrize(
    "mime, ext",
    [
        ("audio/webm;codecs=opus", "webm"), ("audio/ogg", "ogg"), ("audio/mp4", "m4a"), ("audio/aac", "aac"),
        ("audio/mpeg", "mp3"), ("audio/wav", "wav"), ("audio/x-wav", "wav"), ("AUDIO/OGG ; codecs=opus", "ogg"),
        ("audio/flac", "bin"),
    ],
)
def test_audio_extension(mime, ext):
    assert audio_extension(mime) == ext
    assert audio_filename(mime) == f"take.{ext}"
