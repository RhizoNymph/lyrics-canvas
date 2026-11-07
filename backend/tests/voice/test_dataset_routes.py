"""Vocabulary hints at take time, transcript verification, stats and the dataset export, over the
HTTP API with the recording service and the transcriber faked (fixtures from test_voice_routes)."""

from __future__ import annotations

import io
import json
import logging
import uuid
import zipfile
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api.voice import get_transcriber
from app.config import settings
from app.main import app
from app.models.recording import SplitMode
from app.services import recording_service
from app.services.voice.dataset_export import ExportRecording
from app.services.voice.errors import ExportTooLargeError, RecordingNotFoundError, VoicePersistError
from app.services.voice.hints import HintWord
from app.services.voice.line_assembly import TimedWord
from app.services.voice.stt_client import SttClient
from app.services.voice.timings import Timings
from tests.voice.test_voice_routes import (  # noqa: F401  (pytest fixtures)
    AUDIO,
    CREATED,
    client,
    error_of,
    meta,
    post_take,
    recording_row,
    store,
    transcriber,
)

T0 = datetime(2026, 10, 1, tzinfo=timezone.utc)


def bank(*texts: str) -> list[HintWord]:
    return [HintWord(text=t, created_at=T0 + timedelta(minutes=i)) for i, t in enumerate(texts)]


# ---- vocabulary hints at take time ----


def test_bank_words_are_sent_as_the_prompt_and_stored(client, store, transcriber):
    store.bank_words = bank("night", "zephyr")
    res = post_take(client, meta(breaks=[750]))
    assert res.status_code == 201, res.text
    (prompt,) = transcriber.prompts
    assert prompt is not None and prompt.startswith("Vocabulary: ") and prompt.endswith("zephyr.")
    assert set(prompt.removeprefix("Vocabulary: ").removesuffix(".").split(", ")) == {"night", "zephyr"}
    rec = store.created[0]["recording"]
    assert rec.initial_prompt == prompt
    assert res.json()["recording"]["initial_prompt"] == prompt


def test_take_stores_words_spans_and_language(client, store):
    res = post_take(client, meta(breaks=[750]))
    assert res.status_code == 201, res.text
    rec = store.created[0]["recording"]
    assert rec.language == "en"
    assert rec.timings is not None
    assert rec.timings.spans == ((0, 2), (2, 4))
    assert [w.text for w in rec.timings.words] == ["a", "b", "c", "d"]
    body = res.json()["recording"]
    assert body["has_timings"] is True
    assert body["lines"] == ["a b", "c d"]
    assert body["line_spans"] == [[0, 2], [2, 4]]
    assert body["words"][0] == {"text": "a", "start_ms": 0, "end_ms": 300, "probability": None}
    assert body["language"] == "en"
    assert body["verified_text"] is None and body["verified_at"] is None


def test_append_stores_the_takes_own_lines(client, store):
    target = {"kind": "append", "snippet_id": str(store.existing.id)}
    res = post_take(client, meta(target))
    assert res.status_code == 201, res.text
    rec = store.appended[0]["recording"]
    assert rec.transcript == "a b c d"
    assert rec.timings.spans == ((0, 4),)


def test_opting_out_sends_no_prompt(client, store, transcriber):
    store.bank_words = bank("zephyr")
    body = json.loads(meta())
    body["vocabulary_hints"] = False
    assert post_take(client, json.dumps(body)).status_code == 201
    assert transcriber.prompts == [None]
    assert store.created[0]["recording"].initial_prompt is None


def test_server_switch_off_sends_no_prompt(client, store, transcriber, monkeypatch):
    monkeypatch.setattr(settings, "voice_vocab_hints", False)
    store.bank_words = bank("zephyr")
    assert post_take(client, meta()).status_code == 201
    assert transcriber.prompts == [None]


def test_empty_bank_sends_no_prompt(client, store, transcriber):
    assert post_take(client, meta()).status_code == 201
    assert transcriber.prompts == [None]
    assert store.created[0]["recording"].initial_prompt is None


def test_prompt_budget_comes_from_settings(client, store, transcriber, monkeypatch):
    monkeypatch.setattr(settings, "voice_vocab_hint_max_chars", len("Vocabulary: zephyr."))
    store.bank_words = bank("night", "zephyr")
    assert post_take(client, meta()).status_code == 201
    assert transcriber.prompts == ["Vocabulary: zephyr."]


def test_vocabulary_hints_must_be_a_boolean(client, store, transcriber):
    body = json.loads(meta())
    body["vocabulary_hints"] = "maybe"
    res = post_take(client, json.dumps(body))
    assert res.status_code == 422
    assert error_of(res) == "invalid_meta"


def test_prompt_log_has_counts_but_no_words(client, store, caplog):
    store.bank_words = bank("night", "zephyr", "--")
    with caplog.at_level(logging.INFO, logger="app.api.voice"):
        assert post_take(client, meta()).status_code == 201
    (line,) = [r.getMessage() for r in caplog.records if r.getMessage().startswith("voice.take.prompt")]
    assert "words_offered=2" in line and "words_used=2" in line and "chars=" in line
    assert "zephyr" not in line and "night" not in line


def test_real_client_sends_the_prompt_form_field(client, store):
    seen: list[bytes] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request.read())
        words = [{"text": t, "start_ms": i * 400, "end_ms": i * 400 + 300, "probability": 0.5} for i, t in enumerate("a b".split())]
        return httpx.Response(200, json={"language": "en", "duration_ms": 900, "words": words})

    app.dependency_overrides[get_transcriber] = lambda: SttClient("http://stt:8000", 5, transport=httpx.MockTransport(handler))
    store.bank_words = bank("zephyr")
    res = post_take(client, meta())
    assert res.status_code == 201, res.text
    assert b'name="initial_prompt"' in seen[0] and b"Vocabulary: zephyr." in seen[0]
    assert res.json()["recording"]["words"][0]["probability"] == 0.5


def test_language_longer_than_the_column_is_dropped():
    assert recording_service.stored_language("en") == "en"
    assert recording_service.stored_language(" yue ") == "yue"
    assert recording_service.stored_language("") is None
    assert recording_service.stored_language(None) is None
    assert recording_service.stored_language("english-us") is None


# ---- verification ----


WORDS = (TimedWord("a", 0, 300), TimedWord("b", 400, 700))


@pytest.fixture
def dataset(store, monkeypatch):
    """Adds the dataset operations to the fake store."""

    async def set_verification(db, recording_id, owner_id, text):
        row = store.recordings.get(recording_id)
        if row is None or store.owner_of_snippet(row.snippet_id) != owner_id:
            raise RecordingNotFoundError("recording not found")
        if store.fail_with:
            raise store.fail_with
        row.verified_text = text
        row.verified_at = datetime.now(timezone.utc)
        return row

    async def clear_verification(db, recording_id, owner_id):
        row = store.recordings.get(recording_id)
        if row is None or store.owner_of_snippet(row.snippet_id) != owner_id:
            raise RecordingNotFoundError("recording not found")
        row.verified_text = None
        row.verified_at = None
        return row

    async def recording_stats(db, owner_id):
        rows = [r for r in store.recordings.values() if store.owner_of_snippet(r.snippet_id) == owner_id]
        verified = [r for r in rows if r.verified_text is not None]
        return recording_service.RecordingStats(
            total=len(rows), verified=len(verified),
            total_duration_ms=sum(r.duration_ms for r in rows),
            verified_duration_ms=sum(r.duration_ms for r in verified),
            with_timings=sum(1 for r in rows if r.words is not None and r.line_spans is not None),
        )

    async def export_recordings(db, owner_id, *, include_unverified, max_audio_bytes):
        rows = sorted(
            (r for r in store.recordings.values() if store.owner_of_snippet(r.snippet_id) == owner_id),
            key=lambda r: (r.created_at, str(r.id)),
        )
        if not include_unverified:
            rows = [r for r in rows if r.verified_text is not None]
        if sum(r.byte_size for r in rows) > max_audio_bytes:
            raise ExportTooLargeError("too large")
        return [
            ExportRecording(
                id=r.id, mime_type=r.mime_type, duration_ms=r.duration_ms, byte_size=r.byte_size,
                language=r.language, split_mode=r.split_mode, created_at=r.created_at, verified_at=r.verified_at,
                verified_text=r.verified_text, transcript=r.transcript, initial_prompt=r.initial_prompt,
                timings=recording_service.timings_of(r),
            )
            for r in rows
        ]

    for fn in (set_verification, clear_verification, recording_stats, export_recordings):
        monkeypatch.setattr(recording_service, fn.__name__, fn)
    return store


def seed(store, snippet, *, created=CREATED, timed=True, verified=None, mime="audio/ogg", audio=AUDIO, duration=1000):
    timings = Timings(WORDS, ((0, 1), (1, 2))) if timed else None
    rec = recording_service.NewRecording(
        mime_type=mime, duration_ms=duration, audio=audio, transcript="a\nb", split_mode=SplitMode.taps,
        timings=timings, language="en" if timed else None, initial_prompt="Vocabulary: a." if timed else None,
    )
    row = recording_row(snippet.id, rec, created_at=created)
    if verified is not None:
        row.verified_text = verified
        row.verified_at = created + timedelta(days=1)
    store.recordings[row.id] = row
    store.audio[row.id] = (mime, audio)
    return row


def test_put_verification_normalizes_and_returns_the_recording(client, dataset):
    row = seed(dataset, dataset.existing)
    res = client.put(f"/api/recordings/{row.id}/verification", json={"text": "\r\n a  \r\n\r\n\r\nbee \n"})
    assert res.status_code == 200, res.text
    body = res.json()
    assert body["verified_text"] == " a\n\nbee"
    assert body["verified_at"] is not None
    assert body["id"] == str(row.id)
    assert body["transcript"] == "a\nb"  # the Whisper text is untouched
    assert body["lines"] == ["a", "b"]
    assert body["has_timings"] is True


@pytest.mark.parametrize("text", ["", "  \n\r\n\t", "x" * 10_001])
def test_put_invalid_verification_is_422(client, dataset, text):
    row = seed(dataset, dataset.existing)
    res = client.put(f"/api/recordings/{row.id}/verification", json={"text": text})
    assert res.status_code == 422
    assert error_of(res) == "invalid_verification"
    assert row.verified_text is None


def test_put_verification_without_text_is_a_validation_error(client, dataset):
    row = seed(dataset, dataset.existing)
    assert client.put(f"/api/recordings/{row.id}/verification", json={}).status_code == 422


@pytest.mark.parametrize("which", ["foreign", "missing"])
def test_put_verification_of_a_foreign_or_missing_recording_is_404(client, dataset, which):
    rid = seed(dataset, dataset.foreign).id if which == "foreign" else uuid.uuid4()
    res = client.put(f"/api/recordings/{rid}/verification", json={"text": "hi"})
    assert res.status_code == 404
    assert error_of(res) == "recording_not_found"


def test_put_verification_persist_failure_is_500(client, dataset):
    row = seed(dataset, dataset.existing)
    dataset.fail_with = VoicePersistError("could not save the checked transcript")
    res = client.put(f"/api/recordings/{row.id}/verification", json={"text": "hi"})
    assert res.status_code == 500
    assert error_of(res) == "persist_failed"


def test_delete_verification_clears_both_and_is_idempotent(client, dataset):
    row = seed(dataset, dataset.existing, verified="checked")
    for _ in range(2):
        res = client.delete(f"/api/recordings/{row.id}/verification")
        assert res.status_code == 200, res.text
        assert res.json()["verified_text"] is None
        assert res.json()["verified_at"] is None


@pytest.mark.parametrize("which", ["foreign", "missing"])
def test_delete_verification_of_a_foreign_or_missing_recording_is_404(client, dataset, which):
    rid = seed(dataset, dataset.foreign, verified="x").id if which == "foreign" else uuid.uuid4()
    res = client.delete(f"/api/recordings/{rid}/verification")
    assert res.status_code == 404
    assert error_of(res) == "recording_not_found"


def test_old_rows_have_null_dataset_fields(client, dataset):
    seed(dataset, dataset.existing, timed=False)
    (body,) = client.get(f"/api/snippets/{dataset.existing.id}/recordings").json()
    assert body["has_timings"] is False
    assert body["words"] is None and body["line_spans"] is None
    assert body["language"] is None and body["initial_prompt"] is None
    assert body["lines"] == ["a", "b"]


def test_list_includes_words(client, dataset):
    seed(dataset, dataset.existing)
    (body,) = client.get(f"/api/snippets/{dataset.existing.id}/recordings").json()
    assert body["words"] == [
        {"text": "a", "start_ms": 0, "end_ms": 300, "probability": None},
        {"text": "b", "start_ms": 400, "end_ms": 700, "probability": None},
    ]
    assert body["line_spans"] == [[0, 1], [1, 2]]


def test_malformed_stored_timings_read_as_none(client, dataset):
    row = seed(dataset, dataset.existing)
    row.line_spans = [[0, 5]]
    (body,) = client.get(f"/api/snippets/{dataset.existing.id}/recordings").json()
    assert body["has_timings"] is False and body["words"] is None


# ---- stats ----


def test_stats(client, dataset):
    seed(dataset, dataset.existing, verified="a\nb", duration=1000)
    seed(dataset, dataset.existing, duration=2000)
    seed(dataset, dataset.elsewhere, timed=False, duration=500)
    seed(dataset, dataset.foreign, verified="x", duration=9999)
    res = client.get("/api/recordings/stats")
    assert res.status_code == 200, res.text
    assert res.json() == {
        "total": 3, "verified": 1, "total_duration_ms": 3500, "verified_duration_ms": 1000, "with_timings": 2,
    }


def test_stats_empty(client, dataset):
    assert client.get("/api/recordings/stats").json() == {
        "total": 0, "verified": 0, "total_duration_ms": 0, "verified_duration_ms": 0, "with_timings": 0,
    }


# ---- export ----


def unzip(res) -> zipfile.ZipFile:
    return zipfile.ZipFile(io.BytesIO(res.content))


def test_export_only_verified_by_default(client, dataset):
    checked = seed(dataset, dataset.existing, verified="a\nbee", mime="audio/webm;codecs=opus", audio=b"webm-bytes")
    seed(dataset, dataset.existing, created=CREATED + timedelta(hours=1))
    seed(dataset, dataset.foreign, verified="not yours")
    res = client.get("/api/recordings/export")
    assert res.status_code == 200, res.text
    assert res.headers["content-type"] == "application/zip"
    disposition = res.headers["content-disposition"]
    assert disposition.startswith('attachment; filename="lyrics-canvas-voice-dataset-')
    assert disposition.endswith('Z.zip"')
    assert int(res.headers["content-length"]) == len(res.content)
    archive = unzip(res)
    path = f"audio/{checked.id}.webm"
    assert archive.namelist() == [path, "metadata.jsonl", "dataset.json"]
    assert archive.read(path) == b"webm-bytes"
    (line,) = archive.read("metadata.jsonl").decode().splitlines()
    record = json.loads(line)
    assert record["id"] == str(checked.id)
    assert record["file"] == path
    assert record["text"] == "a\nbee"
    assert record["whisper_text"] == "a\nb"
    assert record["initial_prompt"] == "Vocabulary: a."
    assert record["lines"] == [
        {"whisper_text": "a", "word_start": 0, "word_end": 1, "start_ms": 0, "end_ms": 300},
        {"whisper_text": "b", "word_start": 1, "word_end": 2, "start_ms": 400, "end_ms": 700},
    ]
    manifest = json.loads(archive.read("dataset.json"))
    assert manifest["format_version"] == 1
    assert (manifest["count"], manifest["verified_count"], manifest["total_duration_ms"]) == (1, 1, 1000)
    assert "whisper_text is the raw STT output" in manifest["notes"]


def test_export_including_unverified_oldest_first(client, dataset):
    newer = seed(dataset, dataset.existing, created=CREATED + timedelta(hours=1), timed=False)
    older = seed(dataset, dataset.elsewhere, verified="ok")
    res = client.get("/api/recordings/export", params={"include_unverified": "true"})
    assert res.status_code == 200, res.text
    rows = [json.loads(line) for line in unzip(res).read("metadata.jsonl").decode().splitlines()]
    assert [r["id"] for r in rows] == [str(older.id), str(newer.id)]
    assert rows[1]["text"] is None
    assert rows[1]["words"] is None and rows[1]["lines"] is None
    manifest = json.loads(unzip(res).read("dataset.json"))
    assert (manifest["count"], manifest["verified_count"]) == (2, 1)


def test_empty_export_is_a_valid_zip(client, dataset):
    seed(dataset, dataset.existing)  # not verified
    res = client.get("/api/recordings/export")
    assert res.status_code == 200
    archive = unzip(res)
    assert archive.namelist() == ["metadata.jsonl", "dataset.json"]
    assert archive.read("metadata.jsonl") == b""
    assert json.loads(archive.read("dataset.json"))["count"] == 0


def test_export_over_the_limit_is_413(client, dataset, monkeypatch):
    seed(dataset, dataset.existing, verified="x", audio=b"x" * 11)
    monkeypatch.setattr(settings, "voice_export_max_bytes", 10)
    res = client.get("/api/recordings/export")
    assert res.status_code == 413
    assert error_of(res) == "export_too_large"


def test_export_at_the_limit_is_allowed(client, dataset, monkeypatch):
    seed(dataset, dataset.existing, verified="x", audio=b"x" * 10)
    monkeypatch.setattr(settings, "voice_export_max_bytes", 10)
    assert client.get("/api/recordings/export").status_code == 200


def test_export_recording_deleted_mid_export_is_404(client, dataset, monkeypatch):
    seed(dataset, dataset.existing, verified="x")

    async def gone(db, recording_id, owner_id):
        raise RecordingNotFoundError("recording not found")

    monkeypatch.setattr(recording_service, "get_audio", gone)
    res = client.get("/api/recordings/export")
    assert res.status_code == 404
    assert error_of(res) == "recording_not_found"


def test_include_unverified_must_be_a_boolean(client, dataset):
    assert client.get("/api/recordings/export", params={"include_unverified": "perhaps"}).status_code == 422


# ---- routing and auth ----


def test_fixed_recording_paths_are_not_read_as_ids(client, dataset):
    assert client.get("/api/recordings/stats").status_code == 200
    assert client.get("/api/recordings/export").status_code == 200
    # the id routes still parse ids
    assert client.put("/api/recordings/stats/verification", json={"text": "x"}).status_code == 422
    assert client.delete("/api/recordings/export").status_code == 422


def test_dataset_routes_need_a_token(dataset):
    plain = TestClient(app)
    rid = uuid.uuid4()
    assert plain.get("/api/recordings/stats").status_code in (401, 403)
    assert plain.get("/api/recordings/export").status_code in (401, 403)
    assert plain.put(f"/api/recordings/{rid}/verification", json={"text": "x"}).status_code in (401, 403)
    assert plain.delete(f"/api/recordings/{rid}/verification").status_code in (401, 403)


def test_verification_text_is_never_logged(client, dataset, caplog):
    row = seed(dataset, dataset.existing)
    with caplog.at_level(logging.DEBUG):
        client.put(f"/api/recordings/{row.id}/verification", json={"text": "supersecretlyric"})
    assert "supersecretlyric" not in caplog.text

