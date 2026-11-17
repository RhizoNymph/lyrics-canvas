"""Snippet score over HTTP against Postgres (docs/features/score_backend.md): create, update,
remove, the view flag, the save flow on text and grid edits, versions, the snapshot, fork, merge,
also-show, client-id retries and the voice-take append."""

from __future__ import annotations

import copy
import uuid

import pytest
from sqlalchemy import select

from app.models.recording import SplitMode
from app.models.snippet import Snippet
from app.services import recording_service
from app.services.recording_service import NewRecording
from app.services.nlp.syllabifier import syllabify_text
from tests.placements.db import make_user
from tests.sync.helpers import canvas, conflict, new_canvas, new_snippet, ok, put_snippet, snippet_on
from tests.tags.db import requires_db

pytestmark = requires_db


def score(*lines: list[tuple[int, int, int]], **top) -> dict:
    body = {
        "v": 1, "ppq": 48, "pickup": 0, "meters": [{"bar": 0, "num": 4, "den": 4}],
        "tempos": [{"bar": 0, "bpm": 90}], "snap": "1/16",
        "lines": [{"notes": [{"s": s, "t": t, "d": d, "a": 0} for s, t, d in line]} for line in lines],
    }
    body.update(top)
    return body


EVEN = score([(0, 0, 24), (1, 24, 24), (2, 48, 24)])  # "I go now", no gaps
GAP = score([(0, 0, 24), (1, 72, 24), (2, 96, 24)])  # a beat of rest after "I"


def rests(snippet: dict, line: int = 0) -> list[bool]:
    return [c.get("rest") is True for c in snippet["grid_data"]["lines"][line]["cells"]]


def note_tuples(snippet: dict, line: int = 0) -> list[tuple[int, int, int]]:
    return [(n["s"], n["t"], n["d"]) for n in snippet["score"]["lines"][line]["notes"]]


@pytest.fixture
async def me(db, api):
    user = await make_user(db)
    await db.commit()
    me = api(user)
    me.user_id = user
    return me


@pytest.fixture
async def song(me) -> str:
    return (await new_canvas(me))["id"]


# ---- create ----


async def test_create_without_score_has_none_and_grid_view(me, song):
    s = await new_snippet(me, song, "I go now")
    assert s["score"] is None and s["score_view"] is False


async def test_create_with_view_but_no_score_stores_grid_view(me, song):
    s = await new_snippet(me, song, "I go now", score_view=True)
    assert s["score"] is None and s["score_view"] is False


async def test_create_with_score_links_rests_and_text(me, song):
    s = await new_snippet(me, song, "I go now", score=GAP, score_view=True)
    assert s["score"] == GAP and s["score_view"] is True
    assert rests(s) == [False, True, False, False]
    assert s["raw_text"] == "i _ go now"
    assert s["version"] == 1
    # the card size follows the linked grid (one more cell)
    plain = await new_snippet(me, song, "I go now")
    assert s["width"] > plain["width"]
    assert s == await snippet_on(me, song, s["id"])


async def test_create_canonicalizes_the_score(me, song):
    raw = score([(0, 0, 24)])
    raw["lines"][0]["notes"][0]["d"] = 24.0
    raw["lines"][0]["notes"][0]["pitch"] = 60
    s = await new_snippet(me, song, "I go now", score=raw)
    assert s["score"]["lines"][0]["notes"] == [{"s": 0, "t": 0, "d": 24, "a": 0, "pitch": 60}]
    assert list(s["score"]) == ["v", "ppq", "pickup", "meters", "tempos", "snap", "lines"]


async def test_create_with_invalid_score_is_422_and_creates_nothing(me, song):
    res = await me.post(f"/api/canvases/{song}/snippets", json={"raw_text": "I go now", "score": score([(0, 0, 24)], [])})
    assert res.status_code == 422
    assert res.json() == {"detail": {"error": "invalid_score", "reason": "line_count_mismatch"}}
    assert (await canvas(me, song))["snippets"] == []


async def test_create_overlap_reports_line_and_note(me, song):
    res = await me.post(
        f"/api/canvases/{song}/snippets", json={"raw_text": "I go now", "score": score([(0, 0, 24), (1, 12, 24)])}
    )
    assert res.status_code == 422
    assert res.json()["detail"] == {"error": "invalid_score", "reason": "notes_overlap", "line": 0, "note": 1}


@pytest.mark.parametrize("bad", [[], "score", 3, True, {"v": 1}])
async def test_create_non_score_values_are_malformed(me, song, bad):
    res = await me.post(f"/api/canvases/{song}/snippets", json={"raw_text": "I go now", "score": bad})
    assert res.status_code == 422
    assert res.json()["detail"]["reason"] == "malformed"


async def test_create_with_sent_grid_validates_against_it(me, song):
    grid = syllabify_text("I _ go now")
    s = await new_snippet(me, song, "I _ go now", grid_data=grid, score=EVEN)
    # the score has no gap after "I": the rest is linked away
    assert rests(s) == [False, False, False] and s["raw_text"] == "i go now"


async def test_create_with_client_id_retry_returns_the_same_snippet(me, song):
    sid = str(uuid.uuid4())
    body = {"id": sid, "raw_text": "I go now", "score": GAP, "score_view": True}
    first = ok(await me.post(f"/api/canvases/{song}/snippets", json=body), 201)
    again = ok(await me.post(f"/api/canvases/{song}/snippets", json=body), 201)
    assert again["id"] == first["id"] == sid and again["score"] == GAP
    other = await me.post(f"/api/canvases/{song}/snippets", json={**body, "score": EVEN})
    conflict(other, "id_conflict")


# ---- update: set / remove / view ----


async def test_put_score_sets_it_and_bumps_version_and_content_clock(me, song):
    s = await new_snippet(me, song, "I go now")
    out = ok(await put_snippet(me, s["id"], song, score=GAP))
    assert out["score"] == GAP and out["version"] == 2
    assert out["content_updated_at"] != s["content_updated_at"]
    assert rests(out) == [False, True, False, False] and out["raw_text"] == "i _ go now"
    assert out["score_view"] is False


async def test_put_same_score_again_does_not_bump(me, song):
    s = await new_snippet(me, song, "I go now", score=GAP)
    out = ok(await put_snippet(me, s["id"], song, score=GAP))
    assert out["version"] == 1 and out["content_updated_at"] == s["content_updated_at"]


async def test_put_null_removes_score_and_view_and_keeps_text(me, song):
    s = await new_snippet(me, song, "I go now", score=GAP, score_view=True)
    out = ok(await put_snippet(me, s["id"], song, score=None))
    assert out["score"] is None and out["score_view"] is False and out["version"] == 2
    assert out["raw_text"] == "i _ go now" and rests(out) == [False, True, False, False]
    again = ok(await put_snippet(me, s["id"], song, score=None))
    assert again["version"] == 2


async def test_put_view_toggles_and_bumps(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN)
    on = ok(await put_snippet(me, s["id"], song, score_view=True))
    assert on["score_view"] is True and on["version"] == 2 and on["score"] == EVEN
    off = ok(await put_snippet(me, s["id"], song, score_view=False))
    assert off["score_view"] is False and off["version"] == 3


async def test_put_view_without_score_stays_false_and_does_not_bump(me, song):
    s = await new_snippet(me, song, "I go now")
    out = ok(await put_snippet(me, s["id"], song, score_view=True))
    assert out["score_view"] is False and out["version"] == 1


async def test_put_view_null_means_not_sent(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN, score_view=True)
    out = ok(await put_snippet(me, s["id"], song, score_view=None, position_x=5.0))
    assert out["score_view"] is True and out["version"] == 1


async def test_put_invalid_score_is_422_and_changes_nothing(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN)
    res = await put_snippet(me, s["id"], song, score=score([(5, 0, 24)]), position_x=50.0)
    assert res.status_code == 422
    assert res.json()["detail"] == {"error": "invalid_score", "reason": "syllable_out_of_range", "line": 0, "note": 0}
    assert await snippet_on(me, song, s["id"]) == s


async def test_put_score_with_text_is_validated_against_the_new_grid(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN)
    out = ok(await put_snippet(me, s["id"], song, raw_text="I go", score=score([(0, 0, 24), (1, 24, 24)])))
    assert note_tuples(out) == [(0, 0, 24), (1, 24, 24)]
    res = await put_snippet(me, s["id"], song, raw_text="I", score=score([(0, 0, 24), (1, 24, 24)]))
    assert res.status_code == 422 and res.json()["detail"]["reason"] == "syllable_out_of_range"


async def test_layout_only_put_leaves_score_alone(me, song):
    s = await new_snippet(me, song, "I go now", score=GAP, score_view=True)
    out = ok(await put_snippet(me, s["id"], song, position_x=10.0, z_index=3))
    assert out["score"] == GAP and out["score_view"] is True and out["version"] == 1


# ---- update: the stored score follows text and grid edits ----


async def test_text_edit_reconciles_the_score(me, song):
    s = await new_snippet(me, song, "hello world", score=score([(0, 0, 24), (1, 24, 24), (2, 48, 24)]))
    out = ok(await put_snippet(me, s["id"], song, raw_text="hello big world"))
    assert note_tuples(out) == [(0, 0, 24), (1, 24, 24), (3, 48, 24)]
    assert out["raw_text"] == "hello big world" and out["version"] == 2


async def test_text_edit_keeps_the_rests_the_score_needs(me, song):
    s = await new_snippet(me, song, "I go now", score=GAP)
    out = ok(await put_snippet(me, s["id"], song, raw_text="I go now there"))
    # the rest between I and go comes back from the score; the new word is unplaced
    assert rests(out) == [False, True, False, False, False]
    assert out["raw_text"] == "i _ go now there"
    assert note_tuples(out) == [(0, 0, 24), (1, 72, 24), (2, 96, 24)]


async def test_text_edit_adding_a_line_gives_it_no_notes(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN)
    out = ok(await put_snippet(me, s["id"], song, raw_text="I go now\nhello world"))
    assert len(out["score"]["lines"]) == 2 and out["score"]["lines"][1]["notes"] == []
    assert note_tuples(out) == [(0, 0, 24), (1, 24, 24), (2, 48, 24)]


async def test_text_rest_added_is_a_rest_only_edit_and_moves_notes(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN)
    out = ok(await put_snippet(me, s["id"], song, raw_text="I go _ _ now"))
    assert note_tuples(out) == [(0, 0, 24), (1, 24, 24), (2, 144, 24)]
    assert out["raw_text"] == "I go _ _ now"


async def test_grid_rest_insert_moves_later_notes(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN)
    grid = s["grid_data"]
    cells = grid["lines"][0]["cells"]
    grid["lines"][0]["cells"] = [cells[0], cells[1], {"rest": True}, {"rest": True}, cells[2]]
    grid["lines"][0]["raw_text"] = "I go _ _ now"
    # clients send the rebuilt text with a grid edit (the server never rebuilds text from a sent grid)
    out = ok(await put_snippet(me, s["id"], song, grid_data=grid, raw_text="I go _ _ now"))
    assert note_tuples(out) == [(0, 0, 24), (1, 24, 24), (2, 144, 24)]
    assert rests(out) == [False, False, True, True, False]
    assert out["raw_text"] == "I go _ _ now" and out["grid_data"] == grid
    assert out["version"] == 2


async def test_grid_only_rest_edit_keeps_the_text(me, song):
    s = await new_snippet(me, song, "I go now", score=EVEN)
    grid = copy.deepcopy(s["grid_data"])
    cells = grid["lines"][0]["cells"]
    grid["lines"][0]["cells"] = [cells[0], cells[1], {"rest": True}, cells[2]]
    out = ok(await put_snippet(me, s["id"], song, grid_data=grid))
    assert note_tuples(out) == [(0, 0, 24), (1, 24, 24), (2, 96, 24)]
    assert out["raw_text"] == "I go now"


async def test_grid_rest_removal_is_clamped(me, song):
    s = await new_snippet(me, song, "I _ _ go now", score=score([(0, 0, 24), (1, 48, 24), (2, 72, 24)]))
    # the score's gap is only half a beat: the link already removed one of the two rests
    assert rests(s) == [False, False, False]
    s2 = await new_snippet(me, song, "I _ _ go now", score=score([(0, 0, 24), (1, 120, 24), (2, 144, 24)]))
    assert rests(s2) == [False, True, True, False, False]
    grid = s2["grid_data"]
    cells = grid["lines"][0]["cells"]
    grid["lines"][0]["cells"] = [cells[0], cells[3], cells[4]]
    out = ok(await put_snippet(me, s2["id"], song, grid_data=grid, raw_text="i go now"))
    assert note_tuples(out) == [(0, 0, 24), (1, 24, 24), (2, 48, 24)]
    assert out["raw_text"] == "i go now"


async def test_grid_rest_edit_past_max_ticks_is_422(me, song):
    end = 393216 - 24
    s = await new_snippet(me, song, "I go", score=score([(0, end - 24, 24), (1, end, 24)]))
    grid = copy.deepcopy(s["grid_data"])
    cells = grid["lines"][0]["cells"]
    grid["lines"][0]["cells"] = [cells[0], {"rest": True}, cells[1]]
    res = await put_snippet(me, s["id"], song, grid_data=grid)
    assert res.status_code == 422
    assert res.json()["detail"] == {"error": "invalid_score", "reason": "exceeds_max_ticks", "line": 0, "note": 1}
    assert await snippet_on(me, song, s["id"]) == s


async def test_grid_text_mismatch_is_422(me, song):
    s = await new_snippet(me, song, "I go now")
    grid = syllabify_text("I go now\nhello world")
    res = await put_snippet(me, s["id"], song, grid_data=grid, score=score([], [(1, 0, 24), (2, 96, 24)]))
    assert res.status_code == 422
    assert res.json()["detail"]["error"] == "score_text_mismatch"


async def test_phonetics_follow_the_linked_text(me, song):
    s = await new_snippet(me, song, "I go now")
    out = ok(await put_snippet(me, s["id"], song, score=GAP))
    reference = await new_snippet(me, song, "i _ go now")
    assert out["phonetics"] == reference["phonetics"]


# ---- versions ----


async def test_stale_base_version_on_score_put_is_409(me, song):
    s = await new_snippet(me, song, "I go now")
    ok(await put_snippet(me, s["id"], song, raw_text="I go now there"))
    detail = conflict(await put_snippet(me, s["id"], song, score=None, base_version=1))
    assert detail["current"]["version"] == 2
    detail = conflict(await put_snippet(me, s["id"], song, score_view=True, base_version=1))
    out = ok(await put_snippet(me, s["id"], song, score=score([(0, 0, 24)]), base_version=2))
    assert out["version"] == 3


# ---- reads: canvas detail, snapshot ----


async def test_snapshot_carries_score_and_view(me, song):
    s = await new_snippet(me, song, "I go now", score=GAP, score_view=True)
    plain = await new_snippet(me, song, "hello")
    snap = ok(await me.get("/api/sync/snapshot"))
    by_id = {x["id"]: x for x in snap["snippets"]}
    assert by_id[s["id"]]["score"] == GAP and by_id[s["id"]]["score_view"] is True
    assert by_id[plain["id"]]["score"] is None and by_id[plain["id"]]["score_view"] is False


async def test_stored_score_is_sql_null_without_a_score(me, song, db):
    s = await new_snippet(me, song, "I go now", score=GAP)
    ok(await put_snippet(me, s["id"], song, score=None))
    from sqlalchemy import text

    row = (await db.execute(text("SELECT score IS NULL FROM snippets WHERE id = :i"), {"i": s["id"]})).scalar_one()
    assert row is True


# ---- fork, merge, also-show ----


async def test_fork_copies_score_and_view(api, db, me, song):
    s = await new_snippet(me, song, "I go now", score=GAP, score_view=True)
    ok(await me.post(f"/api/canvases/{song}/share"))
    other = await make_user(db)
    await db.commit()
    them = api(other)
    forked = ok(await them.post(f"/api/canvases/{song}/fork"))
    copy = (await canvas(them, forked["id"]))["snippets"][0]
    assert copy["id"] != s["id"]
    assert copy["score"] == GAP and copy["score_view"] is True and copy["raw_text"] == s["raw_text"]


async def test_merge_drops_scores(me, song):
    a = await new_snippet(me, song, "I go now", score=GAP, score_view=True)
    b = await new_snippet(me, song, "hello world", score=score([(0, 0, 24)]))
    merged = ok(await me.post(f"/api/canvases/{song}/merge", json={"snippet_ids": [a["id"], b["id"]], "mode": "vertical"}))
    assert merged["snippet"]["score"] is None and merged["snippet"]["score_view"] is False


async def test_also_show_shares_the_score_and_edits_reach_both(me, song):
    other = (await new_canvas(me, "drafts"))["id"]
    s = await new_snippet(me, song, "I go now", score=EVEN)
    ok(await me.post(f"/api/canvases/{song}/snippets/transfer", json={"snippet_ids": [s["id"]], "target_canvas_id": other, "mode": "show"}))
    ok(await put_snippet(me, s["id"], song, score=GAP, score_view=True))
    there = await snippet_on(me, other, s["id"])
    assert there["score"] == GAP and there["score_view"] is True and there["raw_text"] == "i _ go now"


async def test_text_export_is_unaffected_by_scores(me, song):
    await new_snippet(me, song, "I go now", score=GAP)
    res = await me.post(f"/api/canvases/{song}/export", json={"format": "text"})
    assert res.status_code == 200
    assert "go now" in res.text


# ---- voice-take append ----


async def test_voice_append_reconciles_the_score(me, song, db):
    s = await new_snippet(me, song, "I go now", score=GAP, score_view=True)
    take = NewRecording(mime_type="audio/ogg", duration_ms=10, audio=b"\x00", transcript="hello world", split_mode=SplitMode.taps)
    placed, _ = await recording_service.append_with_recording(
        db, uuid.UUID(song), me.user_id, uuid.UUID(s["id"]), lines=["hello world"], recording=take
    )
    stored = (await db.execute(select(Snippet).where(Snippet.id == uuid.UUID(s["id"])).execution_options(populate_existing=True))).scalar_one()
    assert stored.raw_text == "i _ go now\nhello world"
    assert stored.score["lines"][0] == GAP["lines"][0] and stored.score["lines"][1] == {"notes": []}
    assert stored.score_view is True and stored.version == 2
