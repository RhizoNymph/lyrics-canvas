"""End-to-end through the real faster-whisper backend with the `tiny` model on CPU.

Skipped unless the tiny weights are already in the Hugging Face cache (or in
STT_SMOKE_CACHE_DIR); the test never downloads. Pre-fetch with:

    uv run python -c "from faster_whisper.utils import download_model; download_model('tiny')"
"""

from __future__ import annotations

import io
import os
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from stt_service.app import create_app
from stt_service.config import Settings
from stt_service.whisper import whisper_loader

from .test_decode import wav_bytes

pytestmark = pytest.mark.smoke

TINY_REPO = "models--Systran--faster-whisper-tiny"


def cache_dir() -> Path | None:
    explicit = os.environ.get("STT_SMOKE_CACHE_DIR")
    if explicit:
        return Path(explicit)
    return None


def tiny_cached() -> bool:
    explicit = cache_dir()
    if explicit is not None:
        root = explicit
    else:
        hf_home = Path(os.environ.get("HF_HOME", Path.home() / ".cache" / "huggingface"))
        root = Path(os.environ.get("HF_HUB_CACHE", hf_home / "hub"))
    return any((root / TINY_REPO / "snapshots").glob("*/model.bin"))


@pytest.mark.skipif(not tiny_cached(), reason="faster-whisper tiny weights not cached")
@pytest.mark.parametrize("offline", ["true", "false"])
def test_tiny_model_round_trip(offline: str) -> None:
    # With weights cached, both modes must load without contacting the Hub.
    env = {"STT_MODEL": "tiny", "STT_DEVICE": "cpu", "STT_OFFLINE": offline, "STT_BEAM_SIZE": "1"}
    if (explicit := cache_dir()) is not None:
        env["STT_MODEL_CACHE_DIR"] = str(explicit)
    settings = Settings.from_env(env)
    app = create_app(settings, whisper_loader(settings))
    with TestClient(app) as client:
        import time

        deadline = time.monotonic() + 120
        while client.get("/readyz").status_code != 200:
            assert time.monotonic() < deadline, client.get("/readyz").json()
            time.sleep(0.2)
        r = client.post(
            "/v1/transcribe",
            files={"audio": ("tone.wav", io.BytesIO(wav_bytes(1.5)), "audio/wav")},
            data={"language": "en"},
        )
        assert r.status_code == 200, r.text
        body = r.json()
        assert body["language"] == "en"
        assert 1400 <= body["duration_ms"] <= 1600
        assert isinstance(body["words"], list)
        starts = [w["start_ms"] for w in body["words"]]
        assert starts == sorted(starts)

        r = client.post("/v1/transcribe", files={"audio": ("x.webm", b"garbage" * 50, "audio/webm")})
        assert r.status_code == 400
        assert r.json()["error"] == "undecodable_audio"
