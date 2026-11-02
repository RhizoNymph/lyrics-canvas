"""STT client against an in-process fake transport (no real service)."""

import json

import httpx
import pytest

from app.services.voice.errors import (
    AudioTooLargeError,
    EmptyTranscriptError,
    SttDisabledError,
    SttFailedError,
    SttUnavailableError,
)
from app.services.voice.line_assembly import TimedWord
from app.services.voice.stt_client import SttClient

AUDIO = b"\x1aE\xdf\xa3fake-webm-bytes"
OK_BODY = {
    "language": "en",
    "duration_ms": 2100,
    "words": [
        {"text": "hello", "start_ms": 0, "end_ms": 420, "probability": 0.98},
        {"text": "world.", "start_ms": 500, "end_ms": 900, "probability": 0.91},
    ],
}


def client_with(handler, url: str = "http://stt:8000", timeout: float = 7.5) -> SttClient:
    return SttClient(url, timeout, transport=httpx.MockTransport(handler))


def respond(status: int, body: object):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json=body)

    return handler


def raising(exc_type: type[Exception]):
    def handler(request: httpx.Request) -> httpx.Response:
        raise exc_type("boom", request=request)

    return handler


async def test_posts_multipart_audio_to_v1_transcribe_and_parses_words():
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=OK_BODY)

    transcript = await client_with(handler, url="http://stt:8000/").transcribe(AUDIO, "audio/webm;codecs=opus")
    assert transcript.language == "en"
    assert transcript.duration_ms == 2100
    assert transcript.words == (TimedWord("hello", 0, 420), TimedWord("world.", 500, 900))

    (request,) = seen
    assert request.method == "POST"
    assert str(request.url) == "http://stt:8000/v1/transcribe"
    assert request.headers["content-type"].startswith("multipart/form-data")
    body = request.read()
    assert b'name="audio"' in body
    assert b"Content-Type: audio/webm;codecs=opus" in body
    assert AUDIO in body


async def test_uses_the_configured_timeout():
    seen: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200, json=OK_BODY)

    await client_with(handler, timeout=7.5).transcribe(AUDIO, "audio/ogg")
    assert seen[0].extensions["timeout"] == {"connect": 7.5, "read": 7.5, "write": 7.5, "pool": 7.5}


async def test_empty_url_is_disabled_without_a_request():
    calls: list[httpx.Request] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(request)
        return httpx.Response(200, json=OK_BODY)

    with pytest.raises(SttDisabledError):
        await client_with(handler, url="").transcribe(AUDIO, "audio/ogg")
    with pytest.raises(SttDisabledError):
        await client_with(handler, url="   ").transcribe(AUDIO, "audio/ogg")
    assert calls == []


@pytest.mark.parametrize("words", [[], [{"text": "  ", "start_ms": 0, "end_ms": 10}]])
async def test_no_words_is_an_empty_transcript(words):
    with pytest.raises(EmptyTranscriptError):
        await client_with(respond(200, {**OK_BODY, "words": words})).transcribe(AUDIO, "audio/ogg")


async def test_blank_words_are_dropped_and_text_trimmed():
    body = {**OK_BODY, "words": [{"text": " ", "start_ms": 0, "end_ms": 5}, {"text": " hi ", "start_ms": 10, "end_ms": 50}]}
    transcript = await client_with(respond(200, body)).transcribe(AUDIO, "audio/ogg")
    assert transcript.words == (TimedWord("hi", 10, 50),)


async def test_optional_fields_may_be_missing():
    body = {"words": [{"text": "hi", "start_ms": 10, "end_ms": 50}]}
    transcript = await client_with(respond(200, body)).transcribe(AUDIO, "audio/ogg")
    assert transcript.language is None
    assert transcript.duration_ms is None


@pytest.mark.parametrize(
    "handler",
    [
        respond(503, {"error": "model_not_ready", "message": "loading"}),
        raising(httpx.ConnectError),
        raising(httpx.ConnectTimeout),
        raising(httpx.ReadTimeout),
        raising(httpx.PoolTimeout),
    ],
    ids=["503", "connect", "connect-timeout", "read-timeout", "pool-timeout"],
)
async def test_unreachable_or_not_ready_is_unavailable(handler):
    with pytest.raises(SttUnavailableError):
        await client_with(handler).transcribe(AUDIO, "audio/ogg")


async def test_service_413_is_audio_too_large():
    with pytest.raises(AudioTooLargeError):
        await client_with(respond(413, {"error": "audio_too_large", "message": "x"})).transcribe(AUDIO, "audio/ogg")


@pytest.mark.parametrize(
    "handler",
    [
        respond(400, {"error": "undecodable_audio", "message": "bad"}),
        respond(500, {"error": "transcription_failed", "message": "oops"}),
        respond(404, {"detail": "Not Found"}),
        respond(200, {"words": "nope"}),
        respond(200, {"words": [{"text": "a", "start_ms": "soon"}]}),
        respond(200, ["not", "an", "object"]),
        raising(httpx.RemoteProtocolError),
        raising(httpx.ReadError),
    ],
    ids=["400", "500", "404", "bad-words", "bad-word", "array", "protocol", "read-error"],
)
async def test_other_failures_are_stt_failed(handler):
    with pytest.raises(SttFailedError):
        await client_with(handler).transcribe(AUDIO, "audio/ogg")


async def test_non_json_body_is_stt_failed():
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=b"<html>proxy error</html>", headers={"content-type": "text/html"})

    with pytest.raises(SttFailedError):
        await client_with(handler).transcribe(AUDIO, "audio/ogg")


async def test_service_error_code_is_in_the_message():
    with pytest.raises(SttFailedError) as info:
        await client_with(respond(400, {"error": "undecodable_audio", "message": "bad"})).transcribe(AUDIO, "audio/ogg")
    assert "undecodable_audio" in info.value.message
    assert AUDIO.decode("latin-1") not in info.value.message


async def test_body_is_json_encoded_words_in_order():
    body = {"words": [{"text": f"w{i}", "start_ms": i * 10, "end_ms": i * 10 + 5} for i in range(50)]}
    transcript = await client_with(respond(200, json.loads(json.dumps(body)))).transcribe(AUDIO, "audio/ogg")
    assert [w.text for w in transcript.words] == [f"w{i}" for i in range(50)]
