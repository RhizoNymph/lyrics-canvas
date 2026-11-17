"""plan check: resolution (ids, titles, $refs, tag names), simulation, diff rendering, no writes."""

from __future__ import annotations

from typing import Any

from conftest import ALBUM, DRAFTS, DUP1, S1, S2, S3, S4, S5, W1, W2, Env


def check(lc: Env, *ops: dict[str, Any]) -> dict[str, Any]:
    r = lc.run("plan", "check", lc.plan_file(list(ops)), "--json")
    assert r.code in (0, 1), r.err
    data = r.json()["check"]
    assert (r.code == 0) == data["ok"]
    return data


def codes(data: dict[str, Any]) -> list[tuple[int, str]]:
    return [(i["index"], i["code"]) for i in data["issues"]]


def test_check_writes_nothing(lc: Env) -> None:
    ops = [{"op": "create_tag", "name": "new"}, {"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "Album"}]
    r = lc.run("plan", "check", lc.plan_file(ops))
    assert r.code == 0, r.err + r.out
    assert lc.backend.writes() == []
    assert [q.path for q in lc.backend.requests] == ["/api/sync/snapshot"]


def test_canvas_resolution_by_id_title_and_ref(lc: Env) -> None:
    data = check(
        lc,
        {"op": "show_on", "snippet_id": S2, "canvas": ALBUM},
        {"op": "show_on", "snippet_id": S1, "canvas": "Album"},
        {"op": "create_canvas", "title": "Night songs", "ref": "$night"},
        {"op": "show_on", "snippet_id": S4, "canvas": "$night"},
        {"op": "show_on", "snippet_id": S5, "canvas": "Night songs"},
    )
    assert data["ok"], data["issues"]
    assert all(s["change"] for s in data["steps"])
    assert '"Night songs" ($night)' in data["steps"][3]["summary"]


def test_canvas_resolution_errors(lc: Env) -> None:
    data = check(
        lc,
        {"op": "show_on", "snippet_id": S1, "canvas": "Nope"},
        {"op": "show_on", "snippet_id": S1, "canvas": "Dup"},
        {"op": "show_on", "snippet_id": S1, "canvas": "$later"},
        {"op": "create_canvas", "title": "Later", "ref": "$later"},
        {"op": "create_canvas", "title": "Again", "ref": "$later"},
        {"op": "show_on", "snippet_id": "00000000-0000-0000-0000-00000000dead", "canvas": "Album"},
    )
    assert codes(data) == [(1, "unknown_canvas"), (2, "ambiguous_canvas"), (3, "unknown_ref"), (5, "duplicate_ref"),
                           (6, "unknown_snippet")]


def test_renamed_canvas_resolves_by_new_title(lc: Env) -> None:
    data = check(
        lc,
        {"op": "rename_canvas", "canvas": "Drafts", "title": "Sketches"},
        {"op": "show_on", "snippet_id": S4, "canvas": "Sketches"},
        {"op": "show_on", "snippet_id": S4, "canvas": "Drafts"},
    )
    assert codes(data) == [(3, "unknown_canvas")]


def test_create_canvas_with_existing_title_reuses_it(lc: Env) -> None:
    data = check(lc, {"op": "create_canvas", "title": "Album", "ref": "$a"}, {"op": "show_on", "snippet_id": S1, "canvas": "$a"})
    assert data["ok"]
    assert data["steps"][0]["change"] is False and ALBUM in data["steps"][0]["no_change"]
    assert data["steps"][1]["change"] is True
    dup = check(lc, {"op": "create_canvas", "title": "Dup", "ref": "$d"})
    assert codes(dup) == [(1, "ambiguous_canvas")]


def test_tag_names_resolve_case_insensitively_and_from_plan(lc: Env) -> None:
    data = check(
        lc,
        {"op": "tag_snippet", "snippet_id": S2, "add": ["HOOK", "Draft"]},
        {"op": "create_tag", "name": "Night"},
        {"op": "tag_snippet", "snippet_id": S2, "add": ["night"]},
        {"op": "tag_snippet", "snippet_id": S2, "add": ["missing"]},
        {"op": "create_tag", "name": "hook"},
    )
    assert codes(data) == [(4, "unknown_tag")]
    assert data["steps"][0]["summary"].endswith("+Hook +draft")
    assert data["steps"][2]["summary"].endswith("+Night")
    assert data["steps"][3] == {**data["steps"][3], "change": False}  # create_tag "hook" exists as "Hook"


def test_tag_snippet_noops_and_conflicts(lc: Env) -> None:
    data = check(
        lc,
        {"op": "tag_snippet", "snippet_id": S1, "add": ["draft"]},
        {"op": "tag_snippet", "snippet_id": S1, "remove": ["hook"]},
        {"op": "tag_snippet", "snippet_id": S1, "add": ["hook"], "remove": ["hook"]},
        {"op": "tag_snippet", "snippet_id": S1},
        {"op": "tag_snippet", "snippet_id": S1, "add": ["hook", "draft"], "remove": []},
        {"op": "tag_snippet", "snippet_id": S1, "remove": ["draft"]},
    )
    assert codes(data) == [(3, "invalid_field"), (4, "invalid_field")]
    by_index = {s["index"]: s for s in data["steps"]}
    assert by_index[1]["change"] is False and by_index[2]["change"] is False
    assert by_index[5]["summary"].endswith(": +Hook")  # draft already there
    assert by_index[6]["summary"].endswith(": -draft")


def test_tag_word(lc: Env) -> None:
    data = check(
        lc,
        {"op": "tag_word", "word_id": W1, "add": ["hook"], "remove": ["draft"]},
        {"op": "tag_word", "word_id": W2, "remove": ["draft"]},
        {"op": "tag_word", "word_id": "00000000-0000-0000-0000-00000000beef", "add": ["hook"]},
    )
    assert codes(data) == [(3, "unknown_word")]
    assert data["steps"][0]["summary"] == 'tag word "boulevard": +Hook -draft'
    assert data["steps"][1]["change"] is False


def test_move_rules(lc: Env) -> None:
    data = check(
        lc,
        {"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "Drafts"},
        {"op": "move", "snippet_id": S4, "from_canvas": "Drafts", "to_canvas": "Dup"},
        {"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "Album"},
        {"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "Album"},
        {"op": "move", "snippet_id": S3, "from_canvas": "Drafts", "to_canvas": "Album"},
    )
    assert codes(data) == [(1, "same_canvas"), (2, "ambiguous_canvas")]
    by_index = {s["index"]: s for s in data["steps"]}
    assert by_index[3]["change"] is True
    assert by_index[4]["change"] is False  # already moved by op 3
    assert "only removed from" in by_index[5]["details"][0]  # S3 was on both


def test_move_not_on_source(lc: Env) -> None:
    data = check(lc, {"op": "move", "snippet_id": S4, "from_canvas": "Drafts", "to_canvas": DUP1})
    assert codes(data) == [(1, "not_on_canvas")]


def test_show_on_and_remove_from(lc: Env) -> None:
    data = check(
        lc,
        {"op": "show_on", "snippet_id": S3, "canvas": "Album"},
        {"op": "remove_from", "snippet_id": S1, "canvas": "Drafts"},
        {"op": "remove_from", "snippet_id": S1, "canvas": "Album"},
        {"op": "remove_from", "snippet_id": S3, "canvas": "Drafts"},
        {"op": "remove_from", "snippet_id": S3, "canvas": "Album"},
        {"op": "show_on", "snippet_id": S1, "canvas": "Album"},
        {"op": "remove_from", "snippet_id": S1, "canvas": "Drafts"},
    )
    assert codes(data) == [(2, "last_canvas"), (5, "last_canvas")]
    by_index = {s["index"]: s for s in data["steps"]}
    assert by_index[1]["change"] is False
    assert by_index[3]["change"] is False  # S1 is not on Album yet
    assert by_index[4]["change"] is True
    assert by_index[7]["change"] is True  # op 6 put S1 on Album first


def test_set_layout(lc: Env) -> None:
    data = check(
        lc,
        {"op": "set_layout", "snippet_id": S1, "canvas": "Drafts", "position_x": 10, "list_rank": "Zz"},
        {"op": "set_layout", "snippet_id": S1, "canvas": "Album", "position_x": 10},
        {"op": "set_layout", "snippet_id": S1, "canvas": "Drafts", "list_rank": "a00"},
        {"op": "set_layout", "snippet_id": S2, "canvas": "Drafts", "position_x": 0, "list_rank": "a0"},
    )
    assert codes(data) == [(2, "not_on_canvas"), (3, "invalid_rank")]
    assert data["steps"][0]["details"] == ["position_x: 0.0 -> 10", 'list_rank: "a1" -> "Zz"']
    assert data["steps"][1]["change"] is False


def test_reorder_list(lc: Env) -> None:
    data = check(
        lc,
        {"op": "reorder_list", "canvas": "Drafts", "snippet_ids": [S3, S1]},
        {"op": "reorder_list", "canvas": "Drafts", "snippet_ids": [S3]},
        {"op": "reorder_list", "canvas": "Drafts", "snippet_ids": [S1, S1]},
        {"op": "reorder_list", "canvas": "Drafts", "snippet_ids": [S4]},
    )
    assert codes(data) == [(3, "duplicate_snippet"), (4, "not_on_canvas")]
    first = data["steps"][0]
    assert first["change"] is True
    assert first["details"][0].startswith("  1. " + S3[:8]) and first["details"][0].endswith("(was 3)")
    assert first["details"][1].startswith("  2. " + S1[:8]) and "(was" not in first["details"][1]
    assert first["details"][2].startswith("  3. " + S2[:8]) and first["details"][2].endswith("(was 1)")
    assert data["steps"][1]["change"] is False  # S3 is already on top after op 1


def test_reorder_sees_moved_snippets_on_top(lc: Env) -> None:
    data = check(
        lc,
        {"op": "move", "snippet_id": S2, "from_canvas": "Drafts", "to_canvas": "Album"},
        {"op": "reorder_list", "canvas": "Album", "snippet_ids": [S2]},
        {"op": "reorder_list", "canvas": "Album", "snippet_ids": [S3, S4, S2]},
    )
    assert data["ok"]
    assert data["steps"][1]["change"] is False  # moved snippets land at the top


def test_edit_text_base_version(lc: Env) -> None:
    data = check(
        lc,
        {"op": "edit_text", "snippet_id": S1, "raw_text": "new words", "base_version": 2},
        {"op": "edit_text", "snippet_id": S2, "raw_text": "Second thoughts", "base_version": 1},
        {"op": "edit_text", "snippet_id": S3, "raw_text": "changed", "base_version": 1},
        {"op": "edit_text", "snippet_id": S3, "raw_text": "again", "base_version": 1},
    )
    assert codes(data) == [(1, "version_conflict"), (4, "invalid_field")]
    assert "version 3" in data["issues"][0]["message"]
    assert data["steps"][0]["change"] is False  # same text
    assert data["steps"][1]["details"] == ["- Third verse here", "+ changed"]


def test_add_bank_word(lc: Env) -> None:
    data = check(
        lc,
        {"op": "add_bank_word", "text": "  glow   up ", "tags": ["hook"]},
        {"op": "add_bank_word", "text": "Boulevard"},
        {"op": "add_bank_word", "text": "boulevard", "tags": ["hook"]},
        {"op": "add_bank_word", "text": "glow up", "tags": ["Hook"]},
        {"op": "add_bank_word", "text": "", "tags": []},
        {"op": "add_bank_word", "text": "x", "tags": ["nope"]},
    )
    assert codes(data) == [(5, "invalid_field"), (6, "unknown_tag")]
    by_index = {s["index"]: s for s in data["steps"]}
    assert by_index[1]["summary"] == 'add word "glow up" to the word bank tagged Hook'
    assert by_index[2]["change"] is False
    assert by_index[3]["change"] is True and "only links the tags" in by_index[3]["details"][0]
    assert by_index[4]["change"] is False


def test_create_and_rename_validation(lc: Env) -> None:
    data = check(
        lc,
        {"op": "create_tag", "name": "   "},
        {"op": "create_tag", "name": "ok", "color": "red"},
        {"op": "create_canvas", "title": " ", "ref": "$x"},
        {"op": "rename_canvas", "canvas": "Drafts", "title": "Drafts"},
        {"op": "rename_canvas", "canvas": "Album", "title": "x" * 256},
    )
    assert codes(data) == [(1, "invalid_field"), (2, "invalid_field"), (3, "invalid_field"), (5, "invalid_field")]
    assert data["steps"][0]["change"] is False


def test_text_diff_rendering(lc: Env) -> None:
    ops = [
        {"op": "create_tag", "name": "Night"},
        {"op": "tag_snippet", "snippet_id": S1, "add": ["Night"], "remove": ["draft"]},
        {"op": "create_canvas", "title": "Night songs", "ref": "$n"},
        {"op": "move", "snippet_id": S1, "from_canvas": "Drafts", "to_canvas": "$n"},
        {"op": "tag_snippet", "snippet_id": S2, "add": []},
        {"op": "remove_from", "snippet_id": S4, "canvas": "Drafts"},
    ]
    r = lc.run("plan", "check", lc.plan_file(ops, description="Night themes"))
    assert r.code == 1
    lines = r.out.splitlines()
    assert lines[0] == "Plan: Night themes"
    assert lines[1] == "6 ops: 4 changes, 1 already done, 1 problems"
    assert '  1. * create tag "Night"' in lines
    assert f'  2. * tag snippet {S1[:8]} [verse] "Midnight on the boulevard": +Night -draft' in lines
    assert '  3. * create canvas "Night songs" as $n' in lines
    assert f'  4. * move snippet {S1[:8]} [verse] "Midnight on the boulevard" from "Drafts" to "Night songs" ($n)' in lines
    assert any(line.startswith(f"  6. = remove snippet {S4[:8]}") and "(no change: not on" in line for line in lines)
    assert "Problems (nothing will be applied until they are fixed):" in lines
    assert "  5. ! invalid_field: give at least one tag in add or remove" in lines


def test_invalid_plan_file(lc: Env) -> None:
    path = lc.tmp / "bad.json"
    path.write_text("{not json")
    r = lc.run("plan", "check", str(path))
    assert r.code == 1 and "not JSON" in r.err
    path.write_text('{"version": 1, "ops": [{"op": "delete_canvas", "canvas": "x"}]}')
    r = lc.run("plan", "check", str(path))
    assert r.code == 1 and "unknown_op" in r.err
    assert lc.backend.requests == []
    missing = lc.run("plan", "check", str(lc.tmp / "missing.json"))
    assert missing.code == 2


def test_long_first_line_is_truncated(lc: Env) -> None:
    lc.backend.snippets[S2]["raw_text"] = "\n  " + "word " * 20
    data = check(lc, {"op": "tag_snippet", "snippet_id": S2, "add": ["hook"]})
    assert '"word word word word word word word word…"' in data["steps"][0]["summary"]


def test_moved_snippet_label_uses_drafts_id(lc: Env) -> None:
    data = check(lc, {"op": "show_on", "snippet_id": S1, "canvas": DRAFTS})
    assert data["steps"][0]["change"] is False
