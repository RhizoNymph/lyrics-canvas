"""Draft and export routes with the database lookups faked (service.load_snippet / load_timings)."""

from __future__ import annotations

import uuid
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.deps import get_current_user
from app.db.database import get_db
from app.main import app
from app.services.score import check_score, score_to_json
from app.services.score_tools import service
from app.services.score_tools.errors import RecordingHasNoTimingsError, RecordingNotFoundError, SnippetNotFoundError
from app.services.score_tools.service import SnippetContent
from tests.score.helpers import grid, score
from tests.score_tools.midi_reader import parse_midi
from tests.score_tools.takes import steady, timings

import xml.etree.ElementTree as ET

OWNER = uuid.uuid4()
SNIPPET = uuid.UUID("0b7e5d3a-1f2c-4e6b-8a9d-3c4b5a6f7e81")
RECORDING = uuid.uuid4()
GRID = grid("the ^night is ^young")
TEXT = "The night is young"
TAKE = timings(steady(["the", "night", "is", "young"], [0, 1, 3, 4], [1, 2, 1, 4]))
STORED = score_to_json(check_score(score([[(0, 0, 12, 0), (1, 12, 24, 1), (2, 36, 12, 0), (3, 48, 48, 1)]]), GRID))


@pytest.fixture
def world(monkeypatch):
    state = SimpleNamespace(snippet=SnippetContent(SNIPPET, TEXT, GRID, STORED), timings=TAKE, calls=[])

    async def load_snippet(db, snippet_id, owner_id):
        state.calls.append(("snippet", snippet_id, owner_id))
        if snippet_id != SNIPPET or owner_id != OWNER or state.snippet is None:
            raise SnippetNotFoundError(f"snippet {snippet_id} not found")
        return state.snippet

    async def load_timings(db, recording_id, snippet_id, owner_id):
        state.calls.append(("recording", recording_id, snippet_id, owner_id))
        if recording_id != RECORDING:
            raise RecordingNotFoundError("no")
        if state.timings is None:
            raise RecordingHasNoTimingsError("old recording")
        return state.timings

    monkeypatch.setattr(service, "load_snippet", load_snippet)
    monkeypatch.setattr(service, "load_timings", load_timings)

    async def fake_db():
        yield None

    app.dependency_overrides[get_db] = fake_db
    app.dependency_overrides[get_current_user] = lambda: SimpleNamespace(id=OWNER)
    yield state
    app.dependency_overrides.clear()


@pytest.fixture
def client(world):
    return TestClient(app)


def draft(client, body, snippet=SNIPPET):
    return client.post(f"/api/snippets/{snippet}/score/draft", json=body)


GRID_BODY = {"source": "grid", "note": "1/8", "meter": {"num": 4, "den": 4}, "bpm": 90}


def test_grid_draft(client):
    response = draft(client, GRID_BODY)
    assert response.status_code == 200
    body = response.json()
    assert set(body) == {"score", "bpm_estimated", "unplaced", "unmatched_words"}
    assert body["bpm_estimated"] is None and body["unplaced"] == 0 and body["unmatched_words"] == 0
    assert body["score"]["snap"] == "1/8"
    assert [n["t"] for n in body["score"]["lines"][0]["notes"]] == [0, 24, 48, 72]
    check_score(body["score"], GRID)


def test_recording_draft_with_estimate(client, world):
    response = draft(client, {"source": "recording", "recording_id": str(RECORDING), "meter": {"num": 4, "den": 4}, "bpm": None})
    assert response.status_code == 200
    body = response.json()
    assert abs(body["bpm_estimated"] - 90) <= 1
    assert body["score"]["pickup"] == 12
    assert body["unplaced"] == 0 and body["unmatched_words"] == 0
    check_score(body["score"], GRID)
    assert ("recording", RECORDING, SNIPPET, OWNER) in world.calls


def test_recording_draft_bpm_defaults_to_null(client):
    response = draft(client, {"source": "recording", "recording_id": str(RECORDING), "meter": {"num": 3, "den": 4}})
    assert response.status_code == 200
    assert response.json()["bpm_estimated"] is not None


def test_recording_draft_with_tempo(client):
    response = draft(client, {"source": "recording", "recording_id": str(RECORDING), "meter": {"num": 4, "den": 4}, "bpm": 92.5})
    body = response.json()
    assert body["bpm_estimated"] is None and body["score"]["tempos"] == [{"bar": 0, "bpm": 92.5}]


def test_foreign_or_missing_snippet(client):
    response = draft(client, GRID_BODY, snippet=uuid.uuid4())
    assert response.status_code == 404
    assert response.json()["detail"]["error"] == "snippet_not_found"


def test_recording_not_found(client):
    response = draft(client, {"source": "recording", "recording_id": str(uuid.uuid4()), "meter": {"num": 4, "den": 4}})
    assert response.status_code == 404
    assert response.json()["detail"]["error"] == "recording_not_found"


def test_recording_without_timings(client, world):
    world.timings = None
    response = draft(client, {"source": "recording", "recording_id": str(RECORDING), "meter": {"num": 4, "den": 4}})
    assert response.status_code == 422
    assert response.json()["detail"] == {"error": "recording_has_no_timings", "message": "old recording"}


def test_draft_empty_for_a_take_that_matches_nothing(client, world):
    world.timings = timings(steady(["x", "y"], [0, 2], [1, 1]))
    response = draft(client, {"source": "recording", "recording_id": str(RECORDING), "meter": {"num": 4, "den": 4}})
    assert response.status_code == 422
    assert response.json()["detail"]["error"] == "draft_empty"


def test_draft_empty_for_a_grid_without_syllables(client, world):
    world.snippet = SnippetContent(SNIPPET, "", {"lines": []}, None)
    response = draft(client, GRID_BODY)
    assert response.status_code == 422
    assert response.json()["detail"]["error"] == "draft_empty"


@pytest.mark.parametrize(
    ("change", "reason"),
    [
        ({"meter": {"num": 4, "den": 3}}, "meter_invalid"),
        ({"meter": {"num": 33, "den": 4}}, "meter_invalid"),
        ({"bpm": 401}, "tempo_invalid"),
        ({"bpm": 90.123}, "tempo_invalid"),
        ({"note": "1/12"}, "snap_invalid"),
    ],
)
def test_invalid_arguments_are_invalid_score(client, change, reason):
    response = draft(client, {**GRID_BODY, **change})
    assert response.status_code == 422
    assert response.json()["detail"] == {"error": "invalid_score", "reason": reason}


def test_invalid_recording_bpm_is_invalid_score(client):
    response = draft(client, {"source": "recording", "recording_id": str(RECORDING), "meter": {"num": 4, "den": 4}, "bpm": 10})
    assert response.json()["detail"] == {"error": "invalid_score", "reason": "tempo_invalid"}


@pytest.mark.parametrize(
    "body",
    [
        {"source": "midi"},
        {"source": "grid", "meter": {"num": 4, "den": 4}, "bpm": 90},
        {"source": "recording", "recording_id": "nope", "meter": {"num": 4, "den": 4}},
        {**GRID_BODY, "extra": 1},
        {**GRID_BODY, "meter": {"num": 4, "den": 4, "bar": 0}},
    ],
)
def test_malformed_bodies_are_plain_validation_errors(client, body):
    response = draft(client, body)
    assert response.status_code == 422
    assert isinstance(response.json()["detail"], list)


def test_export_midi(client):
    response = client.get(f"/api/snippets/{SNIPPET}/score/export", params={"format": "midi"})
    assert response.status_code == 200
    assert response.headers["content-type"] == "audio/midi"
    assert response.headers["content-disposition"] == 'attachment; filename="the-night-is-young.mid"'
    assert response.headers["cache-control"] == "no-store"
    midi = parse_midi(response.content)
    assert [e.tick for t in midi.tracks for e in t if e.kind == "note_on"] == [0, 12, 36, 48]


def test_export_musicxml(client):
    response = client.get(f"/api/snippets/{SNIPPET}/score/export", params={"format": "musicxml"})
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("application/vnd.recordare.musicxml+xml")
    assert response.headers["content-disposition"] == 'attachment; filename="the-night-is-young.musicxml"'
    root = ET.fromstring(response.content)
    assert root.findtext("work/work-title") == "The night is young"


def test_export_without_score(client, world):
    world.snippet = SnippetContent(SNIPPET, TEXT, GRID, None)
    response = client.get(f"/api/snippets/{SNIPPET}/score/export", params={"format": "midi"})
    assert response.status_code == 404
    assert response.json()["detail"]["error"] == "score_not_found"


def test_export_of_a_foreign_snippet(client):
    response = client.get(f"/api/snippets/{uuid.uuid4()}/score/export", params={"format": "midi"})
    assert response.status_code == 404
    assert response.json()["detail"]["error"] == "snippet_not_found"


def test_export_stored_score_that_does_not_fit_the_grid(client, world):
    world.snippet = SnippetContent(SNIPPET, TEXT, grid("^one"), STORED)
    response = client.get(f"/api/snippets/{SNIPPET}/score/export", params={"format": "musicxml"})
    assert response.status_code == 500
    assert response.json()["detail"]["error"] == "stored_score_invalid"


@pytest.mark.parametrize("params", [{}, {"format": "pdf"}])
def test_export_format_is_required_and_checked(client, params):
    response = client.get(f"/api/snippets/{SNIPPET}/score/export", params=params)
    assert response.status_code == 422


def test_untitled_snippet_falls_back_to_its_id(client, world):
    world.snippet = SnippetContent(SNIPPET, "", GRID, STORED)
    response = client.get(f"/api/snippets/{SNIPPET}/score/export", params={"format": "midi"})
    assert response.headers["content-disposition"] == 'attachment; filename="snippet-0b7e5d3a.mid"'


def test_routes_need_authentication():
    plain = TestClient(app)
    assert plain.post(f"/api/snippets/{SNIPPET}/score/draft", json=GRID_BODY).status_code in (401, 403)
    assert plain.get(f"/api/snippets/{SNIPPET}/score/export", params={"format": "midi"}).status_code in (401, 403)
