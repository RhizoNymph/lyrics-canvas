"""plan apply: --yes gate, re-check, request order and payloads, $refs, stop on error, conflicts."""

from __future__ import annotations

from typing import Any

from conftest import ALBUM, DRAFTS, S1, S2, S3, S4, T_DRAFT, T_HOOK, W1, W2, Env

from lcxlib.rank import n_keys_between


def apply(lc: Env, ops: list[dict[str, Any]], *flags: str) -> tuple[int, dict[str, Any]]:
    r = lc.run("plan", "apply", lc.plan_file(ops), "--json", *flags)
    return r.code, r.json()


def writes(lc: Env) -> list[tuple[str, str]]:
    return [(r.method, r.path) for r in lc.backend.writes()]


def test_apply_without_yes_only_shows_the_diff(lc: Env) -> None:
    ops = [{"op": "create_tag", "name": "Night"}, {"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "Album"}]
    r = lc.run("plan", "apply", lc.plan_file(ops))
    assert r.code == 0
    assert '  1. * create tag "Night"' in r.out
    assert "Not applied: show this to the user and re-run with --yes once they approve." in r.out
    assert lc.backend.writes() == []
    code, data = apply(lc, ops)
    assert code == 0 and data["applied"] is False and lc.backend.writes() == []


def test_apply_refuses_a_plan_with_problems(lc: Env) -> None:
    ops = [{"op": "create_tag", "name": "Night"}, {"op": "remove_from", "snippet_id": S1, "canvas": "Drafts"}]
    code, data = apply(lc, ops, "--yes")
    assert code == 1 and data["applied"] is False
    assert [i["code"] for i in data["check"]["issues"]] == ["last_canvas"]
    assert lc.backend.writes() == []


def test_apply_runs_ops_in_order_with_refs_and_new_tags(lc: Env) -> None:
    ops = [
        {"op": "create_tag", "name": "Night"},
        {"op": "create_canvas", "title": "Night songs", "ref": "$n"},
        {"op": "tag_snippet", "snippet_id": S1, "add": ["night"], "remove": ["DRAFT"]},
        {"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "$n"},
        {"op": "show_on", "snippet_id": S4, "canvas": "Night songs"},
        {"op": "rename_canvas", "canvas": "$n", "title": "Nocturnes"},
        {"op": "tag_word", "word_id": W2, "add": ["Night", "hook"]},
        {"op": "add_bank_word", "text": "glow", "tags": ["night"]},
        {"op": "tag_snippet", "snippet_id": S2, "add": ["draft"], "remove": []},  # already? no: S2 has no tags
    ]
    code, data = apply(lc, ops, "--yes")
    assert code == 0, data
    b = lc.backend
    night_tag = next(t["id"] for t in b.tags if t["name"] == "Night")
    new_canvas = next(c for c in b.canvases if c["title"] == "Nocturnes")
    assert writes(lc) == [
        ("POST", "/api/tags"),
        ("POST", "/api/canvases"),
        ("POST", f"/api/snippets/{S1}/tags"),
        ("DELETE", f"/api/snippets/{S1}/tags/{T_DRAFT}"),
        ("POST", f"/api/canvases/{DRAFTS}/snippets/transfer"),
        ("POST", f"/api/canvases/{ALBUM}/snippets/transfer"),
        ("PUT", f"/api/canvases/{new_canvas['id']}"),
        ("POST", "/api/word-bank/tags"),
        ("POST", "/api/word-bank/tags"),
        ("POST", "/api/word-bank"),
        ("POST", f"/api/snippets/{S2}/tags"),
    ]
    w = b.writes()
    assert w[0].body == {"name": "Night"}
    assert w[1].body == {"title": "Night songs"}
    assert w[2].query == {"tag_id": night_tag}
    assert w[4].body == {"snippet_ids": [S1], "target_canvas_id": new_canvas["id"], "mode": "move"}
    assert w[5].body == {"snippet_ids": [S4], "target_canvas_id": new_canvas["id"], "mode": "show"}
    assert w[6].body == {"title": "Nocturnes"}
    assert w[7].body == {"word_ids": [W2], "tag_id": night_tag, "op": "link"}
    assert w[8].body == {"word_ids": [W2], "tag_id": T_HOOK, "op": "link"}
    assert w[9].body == {"words": ["glow"], "tag_ids": [night_tag]}
    assert b.snippets[S1]["tag_ids"] == [night_tag]
    assert {p["canvas_id"] for p in b.placements if p["snippet_id"] == S1} == {new_canvas["id"]}
    assert [d["index"] for d in data["apply"]["done"]] == list(range(1, 10))
    assert data["apply"]["failed"] is None and data["apply"]["not_done"] == []


def test_noop_steps_are_skipped_not_sent(lc: Env) -> None:
    ops = [
        {"op": "create_tag", "name": "hook"},
        {"op": "tag_snippet", "snippet_id": S1, "add": ["draft"]},
        {"op": "show_on", "snippet_id": S3, "canvas": "Album"},
        {"op": "tag_word", "word_id": W1, "add": ["hook"], "remove": ["draft"]},
    ]
    code, data = apply(lc, ops, "--yes")
    assert code == 0
    assert [s["index"] for s in data["apply"]["skipped"]] == [1, 2, 3]
    assert writes(lc) == [("POST", "/api/word-bank/tags"), ("POST", "/api/word-bank/tags")]
    assert [w.body["op"] for w in lc.backend.writes()] == ["link", "unlink"]


def test_stop_on_first_error_reports_done_failed_and_not_done(lc: Env) -> None:
    lc.backend.fail_next("POST", f"/api/canvases/{ALBUM}/snippets/transfer", 404,
                         {"error": "canvas_not_found", "message": "target canvas is not yours"})
    ops = [
        {"op": "create_tag", "name": "Night"},
        {"op": "move", "snippet_id": S4, "from_canvas": "Album", "to_canvas": "Drafts"},
        {"op": "tag_snippet", "snippet_id": S2, "add": ["Night"]},
        {"op": "create_tag", "name": "hook"},  # no-op: not listed as not done
        {"op": "rename_canvas", "canvas": "Drafts", "title": "Sketches"},
    ]
    r = lc.run("plan", "apply", lc.plan_file(ops), "--yes")
    assert r.code == 4
    assert "Done (1):" in r.out and '  1. create tag "Night"' in r.out
    assert "FAILED at op 2: move snippet" in r.out
    assert "404 canvas_not_found: target canvas is not yours" in r.out
    assert "Not done (2):" in r.out
    assert writes(lc) == [("POST", "/api/tags"), ("POST", f"/api/canvases/{ALBUM}/snippets/transfer")]
    lc.backend.requests.clear()
    code, data = apply(lc, ops, "--yes")  # re-running finishes the rest; the created tag is now a no-op
    assert code == 0
    assert [s["index"] for s in data["apply"]["skipped"]] == [1, 4]
    assert writes(lc)[0] == ("POST", f"/api/canvases/{ALBUM}/snippets/transfer")


def test_stop_on_error_json(lc: Env) -> None:
    lc.backend.fail_next("POST", "/api/canvases", 422, {"error": "invalid_canvas_title", "message": "bad title"})
    code, data = apply(lc, [{"op": "create_canvas", "title": "X", "ref": "$x"}, {"op": "show_on", "snippet_id": S1, "canvas": "$x"}], "--yes")
    assert code == 4
    failed = data["apply"]["failed"]
    assert failed["index"] == 1
    assert failed["error"] == {"method": "POST", "path": "/api/canvases", "status": 422, "code": "invalid_canvas_title", "message": "bad title"}
    assert [s["index"] for s in data["apply"]["not_done"]] == [2]
    assert writes(lc) == [("POST", "/api/canvases")]


def test_pydantic_422_is_reported(lc: Env) -> None:
    lc.backend.fail_next("PUT", f"/api/snippets/{S1}", 422,
                         [{"loc": ["body", "position_x"], "msg": "Input should be a valid number", "type": "x"}])
    code, data = apply(lc, [{"op": "set_layout", "snippet_id": S1, "canvas": "Drafts", "position_x": 5}], "--yes")
    assert code == 4
    assert data["apply"]["failed"]["error"]["code"] == "validation_error"
    assert "body.position_x: Input should be a valid number" in data["apply"]["failed"]["error"]["message"]


def test_edit_text_sends_base_version_and_reports_server_conflict(lc: Env) -> None:
    lc.backend.fail_next("PUT", f"/api/snippets/{S1}", 409, {"error": "version_conflict", "message": "changed elsewhere", "current": {}})
    ops = [{"op": "edit_text", "snippet_id": S1, "raw_text": "new text", "base_version": 3},
           {"op": "create_tag", "name": "after"}]
    r = lc.run("plan", "apply", lc.plan_file(ops), "--yes")
    assert r.code == 4
    assert "409 version_conflict: changed elsewhere" in r.out and "re-read and rebuild this edit" in r.out
    puts = [w for w in lc.backend.writes() if w.method == "PUT"]
    assert len(puts) == 1  # never retried or forced
    assert puts[0].body == {"raw_text": "new text", "base_version": 3} and puts[0].query == {"canvas_id": DRAFTS}
    assert lc.backend.snippets[S1]["raw_text"].startswith("Midnight")
    assert writes(lc) == [("PUT", f"/api/snippets/{S1}")]


def test_edit_text_stale_at_check_writes_nothing(lc: Env) -> None:
    ops = [{"op": "create_tag", "name": "x"}, {"op": "edit_text", "snippet_id": S1, "raw_text": "t", "base_version": 2}]
    code, data = apply(lc, ops, "--yes")
    assert code == 1 and [i["code"] for i in data["check"]["issues"]] == ["version_conflict"]
    assert lc.backend.writes() == []


def test_edit_text_after_own_tag_change_uses_the_new_version(lc: Env) -> None:
    ops = [{"op": "tag_snippet", "snippet_id": S1, "add": ["hook"]},
           {"op": "edit_text", "snippet_id": S1, "raw_text": "new text", "base_version": 3}]
    code, data = apply(lc, ops, "--yes")
    assert code == 0, data
    put = next(w for w in lc.backend.writes() if w.method == "PUT")
    assert put.body["base_version"] == 4
    assert lc.backend.snippets[S1]["raw_text"] == "new text" and lc.backend.snippets[S1]["version"] == 5
    assert data["apply"]["done"][1]["result"] == {"version": 5}


def test_edit_text_on_a_snippet_moved_to_a_new_canvas(lc: Env) -> None:
    ops = [{"op": "create_canvas", "title": "N", "ref": "$n"},
           {"op": "move", "snippet_id": S2, "from_canvas": "Drafts", "to_canvas": "$n"},
           {"op": "edit_text", "snippet_id": S2, "raw_text": "moved and edited", "base_version": 1}]
    code, _ = apply(lc, ops, "--yes")
    assert code == 0
    new_id = next(c["id"] for c in lc.backend.canvases if c["title"] == "N")
    assert lc.backend.writes()[-1].query == {"canvas_id": new_id}


def test_reorder_list_sends_one_batch_with_fresh_ranks(lc: Env) -> None:
    code, data = apply(lc, [{"op": "reorder_list", "canvas": "Drafts", "snippet_ids": [S3, S1]}], "--yes")
    assert code == 0, data
    (patch,) = lc.backend.writes()
    assert (patch.method, patch.path) == ("PATCH", "/api/snippets/batch-position")
    keys = n_keys_between(None, None, 3)
    assert patch.body == {"canvas_id": DRAFTS, "updates": [{"id": S3, "list_rank": keys[0]}, {"id": S1, "list_rank": keys[1]},
                                                           {"id": S2, "list_rank": keys[2]}]}
    assert lc.run("canvas", "Drafts").json()["snippets"][0]["id"] == S3


def test_reorder_after_move_reads_the_live_canvas(lc: Env) -> None:
    ops = [{"op": "move", "snippet_id": S2, "from_canvas": "Drafts", "to_canvas": "Album"},
           {"op": "reorder_list", "canvas": "Album", "snippet_ids": [S4, S2, S3]}]
    code, _ = apply(lc, ops, "--yes")
    assert code == 0
    assert [s["id"] for s in lc.run("canvas", "Album").json()["snippets"]] == [S4, S2, S3]


def test_reorder_partial_update_is_an_error(lc: Env) -> None:
    """A 200 that updated fewer placements than sent (e.g. one left the canvas meanwhile)."""
    original = lc.backend.handle

    def handle(method: str, path: str, query: dict[str, str], body: Any) -> tuple[int, Any]:
        if method == "PATCH":
            lc.backend.requests.append(type(lc.backend.requests[0])(method, path, query, body))
            return 200, {"updated": 1}
        return original(method, path, query, body)

    lc.backend.handle = handle  # type: ignore[method-assign]
    code, data = apply(lc, [{"op": "reorder_list", "canvas": "Drafts", "snippet_ids": [S3]}], "--yes")
    assert code == 4
    assert data["apply"]["failed"]["error"]["code"] == "partial_update"


def test_set_layout_sends_only_given_fields(lc: Env) -> None:
    code, _ = apply(lc, [{"op": "set_layout", "snippet_id": S3, "canvas": "Album", "position_x": 40, "list_rank": "Zz"}], "--yes")
    assert code == 0
    (put,) = lc.backend.writes()
    assert put.path == f"/api/snippets/{S3}" and put.query == {"canvas_id": ALBUM}
    assert put.body == {"position_x": 40, "list_rank": "Zz"}


def test_remove_from_one_of_several_canvases(lc: Env) -> None:
    code, _ = apply(lc, [{"op": "remove_from", "snippet_id": S3, "canvas": "Album"}], "--yes")
    assert code == 0
    assert writes(lc) == [("DELETE", f"/api/canvases/{ALBUM}/snippets/{S3}")]
    assert S3 in lc.backend.snippets


def test_apply_rechecks_against_fresh_data(lc: Env) -> None:
    ops = [{"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "Album"}]
    assert lc.run("plan", "check", lc.plan_file(ops)).code == 0
    # someone moves it in the web app before apply
    p = next(p for p in lc.backend.placements if p["snippet_id"] == S1)
    p["canvas_id"] = ALBUM
    code, data = apply(lc, ops, "--yes")
    assert code == 0
    assert data["apply"]["skipped"][0]["no_change"].startswith("already on")
    assert lc.backend.writes() == []


def test_plan_from_stdin(lc: Env, monkeypatch: Any) -> None:
    import io
    import json

    monkeypatch.setattr("sys.stdin", io.StringIO(json.dumps({"version": 1, "ops": [{"op": "create_tag", "name": "z"}]})))
    r = lc.run("plan", "check", "-")
    assert r.code == 0 and 'create tag "z"' in r.out
