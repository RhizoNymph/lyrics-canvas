"""HTTP contract of the STT service, exercised against a fake transcriber."""

from __future__ import annotations

import time
from collections.abc import Iterator
from contextlib import contextmanager

import pytest
from fastapi.testclient import TestClient

from stt_service.app import create_app
from stt_service.config import Settings
from stt_service.errors import TranscriptionFailed, UndecodableAudio

from .fakes import FakeTranscriber, GatedLoader, instant_loader, raw

AUDIO = b"RIFF....WAVEfmt fake audio bytes"


def settings(**env: str) -> Settings:
    return Settings.from_env({"STT_DEVICE": "cpu", **env})


@contextmanager
def running(loader, **env: str) -> Iterator[TestClient]:
    app = create_app(settings(**env), loader)
    with TestClient(app, raise_server_exceptions=False) as client:
        yield client


@contextmanager
def ready_client(fake: FakeTranscriber, **env: str) -> Iterator[TestClient]:
    with running(instant_loader(fake), **env) as client:
        wait_ready(client)
        yield client


def wait_ready(client: TestClient) -> None:
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if client.get("/readyz").status_code == 200:
            return
        time.sleep(0.01)
    raise AssertionError("service never became ready")


def post(client: TestClient, audio: bytes = AUDIO, **fields: str):
    return client.post(
        "/v1/transcribe",
        files={"audio": ("take.webm", audio, "audio/webm")},
        data=fields,
    )


def assert_error(response, status: int, code: str) -> None:
    assert response.status_code == status, response.text
    body = response.json()
    assert set(body) == {"error", "message"}
    assert body["error"] == code
    assert isinstance(body["message"], str) and body["message"]


# ------------------------------------------------------------------ health


def test_healthz_up_while_model_loading() -> None:
    loader = GatedLoader([FakeTranscriber()])
    with running(loader) as client:
        r = client.get("/healthz")
        assert r.status_code == 200
        assert r.json() == {"status": "ok"}
        r = client.get("/readyz")
        assert r.status_code == 503
        assert r.json()["status"] == "loading"
        loader.release.set()
        wait_ready(client)
        r = client.get("/readyz")
        assert r.json() == {"status": "ok"}


def test_readyz_reports_failed_load() -> None:
    loader = GatedLoader([RuntimeError("no cuda device")])
    loader.release.set()
    with running(loader, STT_LOAD_RETRY_INITIAL_SECONDS="60", STT_LOAD_RETRY_MAX_SECONDS="60") as client:
        deadline = time.monotonic() + 5
        body: dict = {}
        while time.monotonic() < deadline:
            body = client.get("/readyz").json()
            if body["status"] == "failed":
                break
            time.sleep(0.01)
        assert body["status"] == "failed"
        assert "no cuda device" in body["message"]
        assert client.get("/healthz").status_code == 200


# -------------------------------------------------------------- transcribe


def test_transcribe_success_shape() -> None:
    fake = FakeTranscriber(
        result=raw((" hello", 0.0, 0.42, 0.98), (" world", 0.5, 0.91, 0.5), duration=61.234)
    )
    with ready_client(fake) as client:
        r = post(client)
    assert r.status_code == 200, r.text
    assert r.json() == {
        "language": "en",
        "duration_ms": 61234,
        "words": [
            {"text": "hello", "start_ms": 0, "end_ms": 420, "probability": 0.98},
            {"text": "world", "start_ms": 500, "end_ms": 910, "probability": 0.5},
        ],
    }
    assert fake.calls[0].audio == AUDIO


def test_language_and_prompt_forwarded() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake) as client:
        assert post(client, language=" DE ", initial_prompt="  verse one  ").status_code == 200
    options = fake.calls[0].options
    assert options.language == "de"
    assert options.initial_prompt == "verse one"


def test_omitted_language_is_auto_detect() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake) as client:
        assert post(client).status_code == 200
        assert post(client, language="", initial_prompt="").status_code == 200
    assert [c.options.language for c in fake.calls] == [None, None]
    assert [c.options.initial_prompt for c in fake.calls] == [None, None]


def test_default_language_from_config() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake, STT_DEFAULT_LANGUAGE="fr") as client:
        post(client)
        post(client, language="es")
    assert [c.options.language for c in fake.calls] == ["fr", "es"]


def test_unknown_language_rejected() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake) as client:
        assert_error(post(client, language="xx"), 422, "invalid_request")
    assert fake.calls == []


def test_missing_audio_field() -> None:
    with ready_client(FakeTranscriber(result=raw())) as client:
        r = client.post("/v1/transcribe", data={"language": "en"})
    assert_error(r, 422, "invalid_request")


def test_not_multipart() -> None:
    with ready_client(FakeTranscriber(result=raw())) as client:
        r = client.post(
            "/v1/transcribe", content=b"raw", headers={"content-type": "application/octet-stream"}
        )
    assert_error(r, 422, "invalid_request")


def test_empty_audio_is_undecodable() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake) as client:
        assert_error(post(client, audio=b""), 400, "undecodable_audio")
    assert fake.calls == []


def test_undecodable_audio_from_transcriber() -> None:
    fake = FakeTranscriber(error=UndecodableAudio("could not decode audio: invalid data"))
    with ready_client(fake) as client:
        assert_error(post(client), 400, "undecodable_audio")


def test_audio_at_limit_accepted() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake, STT_MAX_UPLOAD_BYTES="64") as client:
        assert post(client, audio=b"a" * 64).status_code == 200


def test_audio_over_limit_rejected() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake, STT_MAX_UPLOAD_BYTES="64") as client:
        assert_error(post(client, audio=b"a" * 65), 413, "audio_too_large")
    assert fake.calls == []


def test_oversized_content_length_rejected_before_parsing() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake, STT_MAX_UPLOAD_BYTES="64") as client:
        body = b"x" * (64 + 2 * 1024 * 1024)
        r = client.post(
            "/v1/transcribe",
            content=body,
            headers={"content-type": "multipart/form-data; boundary=zzz"},
        )
    assert_error(r, 413, "audio_too_large")


def test_default_limit_is_25_mib() -> None:
    fake = FakeTranscriber(result=raw())
    with ready_client(fake) as client:
        assert_error(post(client, audio=b"\0" * (25 * 1024 * 1024 + 1)), 413, "audio_too_large")
        assert post(client, audio=b"\0" * (25 * 1024 * 1024)).status_code == 200


def test_model_not_ready() -> None:
    loader = GatedLoader([FakeTranscriber(result=raw())])
    with running(loader) as client:
        assert_error(post(client), 503, "model_not_ready")
        loader.release.set()
        wait_ready(client)
        assert post(client).status_code == 200


def test_transcription_failure_typed() -> None:
    fake = FakeTranscriber(error=TranscriptionFailed("decoder exploded"))
    with ready_client(fake) as client:
        assert_error(post(client), 500, "transcription_failed")


def test_unexpected_exception_becomes_transcription_failed() -> None:
    fake = FakeTranscriber(error=RuntimeError("CUDA out of memory"))
    with ready_client(fake) as client:
        r = post(client)
    assert_error(r, 500, "transcription_failed")
    assert "CUDA out of memory" in r.json()["message"]


def test_service_keeps_working_after_failure() -> None:
    fake = FakeTranscriber(error=RuntimeError("transient"))
    with ready_client(fake) as client:
        assert post(client).status_code == 500
        fake.error = None
        fake.result = raw((" ok", 0.0, 0.1, 0.9))
        assert post(client).status_code == 200


def test_unknown_route_and_method() -> None:
    with ready_client(FakeTranscriber(result=raw())) as client:
        assert client.get("/v1/transcribe").status_code == 405
        assert client.get("/nope").status_code == 404


@pytest.mark.parametrize("path", ["/healthz", "/readyz"])
def test_health_endpoints_are_get_only(path: str) -> None:
    with ready_client(FakeTranscriber(result=raw())) as client:
        assert client.post(path).status_code == 405
