"""Voice-take and recording endpoints with the recording service and the transcriber faked."""

from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.api.voice import get_transcriber
from app.config import settings
from app.db.database import get_db
from app.main import app
from app.models.recording import SplitMode
from app.services import recording_service
from app.services.snippet_content import compute_size, derive_new_content
from app.services.voice.errors import (
    EmptyTranscriptError,
    RecordingNotFoundError,
    SnippetNotFoundError,
    SttFailedError,
    SttUnavailableError,
    VoicePersistError,
)
from app.services.voice.line_assembly import TimedWord
from app.services.voice.stt_client import SttClient, Transcript
from tests.route_fakes import fake_snippet
from tests.voice.timeline import timeline

USER = uuid.uuid4()
OTHER_USER = uuid.uuid4()
CANVAS = uuid.uuid4()
OTHER_CANVAS = uuid.uuid4()
FOREIGN_CANVAS = uuid.uuid4()
AUDIO = b"OggS\x00fake-opus-audio"
CREATED = datetime(2026, 11, 1, 12, 0, tzinfo=timezone.utc)

# "a b c d": word i spans [400 i, 400 i + 300]
FOUR_WORDS = Transcript(language="en", duration_ms=1700, words=tuple(timeline("a b c d")))


class FakeTranscriber:
    def __init__(self, result: Transcript | Exception = FOUR_WORDS):
        self.result = result
        self.calls: list[tuple[bytes, str]] = []

    async def transcribe(self, audio: bytes, mime_type: str) -> Transcript:
        self.calls.append((audio, mime_type))
        if isinstance(self.result, Exception):
            raise self.result
        return self.result


def recording_row(snippet_id: uuid.UUID, recording: recording_service.NewRecording, **overrides) -> SimpleNamespace:
    fields = dict(
        id=uuid.uuid4(),
        snippet_id=snippet_id,
        mime_type=recording.mime_type,
        duration_ms=recording.duration_ms,
        byte_size=len(recording.audio),
        transcript=recording.transcript,
        split_mode=recording.split_mode,
        created_at=CREATED,
    )
    fields.update(overrides)
    return SimpleNamespace(**fields)


class FakeStore:
    """The recording service, in memory. canvases: id -> owner; snippets: id -> fake snippet."""

    def __init__(self):
        self.canvases = {CANVAS: USER, OTHER_CANVAS: USER, FOREIGN_CANVAS: OTHER_USER}
        self.existing = fake_snippet(CANVAS, "cat\ndog bat")
        self.elsewhere = fake_snippet(OTHER_CANVAS, "far away")
        self.foreign = fake_snippet(FOREIGN_CANVAS, "not yours")
        self.snippets = {s.id: s for s in (self.existing, self.elsewhere, self.foreign)}
        self.created: list[dict] = []
        self.appended: list[dict] = []
        self.recordings: dict[uuid.UUID, SimpleNamespace] = {}
        self.audio: dict[uuid.UUID, tuple[str, bytes]] = {}
        self.deleted: list[uuid.UUID] = []
        self.fail_with: Exception | None = None

    def owner_of_snippet(self, snippet_id):
        snippet = self.snippets.get(snippet_id)
        return None if snippet is None else self.canvases.get(snippet.canvas_id)

    async def canvas_owned(self, db, canvas_id, owner_id):
        return self.canvases.get(canvas_id) == owner_id

    async def snippet_in_canvas(self, db, snippet_id, canvas_id, owner_id):
        snippet = self.snippets.get(snippet_id)
        return snippet is not None and snippet.canvas_id == canvas_id and self.canvases.get(canvas_id) == owner_id

    async def create_snippet_with_recording(self, db, canvas_id, owner_id, *, position_x, position_y, lines, recording):
        if self.fail_with:
            raise self.fail_with
        self.created.append(dict(canvas_id=canvas_id, owner_id=owner_id, position_x=position_x, position_y=position_y, lines=list(lines), recording=recording))
        content = derive_new_content("\n".join(lines))
        snippet = fake_snippet(
            canvas_id, content.raw_text, grid_data=content.grid_data, width=content.width, height=content.height,
            position_x=position_x, position_y=position_y,
        )
        return snippet, recording_row(snippet.id, recording)

    async def append_with_recording(self, db, canvas_id, owner_id, snippet_id, *, lines, recording):
        if self.fail_with:
            raise self.fail_with
        self.appended.append(dict(canvas_id=canvas_id, owner_id=owner_id, snippet_id=snippet_id, lines=list(lines), recording=recording))
        base = self.snippets[snippet_id]
        snippet = fake_snippet(canvas_id, base.raw_text + "\n" + "\n".join(lines), id=snippet_id)
        return snippet, recording_row(snippet_id, recording)

    async def list_recordings(self, db, snippet_id, owner_id):
        if self.owner_of_snippet(snippet_id) != owner_id:
            raise SnippetNotFoundError("snippet not found")
        rows = [r for r in self.recordings.values() if r.snippet_id == snippet_id]
        return sorted(rows, key=lambda r: r.created_at, reverse=True)

    async def get_audio(self, db, recording_id, owner_id):
        row = self.recordings.get(recording_id)
        if row is None or self.owner_of_snippet(row.snippet_id) != owner_id:
            raise RecordingNotFoundError("recording not found")
        mime, audio = self.audio[recording_id]
        return recording_service.StoredAudio(mime_type=mime, audio=audio)

    async def delete_recording(self, db, recording_id, owner_id):
        row = self.recordings.get(recording_id)
        if row is None or self.owner_of_snippet(row.snippet_id) != owner_id:
            raise RecordingNotFoundError("recording not found")
        del self.recordings[recording_id]
        self.deleted.append(recording_id)


@pytest.fixture
def store(monkeypatch) -> FakeStore:
    fake = FakeStore()
    for name in (
        "canvas_owned", "snippet_in_canvas", "create_snippet_with_recording", "append_with_recording",
        "list_recordings", "get_audio", "delete_recording",
    ):
        monkeypatch.setattr(recording_service, name, getattr(fake, name))
    return fake


@pytest.fixture
def transcriber() -> FakeTranscriber:
    return FakeTranscriber()


@pytest.fixture
def client(transcriber):
    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER)
    app.dependency_overrides[get_transcriber] = lambda: transcriber
    yield TestClient(app)
    app.dependency_overrides.clear()


def meta(target: dict | None = None, breaks: list[int] | None = None, duration_ms: int = 1800) -> str:
    return json.dumps({
        "breaks_ms": breaks or [],
        "duration_ms": duration_ms,
        "target": target or {"kind": "new", "position_x": 120.5, "position_y": -40.0},
    })


def post_take(client, meta_value: str | None = None, audio: bytes = AUDIO, mime: str = "audio/ogg;codecs=opus", canvas=CANVAS):
    data = {} if meta_value is None else {"meta": meta_value}
    return client.post(f"/api/canvases/{canvas}/voice-takes", data=data, files={"audio": ("take.ogg", audio, mime)})


def error_of(res) -> str:
    body = res.json()
    assert set(body["detail"]) == {"error", "message"}, body
    return body["detail"]["error"]


# ---- POST voice-takes: new snippet ----


def test_new_snippet_from_taps(client, store, transcriber):
    res = post_take(client, meta(breaks=[750]))
    assert res.status_code == 201, res.text
    body = res.json()
    assert body["created"] is True
    assert body["lines"] == ["a b", "c d"]
    assert body["split_mode"] == "taps"
    assert body["snippet"]["raw_text"] == "a b\nc d"
    assert body["snippet"]["position_x"] == 120.5
    assert body["recording"]["transcript"] == "a b\nc d"
    assert body["recording"]["split_mode"] == "taps"
    assert body["recording"]["byte_size"] == len(AUDIO)
    assert body["recording"]["snippet_id"] == body["snippet"]["id"]
    assert "audio" not in body["recording"]

    (call,) = store.created
    assert (call["canvas_id"], call["owner_id"]) == (CANVAS, USER)
    assert (call["position_x"], call["position_y"]) == (120.5, -40.0)
    assert call["lines"] == ["a b", "c d"]
    rec = call["recording"]
    assert rec.audio == AUDIO
    assert rec.mime_type == "audio/ogg;codecs=opus"
    assert rec.split_mode == SplitMode.taps
    assert rec.duration_ms == 1700  # the service's decoded duration wins over the client's
    assert transcriber.calls == [(AUDIO, "audio/ogg;codecs=opus")]


def test_new_snippet_is_sized_like_post_snippets(client, store):
    res = post_take(client, meta(breaks=[750]))
    snippet = res.json()["snippet"]
    assert (snippet["width"], snippet["height"]) == compute_size(snippet["grid_data"])


def test_without_taps_a_short_take_is_single(client, store):
    res = post_take(client, meta())
    assert res.status_code == 201, res.text
    assert res.json()["lines"] == ["a b c d"]
    assert res.json()["split_mode"] == "single"


def test_without_taps_a_long_take_with_a_pause_is_auto_split(client, store, transcriber):
    words = timeline("I saw a black cat sleeping on the mat", gap_ms=80, pauses={4: 500})
    transcriber.result = Transcript(language="en", duration_ms=4000, words=tuple(words))
    res = post_take(client, meta())
    assert res.status_code == 201, res.text
    assert res.json()["lines"] == ["I saw a black cat", "sleeping on the mat"]
    assert res.json()["split_mode"] == "auto"


def test_client_duration_is_used_when_the_service_has_none(client, store, transcriber):
    transcriber.result = Transcript(language=None, duration_ms=None, words=FOUR_WORDS.words)
    assert post_take(client, meta(duration_ms=2345)).status_code == 201
    assert store.created[0]["recording"].duration_ms == 2345


def test_unsorted_and_duplicate_breaks_are_accepted(client, store):
    res = post_take(client, meta(breaks=[1150, 350, 360]))
    assert res.status_code == 201, res.text
    assert res.json()["lines"] == ["a", "b c", "d"]


# ---- POST voice-takes: append ----


def test_append_to_the_open_snippet(client, store):
    target = {"kind": "append", "snippet_id": str(store.existing.id)}
    res = post_take(client, meta(target, breaks=[750]))
    assert res.status_code == 201, res.text
    body = res.json()
    assert body["created"] is False
    assert body["snippet"]["id"] == str(store.existing.id)
    assert body["lines"] == ["a b", "c d"]
    (call,) = store.appended
    assert (call["canvas_id"], call["owner_id"], call["snippet_id"]) == (CANVAS, USER, store.existing.id)
    assert call["lines"] == ["a b", "c d"]
    assert store.created == []


@pytest.mark.parametrize("which", ["elsewhere", "foreign", "missing"])
def test_append_to_a_snippet_outside_the_canvas_is_not_found(client, store, transcriber, which):
    snippet_id = uuid.uuid4() if which == "missing" else getattr(store, which).id
    res = post_take(client, meta({"kind": "append", "snippet_id": str(snippet_id)}))
    assert res.status_code == 404
    assert error_of(res) == "snippet_not_found"
    assert transcriber.calls == []


# ---- POST voice-takes: errors ----


@pytest.mark.parametrize("canvas", [FOREIGN_CANVAS, uuid.uuid4()])
def test_foreign_or_missing_canvas_is_not_found_before_transcribing(client, store, transcriber, canvas):
    res = post_take(client, meta(), canvas=canvas)
    assert res.status_code == 404
    assert error_of(res) == "canvas_not_found"
    assert transcriber.calls == []


def test_upload_over_the_limit_is_413(client, store, transcriber, monkeypatch):
    monkeypatch.setattr(settings, "voice_max_upload_bytes", 10)
    res = post_take(client, meta(), audio=b"x" * 11)
    assert res.status_code == 413
    assert error_of(res) == "audio_too_large"
    assert transcriber.calls == []


def test_upload_at_the_limit_is_accepted(client, store, monkeypatch):
    monkeypatch.setattr(settings, "voice_max_upload_bytes", len(AUDIO))
    assert post_take(client, meta()).status_code == 201


@pytest.mark.parametrize("mime", ["video/webm", "audio/flac", "application/octet-stream", "text/plain"])
def test_unsupported_audio_type_is_415(client, store, transcriber, mime):
    res = post_take(client, meta(), mime=mime)
    assert res.status_code == 415
    assert error_of(res) == "unsupported_audio_type"
    assert transcriber.calls == []


@pytest.mark.parametrize(
    "meta_value",
    [
        None,
        "",
        "not json",
        "[]",
        json.dumps({"breaks_ms": [], "duration_ms": 10}),
        json.dumps({"breaks_ms": [], "duration_ms": 10, "target": {"kind": "elsewhere"}}),
        json.dumps({"breaks_ms": [], "duration_ms": 10, "target": {"kind": "new", "position_x": 1}}),
        json.dumps({"breaks_ms": [], "duration_ms": 10, "target": {"kind": "append"}}),
        json.dumps({"breaks_ms": [], "duration_ms": 10, "target": {"kind": "append", "snippet_id": "nope"}}),
        json.dumps({"breaks_ms": [-1], "duration_ms": 10, "target": {"kind": "new", "position_x": 0, "position_y": 0}}),
        json.dumps({"breaks_ms": ["soon"], "duration_ms": 10, "target": {"kind": "new", "position_x": 0, "position_y": 0}}),
        json.dumps({"breaks_ms": [], "duration_ms": -5, "target": {"kind": "new", "position_x": 0, "position_y": 0}}),
        json.dumps({"breaks_ms": list(range(2001)), "duration_ms": 10, "target": {"kind": "new", "position_x": 0, "position_y": 0}}),
    ],
    ids=["missing", "empty", "not-json", "array", "no-target", "bad-kind", "no-y", "no-snippet", "bad-uuid", "negative-break", "string-break", "negative-duration", "too-many-breaks"],
)
def test_invalid_meta_is_422(client, store, transcriber, meta_value):
    res = post_take(client, meta_value)
    assert res.status_code == 422
    assert error_of(res) == "invalid_meta"
    assert transcriber.calls == []


def test_breaks_may_be_omitted(client, store):
    body = json.dumps({"duration_ms": 10, "target": {"kind": "new", "position_x": 0, "position_y": 0}})
    assert post_take(client, body).status_code == 201


def test_empty_audio_is_an_empty_transcript_without_calling_the_service(client, store, transcriber):
    res = post_take(client, meta(), audio=b"")
    assert res.status_code == 422
    assert error_of(res) == "empty_transcript"
    assert transcriber.calls == []


@pytest.mark.parametrize(
    "error, status, code",
    [
        (EmptyTranscriptError("nothing heard"), 422, "empty_transcript"),
        (SttUnavailableError("down"), 503, "stt_unavailable"),
        (SttFailedError("broken"), 502, "stt_failed"),
    ],
)
def test_transcriber_failures_map_to_contract_codes(client, store, transcriber, error, status, code):
    transcriber.result = error
    res = post_take(client, meta())
    assert res.status_code == status
    assert error_of(res) == code
    assert store.created == []


def test_persist_failure_is_500(client, store):
    store.fail_with = VoicePersistError("could not store the voice take; nothing changed")
    res = post_take(client, meta())
    assert res.status_code == 500
    assert error_of(res) == "persist_failed"


def test_real_client_disabled_without_stt_url(store, monkeypatch):
    monkeypatch.setattr(settings, "stt_url", "")

    async def fake_db():
        yield object()

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=USER)
    try:
        res = post_take(TestClient(app), meta())
    finally:
        app.dependency_overrides.clear()
    assert res.status_code == 503
    assert error_of(res) == "stt_disabled"


@pytest.mark.parametrize(
    "handler, status, code",
    [
        (lambda request: httpx.Response(503, json={"error": "model_not_ready", "message": "x"}), 503, "stt_unavailable"),
        (lambda request: httpx.Response(400, json={"error": "undecodable_audio", "message": "x"}), 502, "stt_failed"),
        (lambda request: httpx.Response(200, json={"language": "en", "duration_ms": 10, "words": []}), 422, "empty_transcript"),
    ],
)
def test_real_client_over_a_fake_transport(client, store, handler, status, code):
    app.dependency_overrides[get_transcriber] = lambda: SttClient("http://stt:8000", 5, transport=httpx.MockTransport(handler))
    res = post_take(client, meta())
    assert res.status_code == status
    assert error_of(res) == code


def test_real_client_success_over_a_fake_transport(client, store):
    def handler(request: httpx.Request) -> httpx.Response:
        words = [{"text": t, "start_ms": i * 400, "end_ms": i * 400 + 300, "probability": 0.9} for i, t in enumerate("a b c d".split())]
        return httpx.Response(200, json={"language": "en", "duration_ms": 1700, "words": words})

    app.dependency_overrides[get_transcriber] = lambda: SttClient("http://stt:8000", 5, transport=httpx.MockTransport(handler))
    res = post_take(client, meta(breaks=[350]))
    assert res.status_code == 201, res.text
    assert res.json()["lines"] == ["a", "b c d"]


def test_voice_takes_need_a_token(store):
    res = post_take(TestClient(app), meta())
    assert res.status_code in (401, 403)


def test_canvas_id_must_be_a_uuid(client, store):
    res = client.post("/api/canvases/not-a-uuid/voice-takes", data={"meta": meta()}, files={"audio": ("a.ogg", AUDIO, "audio/ogg")})
    assert res.status_code == 422


# ---- recordings ----


def seed_recording(store: FakeStore, snippet, created: datetime = CREATED, mime: str = "audio/webm;codecs=opus", audio: bytes = AUDIO):
    rec = recording_service.NewRecording(mime_type=mime, duration_ms=1234, audio=audio, transcript="a b\nc d", split_mode=SplitMode.taps)
    row = recording_row(snippet.id, rec, created_at=created)
    store.recordings[row.id] = row
    store.audio[row.id] = (mime, audio)
    return row


def test_list_recordings_newest_first_without_audio(client, store):
    older = seed_recording(store, store.existing, created=datetime(2026, 10, 1, tzinfo=timezone.utc))
    newer = seed_recording(store, store.existing, created=datetime(2026, 10, 2, tzinfo=timezone.utc))
    seed_recording(store, store.elsewhere)
    res = client.get(f"/api/snippets/{store.existing.id}/recordings")
    assert res.status_code == 200, res.text
    body = res.json()
    assert [r["id"] for r in body] == [str(newer.id), str(older.id)]
    assert set(body[0]) == {"id", "snippet_id", "mime_type", "duration_ms", "byte_size", "transcript", "split_mode", "created_at"}
    assert body[0]["created_at"] == str(newer.created_at)
    assert body[0]["split_mode"] == "taps"


def test_list_recordings_empty(client, store):
    res = client.get(f"/api/snippets/{store.existing.id}/recordings")
    assert res.status_code == 200
    assert res.json() == []


@pytest.mark.parametrize("snippet_id", ["foreign", "missing"])
def test_list_recordings_of_a_foreign_snippet_is_not_found(client, store, snippet_id):
    sid = store.foreign.id if snippet_id == "foreign" else uuid.uuid4()
    res = client.get(f"/api/snippets/{sid}/recordings")
    assert res.status_code == 404
    assert error_of(res) == "snippet_not_found"


def test_audio_is_served_with_the_stored_type_and_private_caching(client, store):
    row = seed_recording(store, store.existing, audio=b"\x1aE\xdf\xa3webm")
    res = client.get(f"/api/recordings/{row.id}/audio")
    assert res.status_code == 200
    assert res.content == b"\x1aE\xdf\xa3webm"
    assert res.headers["content-type"] == "audio/webm;codecs=opus"
    assert res.headers["cache-control"] == "private, max-age=0"


def test_audio_of_a_foreign_recording_is_not_found(client, store):
    row = seed_recording(store, store.foreign)
    res = client.get(f"/api/recordings/{row.id}/audio")
    assert res.status_code == 404
    assert error_of(res) == "recording_not_found"
    assert client.get(f"/api/recordings/{uuid.uuid4()}/audio").status_code == 404


def test_delete_recording(client, store):
    row = seed_recording(store, store.existing)
    res = client.delete(f"/api/recordings/{row.id}")
    assert res.status_code == 204
    assert res.content == b""
    assert store.deleted == [row.id]
    again = client.delete(f"/api/recordings/{row.id}")
    assert again.status_code == 404
    assert error_of(again) == "recording_not_found"


def test_delete_foreign_recording_is_not_found(client, store):
    row = seed_recording(store, store.foreign)
    res = client.delete(f"/api/recordings/{row.id}")
    assert res.status_code == 404
    assert row.id in store.recordings


def test_recording_routes_need_a_token(store):
    plain = TestClient(app)
    assert plain.get(f"/api/snippets/{uuid.uuid4()}/recordings").status_code in (401, 403)
    assert plain.get(f"/api/recordings/{uuid.uuid4()}/audio").status_code in (401, 403)
    assert plain.delete(f"/api/recordings/{uuid.uuid4()}").status_code in (401, 403)


def test_recording_ids_must_be_uuids(client, store):
    assert client.get("/api/recordings/nope/audio").status_code == 422
    assert client.delete("/api/recordings/nope").status_code == 422
    assert client.get("/api/snippets/nope/recordings").status_code == 422


def test_transcript_words_are_never_echoed_in_errors(client, store, transcriber):
    transcriber.result = Transcript(language="en", duration_ms=10, words=(TimedWord("secret", 0, 10),))
    store.fail_with = VoicePersistError("could not store the voice take; nothing changed")
    res = post_take(client, meta())
    assert "secret" not in res.text
